/**
 * ════════════════════════════════════════════════════════════════════
 *  lrec_engine.cpp  —  مكتبة فك ضغط .lrec  (مكتبة خاصة بنا)
 *
 *  تُنفِّذ نفس منطق LrecParser.kt بالكامل في كود C++ أسرع:
 *    • تحليل بنية الملف (block scanning)
 *    • فك ضغط zlib لكل إطار
 *    • استخراج لوحة الألوان (256 لون RGBQUAD)
 *    • تطبيق الإطارات الكاملة والجزئية (Full + Delta)
 *    • إدارة حالة الشاشة بين الإطارات
 *    • بحث ثنائي سريع للانتقال الزمني
 *
 *  بنية ملف .lrec:
 *    8 bytes header (أصفار)
 *    كتل متكررة: [0x10][0x04][len_lo][len_hi][data×len]
 *    النوع 0x03 = كتل الشاشة (viewport)
 *      • len=44   → metadata
 *      • len=1036 → palette (256 × RGBQUAD)
 *      • data[12]=0x78 + data[6]=0x02 → full frame (zlib)
 *      • data[12]=0x78 + data[6]=0x01 → delta frame (zlib)
 *
 *  مرجع التحليل: Inter-Tel Collaboration Client 2.0 / Linktivity
 * ════════════════════════════════════════════════════════════════════
 */

#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <atomic>
#include <algorithm>
#include <sys/types.h>   // off_t
#include <zlib.h>
#include <android/log.h>

#define LOG_TAG "LrecEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ════════════════════════════════════════════════════════════════════
//  ثوابت بنية الملف (مستخرجة من التحليل العكسي)
// ════════════════════════════════════════════════════════════════════
static const uint8_t  MARKER_0     = 0x10;
static const uint8_t  MARKER_1     = 0x04;
static const int      TYPE_VIEWPORT = 0x03;

static const int      SUB_METADATA  = 0;
static const int      SUB_PALETTE   = 1;
static const int      SUB_FULL      = 2;
static const int      SUB_DELTA     = 3;

static const int      ZLIB_OFF      = 12;   // zlib header يبدأ عند offset 12 داخل block data
static const int      FULL_W_OFF    = 9;    // Width  في decompressed[9]  (LE U16)
static const int      FULL_H_OFF    = 13;   // Height في decompressed[13] (LE U16)
static const int      PIX_OFF       = 21;   // بيانات البكسل تبدأ عند decompressed[21]

static const int      REAL_W        = 1187;
static const int      REAL_H        = 834;
static const int      DEFAULT_FPS   = 5;
static const int64_t  MS_PER_FRAME  = 1000 / DEFAULT_FPS;   // 200 ms
static const int      DECOMP_MAX    = 2 * 1024 * 1024;       // 2 MB — يكفي لأي إطار

// ════════════════════════════════════════════════════════════════════
//  بنية إطار واحد في جدول الإطارات
// ════════════════════════════════════════════════════════════════════
struct FrameEntry {
    int64_t  file_offset;      // موقع بداية الكتلة (markers included) في الملف
    int      block_data_len;   // طول data بعد الـ markers الأربعة
    int      type;             // SUB_FULL أو SUB_DELTA
    int64_t  timestamp_ms;     // الوقت بالميلي-ثانية من بداية التسجيل
};

// ════════════════════════════════════════════════════════════════════
//  هيكل البيانات الداخلي لكل ملف مفتوح
// ════════════════════════════════════════════════════════════════════
struct LrecHandle {
    FILE*                   fp            = nullptr;
    std::vector<FrameEntry> frames;
    std::vector<int>        full_indices;  // فهارس الإطارات الكاملة للبحث السريع
    int                     palette[256]  = {};   // ARGB packed (Android format)
    bool                    has_palette   = false;
    int                     screen_w      = REAL_W;
    int                     screen_h      = REAL_H;
    int*                    pix_buf       = nullptr; // الشاشة الحالية (ARGB × w × h)
    uint8_t*                decomp_buf    = nullptr; // buffer مؤقت لـ zlib
    int                     last_rendered = -1;      // آخر إطار تم رسمه
    std::mutex              mtx;

    ~LrecHandle() {
        if (fp)         { fclose(fp);         fp         = nullptr; }
        if (pix_buf)    { free(pix_buf);       pix_buf    = nullptr; }
        if (decomp_buf) { free(decomp_buf);    decomp_buf = nullptr; }
    }
};

// ── سجل المقابض النشطة ────────────────────────────────────────────
static std::mutex                              g_reg_mtx;
static std::unordered_map<int64_t,LrecHandle*> g_reg;
static std::atomic<int64_t>                   g_id_gen{1};

static LrecHandle* lookup(int64_t id) {
    std::lock_guard<std::mutex> lk(g_reg_mtx);
    auto it = g_reg.find(id);
    return it != g_reg.end() ? it->second : nullptr;
}

// ════════════════════════════════════════════════════════════════════
//  مساعدات القراءة
// ════════════════════════════════════════════════════════════════════
static inline uint16_t u16le(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
static inline uint32_t u32le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0] | (p[1]<<8) | (p[2]<<16) | (p[3]<<24));
}

// ════════════════════════════════════════════════════════════════════
//  فك ضغط zlib مع بديل raw deflate
// ════════════════════════════════════════════════════════════════════
static int do_inflate(const uint8_t* src, int src_len,
                      uint8_t* dst,       int dst_max) {
    if (src_len < 2) return -1;

    // ── محاولة 1: zlib مع header ─────────────────────────────────
    z_stream z;
    memset(&z, 0, sizeof(z));
    if (inflateInit(&z) == Z_OK) {
        z.next_in   = const_cast<Bytef*>(src);
        z.avail_in  = static_cast<uInt>(src_len);
        z.next_out  = reinterpret_cast<Bytef*>(dst);
        z.avail_out = static_cast<uInt>(dst_max);
        int r   = inflate(&z, Z_FINISH);
        int out = dst_max - static_cast<int>(z.avail_out);
        inflateEnd(&z);
        if (out > 0 && (r == Z_STREAM_END || r == Z_OK || r == Z_BUF_ERROR))
            return out;
    }

    // ── محاولة 2: raw deflate (تجاهل أول 2 bytes من zlib header) ─
    if (src_len < 4) return -1;
    memset(&z, 0, sizeof(z));
    if (inflateInit2(&z, -MAX_WBITS) == Z_OK) {
        z.next_in   = const_cast<Bytef*>(src + 2);
        z.avail_in  = static_cast<uInt>(src_len - 2);
        z.next_out  = reinterpret_cast<Bytef*>(dst);
        z.avail_out = static_cast<uInt>(dst_max);
        int r   = inflate(&z, Z_FINISH);
        int out = dst_max - static_cast<int>(z.avail_out);
        inflateEnd(&z);
        if (out > 0) return out;
    }
    return -1;
}

// ════════════════════════════════════════════════════════════════════
//  تصنيف كتلة viewport
// ════════════════════════════════════════════════════════════════════
static int classify(const uint8_t* d, int len) {
    if (len < 13) return SUB_METADATA;
    uint8_t b12 = d[12];
    uint8_t b13 = (len > 13) ? d[13] : 0;
    bool has_zlib = (b12 == 0x78) &&
                    (b13 == 0x01 || b13 == 0x5E || b13 == 0x9C || b13 == 0xDA);
    if (!has_zlib) {
        if (len == 44)   return SUB_METADATA;
        if (len == 1036) return SUB_PALETTE;
        return SUB_METADATA;
    }
    return ((d[6] & 0xFF) == 0x02) ? SUB_FULL : SUB_DELTA;
}

// ════════════════════════════════════════════════════════════════════
//  استخراج لوحة الألوان (256 لون RGBQUAD → ARGB)
// ════════════════════════════════════════════════════════════════════
static void read_palette(LrecHandle* h, const uint8_t* d, int len) {
    if (len < 12 + 1024) return;
    const uint8_t* p = d + 12;
    for (int i = 0; i < 256; i++) {
        uint8_t b = p[i*4];
        uint8_t g = p[i*4 + 1];
        uint8_t r = p[i*4 + 2];
        // Android Bitmap format = ARGB_8888
        h->palette[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
    h->has_palette = true;
}

// ════════════════════════════════════════════════════════════════════
//  مسح الملف كاملاً وبناء جدول الإطارات
// ════════════════════════════════════════════════════════════════════
static bool scan_file(LrecHandle* h) {
    // حجم الملف بـ fseeko/ftello (64-bit safe على armeabi-v7a أيضاً)
    fseeko(h->fp, 0, SEEK_END);
    int64_t file_size = static_cast<int64_t>(ftello(h->fp));
    if (file_size < 64) return false;

    h->frames.reserve(4096);

    int64_t pos = 8;    // تجاوز الـ 8 bytes header
    int64_t ts  = 0;    // timestamp بالميلي-ثانية
    uint8_t hdr[4];

    while (pos <= file_size - 4) {
        fseeko(h->fp, static_cast<off_t>(pos), SEEK_SET);
        if (fread(hdr, 1, 4, h->fp) != 4) break;

        // ابحث عن الـ marker
        if (hdr[0] != MARKER_0 || hdr[1] != MARKER_1) {
            pos++;
            continue;
        }

        // طول الكتلة (LE U16)
        int dlen = hdr[2] | (hdr[3] << 8);
        if (dlen < 1 || dlen > 500000 || pos + 4 + dlen > file_size) {
            pos++;
            continue;
        }

        // اقرأ بيانات الكتلة
        uint8_t* bd = static_cast<uint8_t*>(malloc(dlen));
        if (!bd) { pos += 4 + dlen; continue; }

        if (static_cast<int>(fread(bd, 1, dlen, h->fp)) != dlen) {
            free(bd); pos++; continue;
        }

        // هل هي كتلة viewport؟
        if (dlen >= 8 && (bd[1] & 0xFF) == TYPE_VIEWPORT) {
            int sub = classify(bd, dlen);
            switch (sub) {
                case SUB_PALETTE:
                    read_palette(h, bd, dlen);
                    break;

                case SUB_FULL:
                case SUB_DELTA: {
                    FrameEntry fe;
                    fe.file_offset    = pos;
                    fe.block_data_len = dlen;
                    fe.type           = sub;
                    fe.timestamp_ms   = ts;
                    if (sub == SUB_FULL) {
                        h->full_indices.push_back(static_cast<int>(h->frames.size()));
                    }
                    h->frames.push_back(fe);
                    ts += MS_PER_FRAME;
                    break;
                }
                default: break;
            }
        }

        free(bd);
        pos += 4 + dlen;
    }

    LOGI("scan_file: %d frames, palette=%s",
         (int)h->frames.size(), h->has_palette ? "OK" : "default_gray");
    return !h->frames.empty();
}

// ════════════════════════════════════════════════════════════════════
//  تطبيق إطار واحد على h->pix_buf
// ════════════════════════════════════════════════════════════════════
static bool apply_frame(LrecHandle* h, int idx) {
    if (idx < 0 || idx >= static_cast<int>(h->frames.size())) return false;
    const FrameEntry& fe = h->frames[idx];

    // اقرأ البيانات الخام من الملف (تجاوز 4 bytes: markers + length)
    fseeko(h->fp, static_cast<off_t>(fe.file_offset + 4), SEEK_SET);
    uint8_t* raw = static_cast<uint8_t*>(malloc(fe.block_data_len));
    if (!raw) return false;

    if (static_cast<int>(fread(raw, 1, fe.block_data_len, h->fp)) != fe.block_data_len) {
        free(raw); return false;
    }

    if (fe.block_data_len <= ZLIB_OFF + 2) { free(raw); return false; }

    // فك الضغط
    int dsize = do_inflate(raw + ZLIB_OFF,
                           fe.block_data_len - ZLIB_OFF,
                           h->decomp_buf,
                           DECOMP_MAX);
    free(raw);

    if (dsize <= PIX_OFF) return false;

    const uint8_t* db = h->decomp_buf;

    if (fe.type == SUB_FULL) {
        // ── إطار كامل ─────────────────────────────────────────────
        int w  = (dsize > FULL_W_OFF + 1) ? static_cast<int>(u16le(db + FULL_W_OFF)) : 0;
        int hh = (dsize > FULL_H_OFF + 1) ? static_cast<int>(u16le(db + FULL_H_OFF)) : 0;

        // استخدم الأبعاد الحقيقية إذا كانت القراءة خاطئة
        if (w  < 64 || w  > 4096) w  = h->screen_w;
        if (hh < 64 || hh > 4096) hh = h->screen_h;

        if (dsize < PIX_OFF + w * hh) return false;

        int cw = std::min(w,  h->screen_w);
        int ch = std::min(hh, h->screen_h);

        for (int y = 0; y < ch; y++) {
            const uint8_t* row = db + PIX_OFF + y * w;
            int* dst = h->pix_buf + y * h->screen_w;
            for (int x = 0; x < cw; x++) {
                dst[x] = h->palette[row[x]];
            }
        }

    } else {
        // ── إطار جزئي (delta) ─────────────────────────────────────
        // كل قيمة = القيمة الحقيقية × 256 (LE U32)
        if (dsize < 21) return true;  // إطار فارغ — ليس خطأً

        int dx = static_cast<int>(u32le(db +  0) >> 8);
        int dy = static_cast<int>(u32le(db +  4) >> 8);
        int dw = static_cast<int>(u32le(db +  8) >> 8);
        int dh = static_cast<int>(u32le(db + 12) >> 8);

        // تحقق من صحة الأبعاد
        if (dw <= 0 || dh <= 0 || dx < 0 || dy < 0)  return true;
        if (dx + dw > h->screen_w || dy + dh > h->screen_h) return true;
        if (dsize < PIX_OFF + dw * dh) return true;

        for (int y = 0; y < dh; y++) {
            const uint8_t* row = db + PIX_OFF + y * dw;
            int* dst = h->pix_buf + (dy + y) * h->screen_w + dx;
            for (int x = 0; x < dw; x++) {
                dst[x] = h->palette[row[x]];
            }
        }
    }
    return true;
}

// ════════════════════════════════════════════════════════════════════
//  رسم الشاشة حتى الإطار المطلوب
//  (يُعيد استخدام الحالة الحالية للانتقال إلى الأمام)
//  (يبحث عن أقرب Full Frame للانتقال للخلف)
// ════════════════════════════════════════════════════════════════════
static bool render_to(LrecHandle* h, int target_idx) {
    if (target_idx < 0 || target_idx >= static_cast<int>(h->frames.size())) return false;

    int start;

    if (h->last_rendered == target_idx) {
        return true;  // نفس الإطار — لا عمل مطلوب
    }

    if (h->last_rendered >= 0 && target_idx > h->last_rendered) {
        // تقدّم للأمام: ابدأ من بعد آخر إطار مرسوم
        start = h->last_rendered + 1;
    } else {
        // انتقال للخلف أو البداية: ابحث عن أقرب Full Frame
        start = 0;
        for (int fi : h->full_indices) {
            if (fi <= target_idx) start = fi;
            else break;
        }
    }

    // طبِّق الإطارات من start إلى target_idx
    for (int i = start; i <= target_idx; i++) {
        apply_frame(h, i);
    }

    h->last_rendered = target_idx;
    return true;
}

// ════════════════════════════════════════════════════════════════════
//  تصدير دوال JNI
// ════════════════════════════════════════════════════════════════════
extern "C" {

// ── nativeOpen ───────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_lrec_operator_LrecEngine_nativeOpen(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0L;

    FILE* fp = fopen(path, "rb");
    env->ReleaseStringUTFChars(jpath, path);

    if (!fp) { LOGE("fopen failed"); return 0L; }

    auto* h = new LrecHandle();
    h->fp = fp;

    // تخصيص الذاكرة
    h->pix_buf   = static_cast<int*>    (calloc(REAL_W * REAL_H, sizeof(int)));
    h->decomp_buf= static_cast<uint8_t*>(malloc(DECOMP_MAX));

    if (!h->pix_buf || !h->decomp_buf) {
        delete h; LOGE("malloc failed"); return 0L;
    }

    // لوحة رمادية افتراضية حتى نقرأ اللوحة الحقيقية
    for (int i = 0; i < 256; i++) {
        h->palette[i] = (0xFF << 24) | (i << 16) | (i << 8) | i;
    }

    if (!scan_file(h)) {
        delete h; LOGE("scan_file found no frames"); return 0L;
    }

    int64_t id = g_id_gen.fetch_add(1);
    {
        std::lock_guard<std::mutex> lk(g_reg_mtx);
        g_reg[id] = h;
    }
    LOGI("opened handle %lld: %d frames %dx%d",
         (long long)id, (int)h->frames.size(), h->screen_w, h->screen_h);
    return static_cast<jlong>(id);
}

// ── nativeGetFrameCount ──────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_nativeGetFrameCount(JNIEnv*, jobject, jlong id) {
    LrecHandle* h = lookup(id);
    return h ? static_cast<jint>(h->frames.size()) : 0;
}

// ── nativeGetScreenWidth ─────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_nativeGetScreenWidth(JNIEnv*, jobject, jlong id) {
    LrecHandle* h = lookup(id);
    return h ? h->screen_w : REAL_W;
}

// ── nativeGetScreenHeight ────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_nativeGetScreenHeight(JNIEnv*, jobject, jlong id) {
    LrecHandle* h = lookup(id);
    return h ? h->screen_h : REAL_H;
}

// ── nativeGetDurationMs ──────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_lrec_operator_LrecEngine_nativeGetDurationMs(JNIEnv*, jobject, jlong id) {
    LrecHandle* h = lookup(id);
    if (!h || h->frames.empty()) return 0L;
    return static_cast<jlong>(h->frames.back().timestamp_ms + MS_PER_FRAME);
}

// ── nativeGetFrameTimestamp ──────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_lrec_operator_LrecEngine_nativeGetFrameTimestamp(
        JNIEnv*, jobject, jlong id, jint idx) {
    LrecHandle* h = lookup(id);
    if (!h || idx < 0 || idx >= static_cast<int>(h->frames.size())) return 0L;
    return static_cast<jlong>(h->frames[idx].timestamp_ms);
}

// ── nativeRenderFrame ────────────────────────────────────────────────
// يرسم الإطار المطلوب ويكتب pixels ARGB في outPixels
JNIEXPORT jboolean JNICALL
Java_com_lrec_operator_LrecEngine_nativeRenderFrame(
        JNIEnv* env, jobject, jlong id, jint frame_idx, jintArray out_pixels) {
    LrecHandle* h = lookup(id);
    if (!h || !out_pixels) return JNI_FALSE;

    std::lock_guard<std::mutex> lk(h->mtx);

    if (!render_to(h, static_cast<int>(frame_idx))) return JNI_FALSE;

    jsize arr_len   = env->GetArrayLength(out_pixels);
    jsize pix_count = static_cast<jsize>(h->screen_w * h->screen_h);
    jsize copy_n    = std::min(arr_len, pix_count);

    env->SetIntArrayRegion(out_pixels, 0, copy_n, h->pix_buf);
    return JNI_TRUE;
}

// ── nativeFindFrameAtMs ──────────────────────────────────────────────
// بحث ثنائي: يُعيد index أقرب إطار للوقت المطلوب
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_nativeFindFrameAtMs(
        JNIEnv*, jobject, jlong id, jlong target_ms) {
    LrecHandle* h = lookup(id);
    if (!h || h->frames.empty()) return 0;

    int lo = 0, hi = static_cast<int>(h->frames.size()) - 1;
    while (lo < hi) {
        int mid = (lo + hi + 1) / 2;
        if (h->frames[mid].timestamp_ms <= static_cast<int64_t>(target_ms)) lo = mid;
        else hi = mid - 1;
    }
    return static_cast<jint>(lo);
}

// ── nativeClose ──────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_lrec_operator_LrecEngine_nativeClose(JNIEnv*, jobject, jlong id) {
    LrecHandle* h = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_reg_mtx);
        auto it = g_reg.find(id);
        if (it == g_reg.end()) return;
        h = it->second;
        g_reg.erase(it);
    }
    delete h;
    LOGI("closed handle %lld", (long long)id);
}

} // extern "C"

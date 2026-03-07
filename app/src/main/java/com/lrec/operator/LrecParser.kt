package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * ══════════════════════════════════════════════════════════════════
 *  LrecParser — محلّل ملفات .lrec  (النسخة المُصحَّحة)
 *
 *  الصيغة: Inter-Tel Collaboration Client 2.0 (Build 4.2.7.0)
 *  الشركة: Linktivity / Inter-Tel Delaware Inc. © 2007
 *
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │  بنية الملف المُكتشَفة بالتحليل العكسي:                    │
 *  │  • 8 bytes : رأس الملف (أصفار)                              │
 *  │  • كتل متكررة:                                              │
 *  │      marker[0] = 0x10                                        │
 *  │      marker[1] = 0x04                                        │
 *  │      length[2] = little-endian U16                           │
 *  │      data[length bytes]                                      │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │  أنواع الكتل (byte[1] من البيانات):                         │
 *  │  0x08 = رأس الملف / Metadata                                │
 *  │  0x03 = إطارات الشاشة — مضغوطة بـ zlib من offset 12       │
 *  │  0x02 = حزم TCP مشفرة (بيانات شبكة)                        │
 *  │  0x01 = بيانات وصفية إضافية                                 │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │  بنية إطار الشاشة (بعد فك zlib):                           │
 *  │  [0:21]  = header داخلي                                     │
 *  │     [9:11]  = x البداية (little-endian U16)                 │
 *  │     [13:15] = y البداية                                     │
 *  │  [21:]  = بيانات البكسل (8-bit indexed)                     │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │  الأبعاد الحقيقية للشاشة: 1187 × 834 pixels                │
 *  │  إطار الشاشة الكامل (full frame): نوع 0x03 بـ byte[7]=0x02 │
 *  │  إطار تحديث جزئي (delta frame): نوع 0x03 بـ byte[7]=0x01  │
 *  └─────────────────────────────────────────────────────────────┘
 * ══════════════════════════════════════════════════════════════════
 */
class LrecParser(private val file: File) {

    companion object {
        const val BLOCK_MARKER_0 = 0x10
        const val BLOCK_MARKER_1 = 0x04

        // أنواع الكتل — byte[1] من payload
        const val TYPE_HEADER   = 0x08  // رأس الملف
        const val TYPE_VIEWPORT = 0x03  // إطارات الشاشة (zlib)
        const val TYPE_NETWORK  = 0x02  // حزم TCP مشفرة
        const val TYPE_META     = 0x01  // بيانات وصفية

        // للتوافق مع LrecPlayerActivity
        const val TYPE_AUDIO    = TYPE_NETWORK
        const val TYPE_CHAT     = TYPE_META
        const val TYPE_VIDEO    = TYPE_HEADER

        const val DEFAULT_FPS   = 5
        const val MS_PER_FRAME  = 1000L / DEFAULT_FPS

        // الأبعاد الحقيقية للشاشة (مُكتشَفة من تحليل الملف)
        const val REAL_SCREEN_WIDTH  = 1187
        const val REAL_SCREEN_HEIGHT = 834

        const val LINKTIVITY_SIG    = "Linktivity"
        const val HEADER_SCAN_BYTES = 2048

        // offset بداية zlib داخل كتلة type03
        private const val ZLIB_OFFSET = 12

        // offset بداية بيانات البكسل داخل الإطار المفكوك
        private const val PIXEL_HEADER_SIZE = 21

        // offset الإحداثيات داخل الإطار المفكوك
        private const val COORD_X_OFFSET = 9
        private const val COORD_Y_OFFSET = 13
    }

    // ── نماذج البيانات ────────────────────────────────────────────

    data class LrecMetadata(
        val sessionId:    String  = "",
        val version:      String  = "V1.0.1.0",
        val serverAddr:   String  = "",
        val screenWidth:  Int     = REAL_SCREEN_WIDTH,
        val screenHeight: Int     = REAL_SCREEN_HEIGHT,
        val fps:          Int     = DEFAULT_FPS,
        val isValid:      Boolean = false
    )

    data class LrecFrame(
        val fileOffset: Long,
        val type:       Int,
        val dataLength: Int,
        val timestamp:  Long,
        val rawData:    ByteArray
    )

    data class ScreenFrameData(
        val x:           Int,
        val y:           Int,
        val width:       Int,
        val height:      Int,
        val pixels:      IntArray,
        val isFullFrame: Boolean
    )

    // ── الحالة الداخلية ───────────────────────────────────────────
    var metadata = LrecMetadata()
        private set

    private val _frames     = mutableListOf<LrecFrame>()
    private var _durationMs = 0L

    // لوحة الألوان (256 لون — مستخرجة من الإطار الأول)
    private val colorPalette = IntArray(256) { i ->
        // لوحة افتراضية (رمادي) حتى نحصل على اللوحة الحقيقية
        val v = (i * 255 / 255)
        Color.rgb(v, v, v)
    }
    private var hasPalette = false

    // ══════════════════════════════════════════════════════════════
    //  الدالة الرئيسية
    // ══════════════════════════════════════════════════════════════
    fun parse(): Boolean {
        if (!file.exists() || file.length() < 64) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                parseHeader(raf)
                scanAllFrames(raf)
                _durationMs = estimateDuration()
                metadata.isValid && _frames.isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── قراءة رأس الملف ──────────────────────────────────────────
    private fun parseHeader(raf: RandomAccessFile) {
        raf.seek(0)
        val scanSize = HEADER_SCAN_BYTES.coerceAtMost(file.length().toInt())
        val buf      = ByteArray(scanSize)
        raf.read(buf)
        val raw = String(buf, Charsets.ISO_8859_1)

        val sigIdx = raw.indexOf(LINKTIVITY_SIG)

        // استخراج عنوان السيرفر
        val serverAddr = raw.substringAfter("TCP:", "").substringBefore("\u0000", "")

        // استخراج رقم الإصدار
        val versionMatch = Regex("V\\d+\\.\\d+\\.\\d+\\.\\d+").find(raw)
        val version      = versionMatch?.value ?: "V1.0.1.0"

        // استخراج sessionId قبل "Linktivity"
        val sessionId = if (sigIdx > 4) {
            extractNullTerminatedStrings(buf, 0, sigIdx).lastOrNull() ?: ""
        } else ""

        metadata = LrecMetadata(
            sessionId    = sessionId,
            version      = version,
            serverAddr   = serverAddr,
            screenWidth  = REAL_SCREEN_WIDTH,
            screenHeight = REAL_SCREEN_HEIGHT,
            fps          = DEFAULT_FPS,
            isValid      = true
        )
    }

    // ── مسح جميع الكتل ───────────────────────────────────────────
    private fun scanAllFrames(raf: RandomAccessFile) {
        var pos      = 8L
        var tsMs     = 0L
        val fileSize = raf.length()

        while (pos <= fileSize - 4) {
            raf.seek(pos)
            val b0 = raf.read()
            val b1 = raf.read()

            if (b0 == BLOCK_MARKER_0 && b1 == BLOCK_MARKER_1) {
                val lo  = raf.read()
                val hi  = raf.read()
                if (lo < 0 || hi < 0) break

                val dataLen = (hi shl 8) or lo

                if (dataLen in 1..500_000 && pos + 4 + dataLen <= fileSize) {
                    val data = ByteArray(dataLen)
                    val read = raf.read(data)

                    if (read == dataLen && data.size >= 2) {
                        // ✅ التحديد الصحيح للنوع: byte[1] مباشرة
                        val frameType = when (data[1].toInt() and 0xFF) {
                            0x03 -> TYPE_VIEWPORT
                            0x02 -> TYPE_NETWORK
                            0x01 -> TYPE_META
                            0x08 -> TYPE_HEADER
                            else -> TYPE_META
                        }

                        _frames.add(
                            LrecFrame(
                                fileOffset = pos,
                                type       = frameType,
                                dataLength = dataLen,
                                timestamp  = tsMs,
                                rawData    = data
                            )
                        )

                        // تقدم الزمن لكل إطار شاشة فقط
                        if (frameType == TYPE_VIEWPORT) {
                            tsMs += MS_PER_FRAME
                        }
                    }
                    pos += 4 + dataLen
                } else {
                    pos++
                }
            } else {
                pos++
            }
        }
    }

    private fun estimateDuration(): Long {
        if (_frames.isEmpty()) return 0L
        return _frames.last().timestamp + MS_PER_FRAME
    }

    // ══════════════════════════════════════════════════════════════
    //  فك ضغط zlib لإطار شاشة
    // ══════════════════════════════════════════════════════════════
    private fun decompressFrame(rawData: ByteArray): ByteArray? {
        if (rawData.size <= ZLIB_OFFSET + 2) return null

        // ✅ تحقق من وجود zlib header عند offset 12
        val b0 = rawData[ZLIB_OFFSET].toInt() and 0xFF
        val b1 = rawData[ZLIB_OFFSET + 1].toInt() and 0xFF
        val hasZlibHeader = (b0 == 0x78) && (b1 in listOf(0x01, 0x5E, 0x9C, 0xDA))

        if (!hasZlibHeader) return null

        return try {
            val inflater = Inflater()
            inflater.setInput(rawData, ZLIB_OFFSET, rawData.size - ZLIB_OFFSET)
            val output = ByteArray(REAL_SCREEN_WIDTH * REAL_SCREEN_HEIGHT + 256)
            val size   = inflater.inflate(output)
            inflater.end()
            if (size > 0) output.copyOf(size) else null
        } catch (e: DataFormatException) {
            // partial decompress — بعض الإطارات مقطوعة عمداً
            try {
                val inflater = Inflater()
                inflater.setInput(rawData, ZLIB_OFFSET, rawData.size - ZLIB_OFFSET)
                val buf = ByteArray(2 * 1024 * 1024)
                var total = 0
                while (!inflater.finished() && !inflater.needsInput()) {
                    val n = inflater.inflate(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                inflater.end()
                if (total > 0) buf.copyOf(total) else null
            } catch (e2: Exception) { null }
        } catch (e: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════
    //  فك تشفير إطار الشاشة → بيانات بكسل
    // ══════════════════════════════════════════════════════════════
    fun decodeScreenFrame(frame: LrecFrame): ScreenFrameData? {
        if (frame.type != TYPE_VIEWPORT) return null
        val raw = frame.rawData

        // هل الإطار full frame أم delta؟
        // byte[7]: 0x02 = full frame, 0x01 = delta frame
        val isFullFrame = (raw.size >= 8) && (raw[7].toInt() and 0xFF == 0x02)

        // ✅ فك ضغط zlib
        val decompressed = decompressFrame(raw) ?: return null
        if (decompressed.size < PIXEL_HEADER_SIZE + 4) return null

        return try {
            val sw = metadata.screenWidth
            val sh = metadata.screenHeight

            if (isFullFrame) {
                // الإطار الكامل: بيانات البكسل تبدأ عند offset 21
                val pixelData = decompressed.copyOfRange(PIXEL_HEADER_SIZE, decompressed.size)
                val expectedPixels = sw * sh

                if (pixelData.size < expectedPixels) return null

                val pixels = IntArray(expectedPixels)
                for (i in 0 until expectedPixels) {
                    val idx = pixelData[i].toInt() and 0xFF
                    pixels[i] = colorPalette[idx]
                }

                // ✅ استخراج لوحة الألوان من الإطار الأول الكامل إذا أمكن
                if (!hasPalette) {
                    tryExtractPalette(decompressed)
                    if (hasPalette) {
                        // أعد التحويل باللوحة الجديدة
                        for (i in 0 until expectedPixels) {
                            val idx = pixelData[i].toInt() and 0xFF
                            pixels[i] = colorPalette[idx]
                        }
                    }
                }

                ScreenFrameData(
                    x           = 0,
                    y           = 0,
                    width       = sw,
                    height      = sh,
                    pixels      = pixels,
                    isFullFrame = true
                )
            } else {
                // الإطار الجزئي: يحتوي إحداثيات التحديث
                // [COORD_X_OFFSET:COORD_X_OFFSET+2] = x البداية
                // [COORD_Y_OFFSET:COORD_Y_OFFSET+2] = y البداية
                val x = readU16LE(decompressed, COORD_X_OFFSET)
                val y = readU16LE(decompressed, COORD_Y_OFFSET)

                val pixelData = decompressed.copyOfRange(PIXEL_HEADER_SIZE, decompressed.size)
                if (pixelData.isEmpty()) return null

                val pixelCount = pixelData.size
                val w = when {
                    pixelCount == 0         -> return null
                    x + (sw - x) <= sw      -> sw - x
                    else                    -> sw
                }

                // حساب الأبعاد من عدد البكسلات
                val estimatedH = if (w > 0) (pixelCount / w).coerceAtLeast(1) else 1
                val h          = estimatedH.coerceAtMost(sh - y)

                if (w <= 0 || h <= 0) return null

                val pixels = IntArray(w * h)
                for (i in 0 until minOf(pixels.size, pixelData.size)) {
                    val idx  = pixelData[i].toInt() and 0xFF
                    pixels[i] = colorPalette[idx]
                }

                ScreenFrameData(
                    x           = x.coerceIn(0, sw - 1),
                    y           = y.coerceIn(0, sh - 1),
                    width       = w,
                    height      = h,
                    pixels      = pixels,
                    isFullFrame = false
                )
            }
        } catch (e: Exception) { null }
    }

    // ── محاولة استخراج لوحة الألوان من بيانات الإطار الأول ──────
    private fun tryExtractPalette(decompressedFrame: ByteArray) {
        // في بعض الإطارات الكاملة الأولى تُخزَّن لوحة ألوان 256×4 bytes
        // قبل بيانات البكسل عند offset 21
        // حجم اللوحة = 256 * 4 = 1024 bytes
        val paletteStart = PIXEL_HEADER_SIZE
        val paletteEnd   = paletteStart + 256 * 4

        if (decompressedFrame.size < paletteEnd + 100) return

        // فحص صحة اللوحة: كل إدخال RGBQUAD = R,G,B,Reserved
        var valid = 0
        for (i in 0 until 256) {
            val r = decompressedFrame[paletteStart + i*4].toInt() and 0xFF
            val g = decompressedFrame[paletteStart + i*4+1].toInt() and 0xFF
            val b = decompressedFrame[paletteStart + i*4+2].toInt() and 0xFF
            if (r in 0..255 && g in 0..255 && b in 0..255) valid++
        }

        if (valid < 200) return  // لوحة غير صالحة

        for (i in 0 until 256) {
            val r = decompressedFrame[paletteStart + i*4].toInt() and 0xFF
            val g = decompressedFrame[paletteStart + i*4+1].toInt() and 0xFF
            val b = decompressedFrame[paletteStart + i*4+2].toInt() and 0xFF
            colorPalette[i] = Color.rgb(r, g, b)
        }
        hasPalette = true
    }

    // ══════════════════════════════════════════════════════════════
    //  تطبيق إطار على Bitmap الحالي
    // ══════════════════════════════════════════════════════════════
    fun applyFrameToBitmap(bitmap: Bitmap, frameData: ScreenFrameData) {
        if (frameData.isFullFrame) {
            bitmap.setPixels(
                frameData.pixels,
                0, frameData.width,
                0, 0,
                frameData.width.coerceAtMost(bitmap.width),
                frameData.height.coerceAtMost(bitmap.height)
            )
        } else {
            val safeX = frameData.x.coerceIn(0, bitmap.width  - 1)
            val safeY = frameData.y.coerceIn(0, bitmap.height - 1)
            val safeW = frameData.width.coerceAtMost(bitmap.width  - safeX)
            val safeH = frameData.height.coerceAtMost(bitmap.height - safeY)
            if (safeW > 0 && safeH > 0 && frameData.pixels.size >= safeW * safeH) {
                bitmap.setPixels(
                    frameData.pixels,
                    0, frameData.width,
                    safeX, safeY,
                    safeW, safeH
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  استخراج نص المحادثة (من كتل Type01)
    // ══════════════════════════════════════════════════════════════
    fun decodeChatFrame(frame: LrecFrame): String? {
        if (frame.rawData.size < 5) return null
        return try {
            val text = String(frame.rawData, 4, frame.rawData.size - 4, Charsets.UTF_8)
                .trim()
                .filter { it >= ' ' }
            text.takeIf { it.length > 1 }
        } catch (e: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════
    //  واجهة استرجاع الإطارات
    // ══════════════════════════════════════════════════════════════
    fun getAllFrames():    List<LrecFrame> = _frames.toList()
    fun getScreenFrames():List<LrecFrame> = _frames.filter { it.type == TYPE_VIEWPORT }
    fun getAudioFrames(): List<LrecFrame> = _frames.filter { it.type == TYPE_NETWORK }
    fun getChatFrames():  List<LrecFrame> = _frames.filter { it.type == TYPE_META }
    fun getDurationMs():  Long            = _durationMs
    fun getTotalFrames(): Int             = _frames.size

    fun getFrameAtTime(timeMs: Long): LrecFrame? =
        _frames.minByOrNull { kotlin.math.abs(it.timestamp - timeMs) }

    fun getFramesBetween(startMs: Long, endMs: Long): List<LrecFrame> =
        _frames.filter { it.timestamp in startMs..endMs }

    // ── مساعدات ───────────────────────────────────────────────────
    private fun readU16LE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    private fun extractNullTerminatedStrings(buf: ByteArray, start: Int, maxLen: Int): List<String> {
        val result = mutableListOf<String>()
        var s      = start
        val end    = (start + maxLen).coerceAtMost(buf.size)
        for (i in start until end) {
            if (buf[i] == 0.toByte()) {
                if (i > s) {
                    val str = String(buf, s, i - s, Charsets.UTF_8)
                    if (str.isNotBlank() && str.all { it.code in 32..126 || it.code > 160 })
                        result.add(str)
                }
                s = i + 1
            }
        }
        return result
    }
}

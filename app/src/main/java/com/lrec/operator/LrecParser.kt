package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.RandomAccessFile

/**
 * ══════════════════════════════════════════════════════════════════
 *  LrecParser — محلّل ملفات .lrec
 *
 *  الصيغة: Inter-Tel Collaboration Client 2.0 (Build 4.2.7.0)
 *  الشركة: Linktivity / Inter-Tel Delaware Inc. © 2007
 *
 *  بنية الملف (مُستخرجة بالتحليل العكسي):
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  8 bytes  : Magic header (أصفار)                             │
 *  │  كتل متكررة: marker(2) + length(2) + data(length bytes)     │
 *  │  الـ marker دائماً: 0x10 0x04                                 │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  كتلة البيانات الأولى: metadata (sessionId, version, أبعاد) │
 *  │  كتل لاحقة: إطارات الشاشة + الصوت + المحادثة + الكاميرا     │
 *  └──────────────────────────────────────────────────────────────┘
 *
 *  أنواع الإطارات (مُستنبطة من تحليل lkVwr.dll و lkExport.dll):
 *  0x03 = VIEWPORT  — إطار مشاركة الشاشة (DIB delta)
 *  0x02 = AUDIO     — بيانات صوت PCM
 *  0x08 = VIDEO     — إطار كاميرا H.263
 *  0x0B = CHAT      — نص محادثة
 *  0x0D = META      — بيانات وصفية
 * ══════════════════════════════════════════════════════════════════
 */
class LrecParser(private val file: File) {

    // ── ثوابت البروتوكول ──────────────────────────────────────────
    companion object {
        // علامة الكتلة — تظهر قبل كل كتلة في الملف
        const val BLOCK_MARKER_0 = 0x10
        const val BLOCK_MARKER_1 = 0x04

        // أنواع الإطارات الداخلية
        const val TYPE_META     = 0x0D
        const val TYPE_VIEWPORT = 0x03
        const val TYPE_AUDIO    = 0x02
        const val TYPE_CHAT     = 0x0B
        const val TYPE_VIDEO    = 0x08

        // توقيت التشغيل (5 إطارات/ثانية وفق إعدادات التطبيق)
        const val DEFAULT_FPS       = 5
        const val MS_PER_FRAME      = 1000L / DEFAULT_FPS

        // توقيع Linktivity في رأس الملف
        const val LINKTIVITY_SIG    = "Linktivity"
        const val VERSION_PREFIX    = "V"
        const val HEADER_SCAN_BYTES = 2048
    }

    // ── نماذج البيانات ────────────────────────────────────────────

    /** البيانات الوصفية المستخرجة من رأس الملف */
    data class LrecMetadata(
        val sessionId:    String = "",
        val userId:       String = "",
        val version:      String = "V1.0.1.0",
        val serverAddr:   String = "",
        val screenWidth:  Int    = 882,
        val screenHeight: Int    = 659,
        val fps:          Int    = DEFAULT_FPS,
        val isValid:      Boolean = false
    )

    /** إطار واحد من الملف */
    data class LrecFrame(
        val fileOffset: Long,       // موضع الكتلة في الملف
        val type:       Int,        // نوع الإطار
        val dataLength: Int,        // طول البيانات
        val timestamp:  Long,       // الزمن بالميلي-ثانية من بداية التشغيل
        val rawData:    ByteArray   // البيانات الخام
    )

    /** نتيجة فك تشفير إطار الشاشة */
    data class ScreenFrameData(
        val x:      Int,            // بداية المستطيل المتغير (يسار)
        val y:      Int,            // بداية المستطيل المتغير (أعلى)
        val width:  Int,            // عرض المستطيل
        val height: Int,            // ارتفاع المستطيل
        val pixels: IntArray,       // بيانات البكسل ARGB
        val isFullFrame: Boolean    // هل هو إطار كامل أم تحديث جزئي؟
    )

    // ── الحالة الداخلية ───────────────────────────────────────────
    var metadata   = LrecMetadata()
        private set

    private val _frames = mutableListOf<LrecFrame>()
    private var _durationMs = 0L

    // لوحة الألوان (256 لون × ARGB)
    private val colorPalette = IntArray(256)
    private var hasPalette   = false

    // ══════════════════════════════════════════════════════════════
    //  الدالة الرئيسية — تحليل الملف الكامل
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
        val buf = ByteArray(scanSize)
        raf.read(buf)

        val raw = String(buf, Charsets.ISO_8859_1)

        // ابحث عن توقيع Linktivity
        val sigIdx = raw.indexOf(LINKTIVITY_SIG)
        if (sigIdx < 0) {
            // الملف ليس .lrec صحيح لكن سنحاول التشغيل بالإعدادات الافتراضية
            metadata = LrecMetadata(isValid = true)
            return
        }

        // استخرج النصوص المنتهية بصفر قبل التوقيع
        val beforeSig  = extractNullTerminatedStrings(buf, 0, sigIdx)
        // استخرج النصوص بعد التوقيع
        val afterStart = sigIdx + LINKTIVITY_SIG.length + 1
        val afterSig   = extractNullTerminatedStrings(buf, afterStart, scanSize - afterStart)

        // بحث عن عنوان الخادم في النص الكامل (TCP:port:ip:port)
        val serverAddr = raw.substringAfter("TCP:", "").substringBefore("\u0000", "")

        // من المعروف أن sessionId و userId يسبقان "Linktivity"
        val sessionId = beforeSig.getOrElse(beforeSig.size - 2) { "" }
        val userId    = beforeSig.getOrElse(beforeSig.size - 1) { "" }

        // أبعاد الشاشة ورقم الإصدار تلي "Linktivity"
        val widthStr  = afterSig.getOrElse(0) { "882" }
        val heightStr = afterSig.getOrElse(1) { "659" }
        val version   = afterSig.firstOrNull { it.startsWith(VERSION_PREFIX) } ?: "V1.0.1.0"

        metadata = LrecMetadata(
            sessionId    = sessionId,
            userId       = userId,
            version      = version,
            serverAddr   = serverAddr,
            screenWidth  = widthStr.toIntOrNull()?.coerceIn(100, 3840)  ?: 882,
            screenHeight = heightStr.toIntOrNull()?.coerceIn(100, 2160) ?: 659,
            fps          = DEFAULT_FPS,
            isValid      = true
        )
    }

    // ── مسح جميع الكتل ───────────────────────────────────────────
    private fun scanAllFrames(raf: RandomAccessFile) {
        var pos         = 8L          // تخطّى 8 bytes المبدئية
        var tsMs        = 0L
        val fileSize    = raf.length()

        while (pos <= fileSize - 4) {
            raf.seek(pos)

            val b0 = raf.read()
            val b1 = raf.read()

            if (b0 == BLOCK_MARKER_0 && b1 == BLOCK_MARKER_1) {
                // قرأنا العلامة — الآن اقرأ الطول (little-endian 2 bytes)
                val lo  = raf.read()
                val hi  = raf.read()
                if (lo < 0 || hi < 0) break

                val dataLen = (hi shl 8) or lo

                if (dataLen in 1..500_000 && pos + 4 + dataLen <= fileSize) {
                    val data = ByteArray(dataLen)
                    val read = raf.read(data)

                    if (read == dataLen) {
                        val frameType = determineFrameType(data, pos)

                        // استخراج لوحة الألوان إذا وُجدت
                        if (!hasPalette && dataLen >= 1024 && looksLikePalette(data)) {
                            extractPalette(data)
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

                        // تقدم الزمن لكل إطار منطقي للشاشة
                        if (frameType == TYPE_VIEWPORT || frameType == TYPE_META) {
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

    // ── تحديد نوع الإطار من البيانات ─────────────────────────────
    private fun determineFrameType(data: ByteArray, offset: Long): Int {
        if (data.isEmpty()) return TYPE_META

        // البيانات الوصفية تحتوي على "Linktivity" أو عناوين TCP
        val preview = String(data.take(64).toByteArray(), Charsets.ISO_8859_1)
        if (preview.contains(LINKTIVITY_SIG) || preview.contains("TCP:")) return TYPE_META

        // إطارات الكاميرا H.263 تبدأ بـ 0x00 0x00 أو bytes خاصة بـ H263
        if (data.size > 4 &&
            data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            (data[2].toInt() and 0x80) != 0) return TYPE_VIDEO

        // إطارات الصوت PCM: بيانات صغيرة متكررة
        if (data.size in 40..512 && offset > 5000) return TYPE_AUDIO

        // إطارات المحادثة: نص قابل للقراءة
        val textRatio = data.count { it in 0x20..0x7E || it in 0xA0..0xFF }.toFloat() / data.size
        if (textRatio > 0.75f && data.size < 500) return TYPE_CHAT

        // افتراضياً: إطار شاشة
        return TYPE_VIEWPORT
    }

    // ── فحص ما إذا كانت البيانات لوحة ألوان ─────────────────────
    private fun looksLikePalette(data: ByteArray): Boolean {
        // لوحة 256 لون = 256 × 4 bytes = 1024 bytes
        // كل إدخال: Blue, Green, Red, Reserved (Windows RGBQUAD)
        if (data.size < 1024) return false
        var validEntries = 0
        for (i in 0 until 256) {
            val b = data[i * 4].toInt() and 0xFF
            val g = data[i * 4 + 1].toInt() and 0xFF
            val r = data[i * 4 + 2].toInt() and 0xFF
            if (b in 0..255 && g in 0..255 && r in 0..255) validEntries++
        }
        return validEntries == 256
    }

    // ── استخراج لوحة الألوان ─────────────────────────────────────
    private fun extractPalette(data: ByteArray) {
        for (i in 0 until 256.coerceAtMost(data.size / 4)) {
            val b = data[i * 4].toInt() and 0xFF
            val g = data[i * 4 + 1].toInt() and 0xFF
            val r = data[i * 4 + 2].toInt() and 0xFF
            colorPalette[i] = Color.rgb(r, g, b)
        }
        hasPalette = true
    }

    // ── تقدير مدة التشغيل ────────────────────────────────────────
    private fun estimateDuration(): Long {
        if (_frames.isEmpty()) return 0L
        return _frames.last().timestamp + MS_PER_FRAME
    }

    // ══════════════════════════════════════════════════════════════
    //  فك تشفير إطار الشاشة إلى بيانات بكسل
    // ══════════════════════════════════════════════════════════════
    fun decodeScreenFrame(frame: LrecFrame): ScreenFrameData? {
        val data = frame.rawData
        if (data.size < 8) return null

        return try {
            // هيكل إطار DIB المتغير:
            // [0-1] X البداية (LE)
            // [2-3] Y البداية (LE)
            // [4-5] X النهاية (LE)
            // [6-7] Y النهاية (LE)
            // [8..] بيانات البكسل
            val x1 = readUInt16LE(data, 0)
            val y1 = readUInt16LE(data, 2)
            val x2 = readUInt16LE(data, 4)
            val y2 = readUInt16LE(data, 6)

            val w = (x2 - x1).coerceIn(1, metadata.screenWidth)
            val h = (y2 - y1).coerceIn(1, metadata.screenHeight)

            if (w <= 0 || h <= 0 || w > 3840 || h > 2160) return null

            val pixelOffset = 8
            val pixelCount  = w * h
            val pixels      = IntArray(pixelCount)

            // فك تشفير البكسل حسب العمق اللوني:
            // 8-bit indexed → نستخدم لوحة الألوان
            // 16-bit RGB565 → نحوّل مباشرة
            val bytesPerPixel = when {
                hasPalette && data.size >= pixelOffset + pixelCount -> 1
                data.size >= pixelOffset + pixelCount * 2           -> 2
                else                                                 -> 1
            }

            for (i in 0 until pixelCount) {
                val bytePos = pixelOffset + i * bytesPerPixel
                if (bytePos >= data.size) break

                pixels[i] = when (bytesPerPixel) {
                    2 -> {
                        // RGB565 → ARGB8888
                        val lo = data[bytePos].toInt()     and 0xFF
                        val hi = data[bytePos + 1].toInt() and 0xFF
                        val rgb565 = (hi shl 8) or lo
                        rgb565ToArgb(rgb565)
                    }
                    else -> {
                        // 8-bit indexed
                        val idx = data[bytePos].toInt() and 0xFF
                        if (hasPalette) colorPalette[idx]
                        else {
                            // بدون لوحة: تدرج رمادي
                            val v = idx
                            Color.rgb(v, v, v)
                        }
                    }
                }
            }

            val isFullFrame = (x1 == 0 && y1 == 0 &&
                               w >= metadata.screenWidth - 10 &&
                               h >= metadata.screenHeight - 10)

            ScreenFrameData(
                x            = x1,
                y            = y1,
                width        = w,
                height       = h,
                pixels       = pixels,
                isFullFrame  = isFullFrame
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── تحويل RGB565 إلى ARGB8888 ─────────────────────────────────
    private fun rgb565ToArgb(rgb565: Int): Int {
        val r5 = (rgb565 shr 11) and 0x1F
        val g6 = (rgb565 shr  5) and 0x3F
        val b5 =  rgb565         and 0x1F
        // توسيع من 5/6 bits إلى 8 bits
        val r8 = (r5 shl 3) or (r5 shr 2)
        val g8 = (g6 shl 2) or (g6 shr 4)
        val b8 = (b5 shl 3) or (b5 shr 2)
        return Color.rgb(r8, g8, b8)
    }

    // ══════════════════════════════════════════════════════════════
    //  تطبيق إطار الشاشة على Bitmap الحالي
    // ══════════════════════════════════════════════════════════════
    fun applyFrameToBitmap(bitmap: Bitmap, frameData: ScreenFrameData) {
        if (frameData.isFullFrame) {
            // إعادة رسم الكل
            bitmap.setPixels(
                frameData.pixels,
                0, frameData.width,
                0, 0,
                frameData.width.coerceAtMost(bitmap.width),
                frameData.height.coerceAtMost(bitmap.height)
            )
        } else {
            // تطبيق التغييرات الجزئية فقط
            val safeX = frameData.x.coerceIn(0, bitmap.width  - 1)
            val safeY = frameData.y.coerceIn(0, bitmap.height - 1)
            val safeW = frameData.width.coerceAtMost(bitmap.width  - safeX)
            val safeH = frameData.height.coerceAtMost(bitmap.height - safeY)

            if (safeW > 0 && safeH > 0) {
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
    //  استخراج نص المحادثة من إطار
    // ══════════════════════════════════════════════════════════════
    fun decodeChatFrame(frame: LrecFrame): String? {
        if (frame.rawData.size < 5) return null
        return try {
            // تجاوز 4 bytes header ثم اقرأ UTF-8
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
    fun getAudioFrames(): List<LrecFrame> = _frames.filter { it.type == TYPE_AUDIO }
    fun getChatFrames():  List<LrecFrame> = _frames.filter { it.type == TYPE_CHAT }
    fun getDurationMs():  Long            = _durationMs
    fun getTotalFrames(): Int             = _frames.size

    /** إيجاد الإطار الأقرب لزمن معيّن */
    fun getFrameAtTime(timeMs: Long): LrecFrame? {
        return _frames.minByOrNull { kotlin.math.abs(it.timestamp - timeMs) }
    }

    /** الإطارات بين زمنين */
    fun getFramesBetween(startMs: Long, endMs: Long): List<LrecFrame> {
        return _frames.filter { it.timestamp in startMs..endMs }
    }

    // ── مساعدات ───────────────────────────────────────────────────
    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    private fun extractNullTerminatedStrings(
        buf: ByteArray, startIdx: Int, maxLen: Int
    ): List<String> {
        val result = mutableListOf<String>()
        var start  = startIdx
        val end    = (startIdx + maxLen).coerceAtMost(buf.size)

        for (i in startIdx until end) {
            if (buf[i] == 0.toByte()) {
                if (i > start) {
                    val s = String(buf, start, i - start, Charsets.UTF_8)
                    if (s.isNotBlank() && s.all { it.code in 32..126 || it.code > 160 }) {
                        result.add(s)
                    }
                }
                start = i + 1
            }
        }
        return result
    }
}

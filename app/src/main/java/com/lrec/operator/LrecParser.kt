package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * ══════════════════════════════════════════════════════════════════════
 *  LrecParser — محلّل ملفات .lrec  (النسخة المُحقَّقة بالتحليل الكامل)
 *
 *  الصيغة: Inter-Tel Collaboration Client 2.0 (Build 4.2.7.0)
 *  © 2007 Linktivity / Inter-Tel Delaware Inc.
 *
 *  ──────────────────────────────────────────────────────────────────────
 *  بنية الملف:
 *    8 bytes رأس (أصفار)
 *    كتل متكررة:
 *      marker[0]=0x10, marker[1]=0x04
 *      length: LE U16 (2 bytes)
 *      data[length]
 *
 *  أنواع الكتل (byte[1] من data):
 *    0x08 = رأس الملف / Metadata الرئيسي
 *    0x03 = بيانات الشاشة  ← النوع الرئيسي
 *    0x02 = حزم TCP مشفرة (صوت + شبكة — مشفر بـ CastEncrypt)
 *    0x01 = Keyboard Chat (مشفر بـ CastEncrypt)
 *
 *  التفاصيل الدقيقة لكتل 0x03:
 *    إذا لم تحتوِ zlib (bd[12] != 0x78):
 *      إذا len == 44   → Metadata (أبعاد الشاشة)
 *      إذا len == 1036 → PALETTE (256 لون RGBQUAD بدءاً من bd[12])
 *    إذا احتوت zlib (bd[12] == 0x78):
 *      إذا bd[6] == 0x02 → Full Frame (الشاشة كاملة)
 *      إذا bd[6] == 0x01 → Delta Frame (تحديث جزئي)
 *
 *  بنية Full Frame (بعد فك zlib):
 *    [0:9]   = أصفار
 *    [9:11]  = Width  (LE U16) — ملاحظة: offset فردي!
 *    [11:13] = أصفار
 *    [13:15] = Height (LE U16) — ملاحظة: offset فردي!
 *    [15:21] = header إضافي
 *    [21 : 21+W*H] = pixel indices (8-bit، indexed في colorPalette)
 *
 *  بنية Delta Frame (بعد فك zlib):
 *    [0:4]   = x * 256  (LE U32)
 *    [4:8]   = y * 256  (LE U32)
 *    [8:12]  = w * 256  (LE U32)
 *    [12:16] = h * 256  (LE U32)
 *    [16:20] = n * 256  (LE U32) حيث n = w * h
 *    [20]    = zero padding
 *    [21 : 21+w*h] = pixel indices (8-bit)
 *
 *  الأبعاد الحقيقية لهذا الملف: 1187 × 834 pixels
 *  الصوت والمحادثة: مشفران بـ CastEncrypt — لا يمكن فكهما
 * ══════════════════════════════════════════════════════════════════════
 */
class LrecParser(private val file: File) {

    companion object {
        const val BLOCK_MARKER_0 = 0x10
        const val BLOCK_MARKER_1 = 0x04

        // أنواع الكتل الخارجية (byte[1])
        const val TYPE_VIEWPORT = 0x03
        const val TYPE_NETWORK  = 0x02
        const val TYPE_CHAT_RAW = 0x01
        const val TYPE_HEADER   = 0x08

        // أنواع داخلية للـ parser
        const val SUBTYPE_METADATA  = 0
        const val SUBTYPE_PALETTE   = 1
        const val SUBTYPE_FULLFRAME = 2
        const val SUBTYPE_DELTA     = 3

        const val DEFAULT_FPS   = 5
        const val MS_PER_FRAME  = 1000L / DEFAULT_FPS

        // الأبعاد الحقيقية للشاشة (مُكتشَفة من التحليل العكسي الكامل)
        const val REAL_SCREEN_WIDTH  = 1187
        const val REAL_SCREEN_HEIGHT = 834

        const val LINKTIVITY_SIG = "Linktivity"

        // ──────────────────────────────────────────
        // offsets ثابتة لفك تشفير الإطارات
        // ──────────────────────────────────────────
        private const val ZLIB_OFFSET        = 12   // zlib يبدأ عند byte[12] في data
        private const val FULL_WIDTH_OFFSET  = 9    // Width  عند decompressed[9]  (LE U16)
        private const val FULL_HEIGHT_OFFSET = 13   // Height عند decompressed[13] (LE U16)
        private const val PIXEL_DATA_OFFSET  = 21   // بيانات البكسل تبدأ عند decompressed[21]
    }

    // ── نماذج البيانات ────────────────────────────────────────────────

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
        val type:       Int,       // SUBTYPE_FULLFRAME أو SUBTYPE_DELTA
        val dataLength: Int,
        val timestamp:  Long,      // ميلي-ثانية من بداية التسجيل
        val rawData:    ByteArray  // البيانات الخام للكتلة (لم تُفك ضغطها بعد)
    )

    data class ScreenFrameData(
        val x:           Int,
        val y:           Int,
        val width:       Int,
        val height:      Int,
        val pixels:      IntArray, // ARGB لكل بكسل
        val isFullFrame: Boolean
    )

    // ── الحالة الداخلية ──────────────────────────────────────────────
    var metadata = LrecMetadata()
        private set

    private val _frames     = mutableListOf<LrecFrame>()
    private var _durationMs = 0L

    // ── ✅ عدّادات الكتل المشفّرة (صوت + شات) ──────────────────────
    var audioBlockCount: Int = 0
        private set

    var chatBlockCount: Int = 0
        private set

    val hasAudioBlocks: Boolean get() = audioBlockCount > 0
    val hasChatBlocks:  Boolean get() = chatBlockCount  > 0

    // لوحة الألوان (256 لون - ARGB)
    // مُهيَّأة بالرمادي الافتراضي حتى نقرأ اللوحة الحقيقية
    private val colorPalette = IntArray(256) { i -> Color.rgb(i, i, i) }
    private var paletteLoaded = false

    // ══════════════════════════════════════════════════════════════════
    //  الدالة الرئيسية — تحليل الملف كاملاً
    // ══════════════════════════════════════════════════════════════════
    fun parse(): Boolean {
        if (!file.exists() || file.length() < 64) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                parseHeader(raf)
                scanAllFrames(raf)
                _durationMs = if (_frames.isNotEmpty())
                    _frames.last().timestamp + MS_PER_FRAME else 0L
                metadata.isValid && _frames.isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── قراءة رأس الملف (أول 2048 byte) ─────────────────────────────
    private fun parseHeader(raf: RandomAccessFile) {
        raf.seek(0)
        val scanSize = 2048.coerceAtMost(file.length().toInt())
        val buf = ByteArray(scanSize)
        raf.read(buf)
        val raw = String(buf, Charsets.ISO_8859_1)

        val serverAddr = raw.substringAfter("TCP:", "").substringBefore("\u0000", "")
        val version    = Regex("V\\d+\\.\\d+\\.\\d+\\.\\d+").find(raw)?.value ?: "V1.0.1.0"
        val sigIdx     = raw.indexOf(LINKTIVITY_SIG)
        val sessionId  = if (sigIdx > 0)
            extractNullStrings(buf, 0, sigIdx).lastOrNull() ?: "" else ""

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

    // ── مسح جميع الكتل ───────────────────────────────────────────────
    private fun scanAllFrames(raf: RandomAccessFile) {
        var pos      = 8L
        var tsMs     = 0L
        val fileSize = raf.length()

        while (pos <= fileSize - 4) {
            raf.seek(pos)
            val b0 = raf.read()
            val b1 = raf.read()

            if (b0 == BLOCK_MARKER_0 && b1 == BLOCK_MARKER_1) {
                val lo = raf.read()
                val hi = raf.read()
                if (lo < 0 || hi < 0) break

                val dataLen = (hi shl 8) or lo
                if (dataLen in 1..500_000 && pos + 4 + dataLen <= fileSize) {
                    val blockData = ByteArray(dataLen)
                    val read = raf.read(blockData)

                    if (read == dataLen && blockData.size >= 8) {
                        val outerType = blockData[1].toInt() and 0xFF

                        when (outerType) {
                            TYPE_VIEWPORT -> {
                                val subtype = classifyViewportBlock(blockData)
                                when (subtype) {
                                    SUBTYPE_PALETTE -> {
                                        extractPalette(blockData)
                                    }
                                    SUBTYPE_FULLFRAME, SUBTYPE_DELTA -> {
                                        _frames.add(
                                            LrecFrame(
                                                fileOffset = pos,
                                                type       = subtype,
                                                dataLength = dataLen,
                                                timestamp  = tsMs,
                                                rawData    = blockData
                                            )
                                        )
                                        tsMs += MS_PER_FRAME
                                    }
                                }
                            }
                            // ✅ عدّ كتل الصوت المشفّرة
                            TYPE_NETWORK -> {
                                audioBlockCount++
                            }
                            // ✅ عدّ كتل الشات المشفّرة
                            TYPE_CHAT_RAW -> {
                                chatBlockCount++
                            }
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

    /**
     * تصنيف كتلة TYPE_VIEWPORT:
     *   - لا zlib + طول صغير  -> metadata أو palette
     *   - zlib + bd[6]=0x02   -> full frame
     *   - zlib + bd[6]=0x01   -> delta frame
     */
    private fun classifyViewportBlock(blockData: ByteArray): Int {
        if (blockData.size < 13) return SUBTYPE_METADATA

        val b12 = blockData[12].toInt() and 0xFF
        val b13 = if (blockData.size > 13) blockData[13].toInt() and 0xFF else 0
        val hasZlib = (b12 == 0x78) && (b13 in listOf(0x01, 0x5E, 0x9C, 0xDA))

        return if (!hasZlib) {
            when {
                blockData.size == 44   -> SUBTYPE_METADATA
                blockData.size == 1036 -> SUBTYPE_PALETTE
                else                   -> SUBTYPE_METADATA
            }
        } else {
            val frameFlag = blockData[6].toInt() and 0xFF
            if (frameFlag == 0x02) SUBTYPE_FULLFRAME else SUBTYPE_DELTA
        }
    }

    // ── استخراج لوحة الألوان من كتلة PALETTE ────────────────────────
    private fun extractPalette(blockData: ByteArray) {
        if (blockData.size < 12 + 1024) return
        val start = 12
        for (i in 0 until 256) {
            val blue  = blockData[start + i * 4].toInt()     and 0xFF
            val green = blockData[start + i * 4 + 1].toInt() and 0xFF
            val red   = blockData[start + i * 4 + 2].toInt() and 0xFF
            colorPalette[i] = Color.rgb(red, green, blue)
        }
        paletteLoaded = true
    }

    // ══════════════════════════════════════════════════════════════════
    //  فك ضغط كتلة بـ zlib (مع دعم incomplete streams)
    // ══════════════════════════════════════════════════════════════════
    private fun zlibDecompress(blockData: ByteArray): ByteArray? {
        if (blockData.size <= ZLIB_OFFSET + 2) return null
        val b12 = blockData[ZLIB_OFFSET].toInt() and 0xFF
        val b13 = blockData[ZLIB_OFFSET + 1].toInt() and 0xFF
        val hasZlib = (b12 == 0x78) && (b13 in listOf(0x01, 0x5E, 0x9C, 0xDA))
        if (!hasZlib) return null

        val inflater = Inflater()
        inflater.setInput(blockData, ZLIB_OFFSET, blockData.size - ZLIB_OFFSET)

        val buf = ByteArray(1_200_000)
        return try {
            var total = 0
            while (!inflater.finished() && !inflater.needsInput() && total < buf.size) {
                val n = inflater.inflate(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            inflater.end()
            if (total > 0) buf.copyOf(total) else null
        } catch (e: DataFormatException) {
            inflater.end()
            // حاول raw deflate كبديل
            val inf2 = Inflater(true)
            inf2.setInput(blockData, ZLIB_OFFSET + 2, blockData.size - ZLIB_OFFSET - 2)
            try {
                var total = 0
                while (!inf2.finished() && !inf2.needsInput() && total < buf.size) {
                    val n = inf2.inflate(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                inf2.end()
                if (total > 0) buf.copyOf(total) else null
            } catch (e2: Exception) {
                inf2.end()
                null
            }
        } catch (e: Exception) {
            inflater.end()
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  فك تشفير إطار شاشة -> بيانات بكسل ARGB
    // ══════════════════════════════════════════════════════════════════
    fun decodeScreenFrame(frame: LrecFrame): ScreenFrameData? {
        val decompressed = zlibDecompress(frame.rawData) ?: return null
        if (decompressed.size <= PIXEL_DATA_OFFSET) return null

        return try {
            when (frame.type) {
                SUBTYPE_FULLFRAME -> decodeFullFrame(decompressed)
                SUBTYPE_DELTA     -> decodeDeltaFrame(decompressed)
                else              -> null
            }
        } catch (e: Exception) { null }
    }

    /**
     * فك تشفير الإطار الكامل:
     *   - decompressed[9:11]  = Width  (LE U16)
     *   - decompressed[13:15] = Height (LE U16)
     *   - decompressed[21:]   = pixel indices (8-bit)
     */
    private fun decodeFullFrame(raw: ByteArray): ScreenFrameData? {
        if (raw.size < PIXEL_DATA_OFFSET + 100) return null

        val w = readU16LE(raw, FULL_WIDTH_OFFSET).let {
            if (it in 100..3840) it else REAL_SCREEN_WIDTH
        }
        val h = readU16LE(raw, FULL_HEIGHT_OFFSET).let {
            if (it in 100..2160) it else REAL_SCREEN_HEIGHT
        }

        val pixelCount = w * h
        if (raw.size < PIXEL_DATA_OFFSET + pixelCount) return null

        val pixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val idx = raw[PIXEL_DATA_OFFSET + i].toInt() and 0xFF
            pixels[i] = colorPalette[idx]
        }

        return ScreenFrameData(
            x           = 0,
            y           = 0,
            width       = w,
            height      = h,
            pixels      = pixels,
            isFullFrame = true
        )
    }

    /**
     * فك تشفير الإطار الجزئي (delta):
     *   Header = 5 x U32LE، كل قيمة = القيمة الحقيقية * 256
     *   [0:4]  = x * 256
     *   [4:8]  = y * 256
     *   [8:12] = w * 256
     *   [12:16]= h * 256
     *   [16:20]= n * 256  (n = w * h)
     *   [20]   = zero padding
     *   [21:]  = pixel indices
     */
    private fun decodeDeltaFrame(raw: ByteArray): ScreenFrameData? {
        if (raw.size < PIXEL_DATA_OFFSET + 1) return null

        val x = (readU32LE(raw, 0)  shr 8).toInt()
        val y = (readU32LE(raw, 4)  shr 8).toInt()
        val w = (readU32LE(raw, 8)  shr 8).toInt()
        val h = (readU32LE(raw, 12) shr 8).toInt()

        if (w <= 0 || h <= 0 || w > REAL_SCREEN_WIDTH || h > REAL_SCREEN_HEIGHT) return null
        if (x < 0 || y < 0 || x + w > REAL_SCREEN_WIDTH || y + h > REAL_SCREEN_HEIGHT) return null

        val pixelCount = w * h
        if (raw.size < PIXEL_DATA_OFFSET + pixelCount) return null

        val pixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val idx = raw[PIXEL_DATA_OFFSET + i].toInt() and 0xFF
            pixels[i] = colorPalette[idx]
        }

        return ScreenFrameData(
            x           = x,
            y           = y,
            width       = w,
            height      = h,
            pixels      = pixels,
            isFullFrame = false
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  تطبيق إطار على Bitmap الحالي
    // ══════════════════════════════════════════════════════════════════
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
            val safeW = frameData.width .coerceAtMost(bitmap.width  - safeX)
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

    // ══════════════════════════════════════════════════════════════════
    //  واجهة استرجاع البيانات
    // ══════════════════════════════════════════════════════════════════
    fun getAllFrames()    : List<LrecFrame> = _frames.toList()
    fun getScreenFrames(): List<LrecFrame>  = _frames.toList()
    fun getAudioFrames() : List<LrecFrame>  = emptyList()  // مشفّر — غير مدعوم
    fun getChatFrames()  : List<LrecFrame>  = emptyList()  // مشفّر — غير مدعوم
    fun getDurationMs()  : Long             = _durationMs
    fun getTotalFrames() : Int              = _frames.size
    fun isPaletteLoaded(): Boolean          = paletteLoaded

    fun getFrameAtTime(timeMs: Long): LrecFrame? =
        _frames.minByOrNull { kotlin.math.abs(it.timestamp - timeMs) }

    fun getFramesBetween(startMs: Long, endMs: Long): List<LrecFrame> =
        _frames.filter { it.timestamp in startMs..endMs }

    // للتوافق مع الكود القديم
    fun decodeChatFrame(frame: LrecFrame): String? = null

    // ── مساعدات ──────────────────────────────────────────────────────
    private fun readU16LE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return  (data[offset].toLong()     and 0xFF) or
               ((data[offset+1].toLong()   and 0xFF) shl  8) or
               ((data[offset+2].toLong()   and 0xFF) shl 16) or
               ((data[offset+3].toLong()   and 0xFF) shl 24)
    }

    private fun extractNullStrings(buf: ByteArray, start: Int, maxLen: Int): List<String> {
        val result = mutableListOf<String>()
        var s = start
        val end = (start + maxLen).coerceAtMost(buf.size)
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

package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class LrecParser(private val file: File) {

    companion object {
        private const val TAG = "LrecParser"

        const val BLOCK_MARKER_0 = 0x10
        const val BLOCK_MARKER_1 = 0x04

        const val TYPE_VIEWPORT  = 0x03
        const val TYPE_NETWORK   = 0x02
        const val TYPE_CHAT_RAW  = 0x01
        const val TYPE_HEADER    = 0x08

        const val SUBTYPE_METADATA  = 0
        const val SUBTYPE_PALETTE   = 1
        const val SUBTYPE_FULLFRAME = 2
        const val SUBTYPE_DELTA     = 3

        const val DEFAULT_FPS  = 5
        const val MS_PER_FRAME = 1000L / DEFAULT_FPS

        const val FALLBACK_WIDTH  = 1024
        const val FALLBACK_HEIGHT = 768

        const val LINKTIVITY_SIG = "Linktivity"

        private const val PIXEL_DATA_OFFSET  = 21
        private const val DIM_SEARCH_START   = 4
        private const val DIM_SEARCH_END     = 20
    }

    // ══════════════════════════════════════════════════════════════════
    //  نماذج البيانات
    // ══════════════════════════════════════════════════════════════════

    data class LrecMetadata(
        val sessionId:    String  = "",
        val version:      String  = "V1.0.1.0",
        val serverAddr:   String  = "",
        val screenWidth:  Int     = FALLBACK_WIDTH,
        val screenHeight: Int     = FALLBACK_HEIGHT,
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

    data class ChatEntry(
        val timestampMs: Long,
        val sender:      String,
        val message:     String,
        val isDecoded:   Boolean = true
    ) {
        val formattedTime: String get() {
            val sec = timestampMs / 1000
            val m   = (sec % 3600) / 60
            val s   = sec % 60
            return String.format("%02d:%02d", m, s)
        }
    }

    data class AudioBlock(
        val timestampMs: Long,
        val rawData:     ByteArray,
        val dataOffset:  Int = 8,
        val sampleRate:  Int = 8000,
        val channels:    Int = 1
    ) {
        val audioData: ByteArray get() {
            val start = dataOffset.coerceAtMost(rawData.size - 1)
            return rawData.copyOfRange(start, rawData.size)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  الحالة الداخلية
    // ══════════════════════════════════════════════════════════════════

    var metadata = LrecMetadata()
        private set

    private val _frames      = mutableListOf<LrecFrame>()
    private val _chatEntries = mutableListOf<ChatEntry>()
    private val _audioBlocks = mutableListOf<AudioBlock>()
    private var _durationMs  = 0L

    private var _realWidth  = 0
    private var _realHeight = 0

    var audioBlockCount: Int = 0
        private set
    var chatBlockCount: Int = 0
        private set

    val hasAudioBlocks:  Boolean get() = audioBlockCount > 0
    val hasChatBlocks:   Boolean get() = chatBlockCount  > 0
    val hasChatContent:  Boolean get() = _chatEntries.isNotEmpty()
    val hasDecodedAudio: Boolean get() = _audioBlocks.isNotEmpty()

    private val colorPalette     = IntArray(256) { i -> Color.rgb(i, i, i) }
    private var paletteLoaded    = false
    private val decompressBuffer = ByteArray(4_000_000)

    // ══════════════════════════════════════════════════════════════════
    //  الدالة الرئيسية
    // ══════════════════════════════════════════════════════════════════

    fun parse(): Boolean {
        if (!file.exists() || file.length() < 64) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                parseHeader(raf)
                scanAllFrames(raf)
                _durationMs = if (_frames.isNotEmpty())
                    _frames.last().timestamp + MS_PER_FRAME else 0L

                if (_realWidth > 0 && _realHeight > 0) {
                    metadata = metadata.copy(
                        screenWidth  = _realWidth,
                        screenHeight = _realHeight
                    )
                }

                Log.d(TAG, "الأبعاد: ${metadata.screenWidth}×${metadata.screenHeight}")
                Log.d(TAG, "إطارات: ${_frames.size} | صوت: ${_audioBlocks.size} | دردشة: ${_chatEntries.size}")

                metadata.isValid && _frames.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في التحليل", e)
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  قراءة رأس الملف
    // ══════════════════════════════════════════════════════════════════

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
            screenWidth  = FALLBACK_WIDTH,
            screenHeight = FALLBACK_HEIGHT,
            fps          = DEFAULT_FPS,
            isValid      = true
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  مسح جميع الكتل
    // ══════════════════════════════════════════════════════════════════

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
                                    SUBTYPE_PALETTE -> extractPalette(blockData)
                                    SUBTYPE_FULLFRAME -> {
                                        if (_realWidth == 0) detectDimensions(blockData)
                                        _frames.add(LrecFrame(pos, subtype, dataLen, tsMs, blockData))
                                        tsMs += MS_PER_FRAME
                                    }
                                    SUBTYPE_DELTA -> {
                                        _frames.add(LrecFrame(pos, subtype, dataLen, tsMs, blockData))
                                        tsMs += MS_PER_FRAME
                                    }
                                }
                            }
                            TYPE_NETWORK -> {
                                audioBlockCount++
                                extractAudioBlock(blockData, tsMs)?.let { _audioBlocks.add(it) }
                            }
                            TYPE_CHAT_RAW -> {
                                chatBlockCount++
                                extractChatEntry(blockData, tsMs)?.let { _chatEntries.add(it) }
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

    // ══════════════════════════════════════════════════════════════════
    //  المسح الديناميكي عن magic bytes لـ zlib
    // ══════════════════════════════════════════════════════════════════

    private fun findZlibOffset(blockData: ByteArray): Int {
        val validCmf = setOf(0x01, 0x5E, 0x9C, 0xDA)
        for (i in 2 until blockData.size - 1) {
            val b0 = blockData[i].toInt() and 0xFF
            val b1 = blockData[i + 1].toInt() and 0xFF
            if (b0 == 0x78 && b1 in validCmf) {
                return i
            }
        }
        return -1
    }

    private fun zlibDecompress(blockData: ByteArray): ByteArray? {
        val zlibStart = findZlibOffset(blockData)
        if (zlibStart < 0) return null

        val inflater = Inflater()
        inflater.setInput(blockData, zlibStart, blockData.size - zlibStart)
        return try {
            var total = 0
            while (!inflater.finished() && !inflater.needsInput()
                && total < decompressBuffer.size) {
                val n = inflater.inflate(decompressBuffer, total, decompressBuffer.size - total)
                if (n <= 0) break
                total += n
            }
            inflater.end()
            if (total > 0) decompressBuffer.copyOf(total) else null
        } catch (e: DataFormatException) {
            inflater.end()
            if (zlibStart + 2 < blockData.size) {
                val inf2 = Inflater(true)
                inf2.setInput(blockData, zlibStart + 2, blockData.size - zlibStart - 2)
                try {
                    var total = 0
                    while (!inf2.finished() && !inf2.needsInput()
                        && total < decompressBuffer.size) {
                        val n = inf2.inflate(decompressBuffer, total, decompressBuffer.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    inf2.end()
                    if (total > 0) decompressBuffer.copyOf(total) else null
                } catch (e2: Exception) { inf2.end(); null }
            } else null
        } catch (e: Exception) { inflater.end(); null }
    }

    // ══════════════════════════════════════════════════════════════════
    //  اكتشاف الأبعاد بمطابقة حجم البيانات
    // ══════════════════════════════════════════════════════════════════

    private fun detectDimensions(blockData: ByteArray) {
        val decompressed = zlibDecompress(blockData) ?: return
        if (decompressed.size <= PIXEL_DATA_OFFSET) return

        val available = decompressed.size - PIXEL_DATA_OFFSET

        for (wOff in DIM_SEARCH_START until DIM_SEARCH_END) {
            for (hOff in (wOff + 2)..(wOff + 8)) {
                if (hOff + 1 >= decompressed.size) continue
                val w = readU16LE(decompressed, wOff)
                val h = readU16LE(decompressed, hOff)
                if (w !in 200..3840 || h !in 150..2160) continue
                val expected = w * h
                if (expected == available ||
                    (expected in available * 95 / 100..available * 105 / 100)) {
                    _realWidth  = w
                    _realHeight = h
                    Log.d(TAG, "✅ أبعاد: ${w}×${h}")
                    return
                }
            }
        }

        // Fallback
        val w = readU16LE(decompressed, 9)
        val h = readU16LE(decompressed, 13)
        if (w in 200..3840 && h in 150..2160) {
            _realWidth  = w
            _realHeight = h
            Log.d(TAG, "✅ أبعاد fallback: ${w}×${h}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  تصنيف كتل الشاشة
    // ══════════════════════════════════════════════════════════════════

    private fun classifyViewportBlock(blockData: ByteArray): Int {
        if (blockData.size < 8) return SUBTYPE_METADATA
        val zlibOff = findZlibOffset(blockData)
        if (zlibOff < 0) {
            return when (blockData.size) {
                44   -> SUBTYPE_METADATA
                1036 -> SUBTYPE_PALETTE
                else -> SUBTYPE_METADATA
            }
        }
        val frameFlag = blockData[6].toInt() and 0xFF
        return if (frameFlag == 0x02) SUBTYPE_FULLFRAME else SUBTYPE_DELTA
    }

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
        Log.d(TAG, "✅ لوحة الألوان محمّلة")
    }

    // ══════════════════════════════════════════════════════════════════
    //  فك تشفير إطار الشاشة
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
        } catch (e: Exception) {
            Log.w(TAG, "خطأ في فك الإطار: ${e.message}")
            null
        }
    }

    private fun decodeFullFrame(raw: ByteArray): ScreenFrameData? {
        if (raw.size <= PIXEL_DATA_OFFSET) return null
        val available = raw.size - PIXEL_DATA_OFFSET

        var w = 0; var h = 0
        outerLoop@ for (wOff in DIM_SEARCH_START until DIM_SEARCH_END) {
            for (hOff in (wOff + 2)..(wOff + 8)) {
                if (hOff + 1 >= raw.size) continue
                val tw = readU16LE(raw, wOff)
                val th = readU16LE(raw, hOff)
                if (tw !in 200..3840 || th !in 150..2160) continue
                val expected = tw * th
                if (expected == available ||
                    (expected in available * 95 / 100..available * 105 / 100)) {
                    w = tw; h = th
                    break@outerLoop
                }
            }
        }

        if (w == 0) {
            w = readU16LE(raw, 9).let { if (it in 200..3840) it else _realWidth }
            h = readU16LE(raw, 13).let { if (it in 150..2160) it else _realHeight }
        }
        if (w == 0) w = _realWidth.takeIf { it > 0 } ?: FALLBACK_WIDTH
        if (h == 0) h = _realHeight.takeIf { it > 0 } ?: FALLBACK_HEIGHT
        if (_realWidth == 0) { _realWidth = w; _realHeight = h }

        val pixelCount = w * h
        if (raw.size < PIXEL_DATA_OFFSET + pixelCount) return null

        val pixels = IntArray(pixelCount) { i ->
            colorPalette[raw[PIXEL_DATA_OFFSET + i].toInt() and 0xFF]
        }
        return ScreenFrameData(0, 0, w, h, pixels, true)
    }

    private fun decodeDeltaFrame(raw: ByteArray): ScreenFrameData? {
        if (raw.size < PIXEL_DATA_OFFSET + 1) return null
        val canvasW = _realWidth.takeIf  { it > 0 } ?: FALLBACK_WIDTH
        val canvasH = _realHeight.takeIf { it > 0 } ?: FALLBACK_HEIGHT
        val x = (readU32LE(raw, 0)  shr 8).toInt()
        val y = (readU32LE(raw, 4)  shr 8).toInt()
        val w = (readU32LE(raw, 8)  shr 8).toInt()
        val h = (readU32LE(raw, 12) shr 8).toInt()
        if (w <= 0 || h <= 0 || w > canvasW || h > canvasH) return null
        if (x < 0 || y < 0 || x + w > canvasW || y + h > canvasH) return null
        val pixelCount = w * h
        if (raw.size < PIXEL_DATA_OFFSET + pixelCount) return null
        val pixels = IntArray(pixelCount) { i ->
            colorPalette[raw[PIXEL_DATA_OFFSET + i].toInt() and 0xFF]
        }
        return ScreenFrameData(x, y, w, h, pixels, false)
    }

    fun applyFrameToBitmap(bitmap: Bitmap, frameData: ScreenFrameData) {
        if (frameData.isFullFrame) {
            bitmap.setPixels(
                frameData.pixels, 0, frameData.width, 0, 0,
                frameData.width.coerceAtMost(bitmap.width),
                frameData.height.coerceAtMost(bitmap.height)
            )
        } else {
            val safeX = frameData.x.coerceIn(0, bitmap.width  - 1)
            val safeY = frameData.y.coerceIn(0, bitmap.height - 1)
            val safeW = frameData.width .coerceAtMost(bitmap.width  - safeX)
            val safeH = frameData.height.coerceAtMost(bitmap.height - safeY)
            if (safeW > 0 && safeH > 0 && frameData.pixels.size >= safeW * safeH) {
                bitmap.setPixels(frameData.pixels, 0, frameData.width, safeX, safeY, safeW, safeH)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  استخراج الصوت
    // ══════════════════════════════════════════════════════════════════

    private fun extractAudioBlock(blockData: ByteArray, timestampMs: Long): AudioBlock? {
        if (blockData.size < 16) return null
        val audioOffset = 8
        val audioLen    = blockData.size - audioOffset
        if (audioLen < 8) return null
        return AudioBlock(
            timestampMs = timestampMs,
            rawData     = blockData,
            dataOffset  = audioOffset,
            sampleRate  = 8000,
            channels    = 1
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  استخراج الدردشة — يدعم العربية UTF-8
    // ══════════════════════════════════════════════════════════════════

    private fun extractChatEntry(blockData: ByteArray, timestampMs: Long): ChatEntry? {
        if (blockData.size < 10) return null

        val offsets = listOf(4, 6, 8, 10, 12)

        for (offset in offsets) {
            if (offset >= blockData.size) continue
            val len = blockData.size - offset
            if (len < 3) continue

            // محاولة UTF-8 (يدعم العربية)
            try {
                val text = String(blockData, offset, len, Charsets.UTF_8).trim()
                if (isValidText(text)) {
                    Log.d(TAG, "✅ دردشة UTF-8 | $text")
                    return ChatEntry(timestampMs, parseSender(text), parseMessage(text), true)
                }
            } catch (e: Exception) { }

            // محاولة UTF-16LE
            if (len >= 4 && len % 2 == 0) {
                try {
                    val text = String(blockData, offset, len, Charsets.UTF_16LE).trim()
                    if (isValidText(text)) {
                        Log.d(TAG, "✅ دردشة UTF-16LE | $text")
                        return ChatEntry(timestampMs, parseSender(text), parseMessage(text), true)
                    }
                } catch (e: Exception) { }
            }
        }

        // محاولة Base64
        tryBase64Chat(blockData)?.let {
            return ChatEntry(timestampMs, it.first, it.second, true)
        }

        Log.d(TAG,
            "كتلة دردشة غير مفكوكة | حجم=${blockData.size} " +
            "| hex=${blockData.take(16).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")

        return null
    }

    private fun isValidText(text: String): Boolean {
        if (text.length < 3 || text.length > 1000) return false
        var validCount = 0
        for (c in text) {
            when {
                c.code in 32..126         -> validCount++
                c.code in 0x0600..0x06FF  -> validCount++ // عربية
                c.code in 0x0750..0x077F  -> validCount++ // ملحق عربي
                c.code in 0xFB50..0xFDFF  -> validCount++ // عربية مقدمة A
                c.code in 0xFE70..0xFEFF  -> validCount++ // عربية مقدمة B
                c.isWhitespace()          -> validCount++
                c.isLetterOrDigit()       -> validCount++
            }
        }
        if (validCount.toFloat() / text.length < 0.7f) return false
        if (!text.any { it.isLetter() }) return false
        return text.toSet().size >= 3
    }

    private fun parseSender(text: String): String {
        val colonIdx = text.indexOf(':')
        if (colonIdx in 1..40) {
            val candidate = text.substring(0, colonIdx).trim()
            if (candidate.isNotBlank() && candidate.length < 40 && !candidate.contains('\n'))
                return candidate
        }
        return "مشارك"
    }

    private fun parseMessage(text: String): String {
        val colonIdx = text.indexOf(':')
        return if (colonIdx in 1..40 && colonIdx + 1 < text.length)
            text.substring(colonIdx + 1).trim()
        else text.trim()
    }

    private fun tryBase64Chat(blockData: ByteArray): Pair<String, String>? {
        for (offset in listOf(4, 6, 8, 12)) {
            if (offset >= blockData.size) continue
            val remaining = blockData.size - offset
            if (remaining < 8) continue
            try {
                val rawStr = String(blockData, offset, remaining, Charsets.ISO_8859_1)
                val match  = Regex("[A-Za-z0-9+/]{12,}={0,2}").find(rawStr) ?: continue
                val decoded = android.util.Base64.decode(match.value, android.util.Base64.DEFAULT)
                if (decoded.size < 3) continue
                val text = String(decoded, Charsets.UTF_8).trim()
                if (isValidText(text)) {
                    Log.d(TAG, "✅ دردشة Base64 | $text")
                    return Pair(parseSender(text), parseMessage(text))
                }
            } catch (e: Exception) { continue }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  واجهة استرجاع البيانات
    // ══════════════════════════════════════════════════════════════════

    fun getAllFrames()    : List<LrecFrame>  = _frames.toList()
    fun getScreenFrames(): List<LrecFrame>  = _frames.toList()
    fun getAudioBlocks() : List<AudioBlock> = _audioBlocks.toList()
    fun getChatFrames()  : List<LrecFrame>  = emptyList()
    fun getDurationMs()  : Long             = _durationMs
    fun getTotalFrames() : Int              = _frames.size
    fun isPaletteLoaded(): Boolean          = paletteLoaded
    fun getChatEntries() : List<ChatEntry>  = _chatEntries.toList()

    fun getAudioBlocksFrom(timeMs: Long): List<AudioBlock> =
        _audioBlocks.filter { it.timestampMs >= timeMs }

    fun getChatEntriesUpTo(timeMs: Long): List<ChatEntry> =
        _chatEntries.filter { it.timestampMs <= timeMs }

    fun getActiveChatEntry(timeMs: Long): ChatEntry? =
        _chatEntries.lastOrNull { it.timestampMs <= timeMs }

    fun getFrameAtTime(timeMs: Long): LrecFrame? =
        _frames.minByOrNull { kotlin.math.abs(it.timestamp - timeMs) }

    fun getFramesBetween(startMs: Long, endMs: Long): List<LrecFrame> =
        _frames.filter { it.timestamp in startMs..endMs }

    fun decodeChatFrame(frame: LrecFrame): String? = null

    // ══════════════════════════════════════════════════════════════════
    //  مساعدات
    // ══════════════════════════════════════════════════════════════════

    private fun readU16LE(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return  (data[offset].toLong()   and 0xFF)         or
               ((data[offset+1].toLong() and 0xFF) shl  8) or
               ((data[offset+2].toLong() and 0xFF) shl 16) or
               ((data[offset+3].toLong() and 0xFF) shl 24)
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

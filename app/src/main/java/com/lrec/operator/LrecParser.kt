package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
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

        const val DEFAULT_FPS   = 5
        const val MS_PER_FRAME  = 1000L / DEFAULT_FPS

        const val REAL_SCREEN_WIDTH  = 1187
        const val REAL_SCREEN_HEIGHT = 834

        const val LINKTIVITY_SIG = "Linktivity"

        private const val ZLIB_OFFSET        = 12
        private const val FULL_WIDTH_OFFSET  = 9
        private const val FULL_HEIGHT_OFFSET = 13
        private const val PIXEL_DATA_OFFSET  = 21
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

    /**
     * كتلة صوتية مُستخرجة من ملف .lrec
     * تحتوي على بيانات G.711 أو PCM الخام
     */
    data class AudioBlock(
        val timestampMs: Long,       // توقيت الكتلة للمزامنة مع الفيديو
        val rawData:     ByteArray,  // البيانات الخام (تشمل الـ header)
        val dataOffset:  Int = 8,    // أين تبدأ بيانات الصوت الفعلية
        val sampleRate:  Int = 8000, // معدل التردد
        val channels:    Int = 1     // عدد القنوات
    ) {
        // البيانات الصوتية الصافية (بدون header)
        val audioData: ByteArray get() {
            val start = dataOffset.coerceAtMost(rawData.size - 1)
            return rawData.copyOfRange(start, rawData.size)
        }
    }

    // ── الحالة الداخلية ───────────────────────────────────────────────

    var metadata = LrecMetadata()
        private set

    private val _frames       = mutableListOf<LrecFrame>()
    private val _chatEntries  = mutableListOf<ChatEntry>()
    private val _audioBlocks  = mutableListOf<AudioBlock>()   // ← جديد
    private var _durationMs   = 0L

    var audioBlockCount: Int = 0
        private set
    var chatBlockCount: Int = 0
        private set

    val hasAudioBlocks:  Boolean get() = audioBlockCount > 0
    val hasChatBlocks:   Boolean get() = chatBlockCount  > 0
    val hasChatContent:  Boolean get() = _chatEntries.isNotEmpty()
    val hasDecodedAudio: Boolean get() = _audioBlocks.isNotEmpty()  // ← جديد

    private val colorPalette     = IntArray(256) { i -> Color.rgb(i, i, i) }
    private var paletteLoaded    = false
    private val decompressBuffer = ByteArray(1_500_000)

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
                metadata.isValid && _frames.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في التحليل", e)
            false
        }
    }

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
                                    SUBTYPE_FULLFRAME, SUBTYPE_DELTA -> {
                                        _frames.add(LrecFrame(
                                            fileOffset = pos,
                                            type       = subtype,
                                            dataLength = dataLen,
                                            timestamp  = tsMs,
                                            rawData    = blockData
                                        ))
                                        tsMs += MS_PER_FRAME
                                    }
                                }
                            }

                            TYPE_NETWORK -> {
                                audioBlockCount++
                                // ── استخراج الكتلة الصوتية الحقيقية ──────
                                extractAudioBlock(blockData, tsMs)?.let {
                                    _audioBlocks.add(it)
                                }
                            }

                            TYPE_CHAT_RAW -> {
                                chatBlockCount++
                                extractChatEntry(blockData, tsMs)?.let {
                                    _chatEntries.add(it)
                                }
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

        Log.d(TAG, "التحليل اكتمل: " +
              "${_frames.size} إطار فيديو, " +
              "${_audioBlocks.size} كتلة صوت, " +
              "${_chatEntries.size} رسالة دردشة")
    }

    // ══════════════════════════════════════════════════════════════════
    //  استخراج الكتلة الصوتية من TYPE_NETWORK
    // ══════════════════════════════════════════════════════════════════

    private fun extractAudioBlock(blockData: ByteArray, timestampMs: Long): AudioBlock? {
        // الحد الأدنى لحجم الكتلة الصوتية
        if (blockData.size < 12) return null

        // ── تحليل بنية كتلة الشبكة ────────────────────────────────────
        // blockData[0] = بيانات داخلية
        // blockData[1] = 0x02 (TYPE_NETWORK)
        // blockData[2] = نوع الكودك المحتمل
        // blockData[3..7] = header إضافي
        // blockData[8..] = بيانات الصوت الفعلية

        val audioDataOffset = findAudioDataOffset(blockData)
        val audioLen = blockData.size - audioDataOffset

        if (audioLen <= 0) return null

        // كشف معدل التردد من الهيدر إن وُجد
        val sampleRate = detectSampleRate(blockData)

        // كشف عدد القنوات
        val channels = detectChannels(blockData)

        return AudioBlock(
            timestampMs = timestampMs,
            rawData     = blockData,
            dataOffset  = audioDataOffset,
            sampleRate  = sampleRate,
            channels    = channels
        )
    }

    /**
     * إيجاد نقطة بداية بيانات الصوت الفعلية داخل الكتلة
     * بتجاوز الـ header الخاص بـ Inter-Tel
     */
    private fun findAudioDataOffset(blockData: ByteArray): Int {
        // الهيدر الثابت = 8 bytes في معظم الحالات
        // لكن بعض الكتل قد تحتوي على هيدر أطول
        if (blockData.size < 16) return 8

        // التحقق من وجود magic bytes للـ RIFF/WAV
        val b8  = blockData[8].toInt()  and 0xFF
        val b9  = blockData[9].toInt()  and 0xFF
        val b10 = blockData[10].toInt() and 0xFF
        val b11 = blockData[11].toInt() and 0xFF

        // إذا بدأت الكتلة بـ RIFF header (نادر لكن ممكن)
        if (b8 == 0x52 && b9 == 0x49 && b10 == 0x46 && b11 == 0x46) {
            return 44  // WAV header كامل = 44 bytes
        }

        // الحالة الافتراضية: 8 bytes header
        return 8
    }

    private fun detectSampleRate(blockData: ByteArray): Int {
        if (blockData.size < 16) return 8000

        // محاولة قراءة sample rate من الـ header (offset 4-7)
        val possibleRate = readU32LE(blockData, 4).toInt()
        return when {
            possibleRate == 8000  -> 8000
            possibleRate == 16000 -> 16000
            possibleRate == 22050 -> 22050
            possibleRate == 44100 -> 44100
            else                  -> 8000  // الافتراضي لـ G.711
        }
    }

    private fun detectChannels(blockData: ByteArray): Int {
        if (blockData.size < 10) return 1
        // byte[3] قد يشير لعدد القنوات في بعض الإصدارات
        val ch = blockData[3].toInt() and 0xFF
        return if (ch == 2) 2 else 1
    }

    // ══════════════════════════════════════════════════════════════════
    //  استخراج رسالة دردشة
    // ══════════════════════════════════════════════════════════════════

    private fun extractChatEntry(blockData: ByteArray, timestampMs: Long): ChatEntry? {
        if (blockData.size < 10) return null

        try {
            val startOffset = 8.coerceAtMost(blockData.size - 1)
            val rawStr = String(blockData, startOffset, blockData.size - startOffset,
                                Charsets.ISO_8859_1)
            val base64Regex = Regex("[A-Za-z0-9+/]{20,}={0,2}")
            val match = base64Regex.find(rawStr)
            if (match != null) {
                val decoded = Base64.decode(match.value, Base64.DEFAULT)
                val text    = String(decoded, Charsets.UTF_8).trim()
                if (isValidChatText(text)) {
                    val parts = parseChatText(text)
                    return ChatEntry(timestampMs, parts.first, parts.second, true)
                }
            }
        } catch (e: Exception) { }

        try {
            val startOffset = 8.coerceAtMost(blockData.size - 1)
            val text = String(blockData, startOffset, blockData.size - startOffset,
                              Charsets.UTF_8).trim()
            if (isValidChatText(text)) {
                val parts = parseChatText(text)
                return ChatEntry(timestampMs, parts.first, parts.second, true)
            }
        } catch (e: Exception) { }

        try {
            val readable = blockData.drop(8).map { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar() else ' '
            }.joinToString("").trim().replace(Regex("\\s{2,}"), " ")
            if (readable.length >= 5 && readable.count { it.isLetterOrDigit() } >= 4) {
                return ChatEntry(timestampMs, "مشارك", readable, true)
            }
        } catch (e: Exception) { }

        return null
    }

    private fun isValidChatText(text: String): Boolean {
        if (text.length < 2 || text.length > 2000) return false
        val validChars = text.count { c ->
            c.isLetterOrDigit() || c.isWhitespace() || c in ".,!?؟،:-()[]{}@#"
        }
        return validChars.toFloat() / text.length > 0.6f
    }

    private fun parseChatText(text: String): Pair<String, String> {
        val colonIdx = text.indexOf(':')
        return if (colonIdx in 1..30) {
            val sender  = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            if (sender.isNotBlank() && message.isNotBlank())
                Pair(sender, message)
            else
                Pair("مشارك", text)
        } else {
            Pair("مشارك", text)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  تصنيف كتل الشاشة
    // ══════════════════════════════════════════════════════════════════

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
    //  فك ضغط zlib
    // ══════════════════════════════════════════════════════════════════

    private fun zlibDecompress(blockData: ByteArray): ByteArray? {
        if (blockData.size <= ZLIB_OFFSET + 2) return null
        val b12 = blockData[ZLIB_OFFSET].toInt() and 0xFF
        val b13 = blockData[ZLIB_OFFSET + 1].toInt() and 0xFF
        val hasZlib = (b12 == 0x78) && (b13 in listOf(0x01, 0x5E, 0x9C, 0xDA))
        if (!hasZlib) return null

        val inflater = Inflater()
        inflater.setInput(blockData, ZLIB_OFFSET, blockData.size - ZLIB_OFFSET)
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
            val inf2 = Inflater(true)
            inf2.setInput(blockData, ZLIB_OFFSET + 2, blockData.size - ZLIB_OFFSET - 2)
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
        } catch (e: Exception) { inflater.end(); null }
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
        } catch (e: Exception) { null }
    }

    private fun decodeFullFrame(raw: ByteArray): ScreenFrameData? {
        if (raw.size < PIXEL_DATA_OFFSET + 100) return null
        val w = readU16LE(raw, FULL_WIDTH_OFFSET).let {
            if (it in 100..3840) it else REAL_SCREEN_WIDTH }
        val h = readU16LE(raw, FULL_HEIGHT_OFFSET).let {
            if (it in 100..2160) it else REAL_SCREEN_HEIGHT }
        val pixelCount = w * h
        if (raw.size < PIXEL_DATA_OFFSET + pixelCount) return null
        val pixels = IntArray(pixelCount) { i ->
            colorPalette[raw[PIXEL_DATA_OFFSET + i].toInt() and 0xFF]
        }
        return ScreenFrameData(0, 0, w, h, pixels, true)
    }

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
        val pixels = IntArray(pixelCount) { i ->
            colorPalette[raw[PIXEL_DATA_OFFSET + i].toInt() and 0xFF]
        }
        return ScreenFrameData(x, y, w, h, pixels, false)
    }

    fun applyFrameToBitmap(bitmap: Bitmap, frameData: ScreenFrameData) {
        if (frameData.isFullFrame) {
            bitmap.setPixels(frameData.pixels, 0, frameData.width, 0, 0,
                frameData.width.coerceAtMost(bitmap.width),
                frameData.height.coerceAtMost(bitmap.height))
        } else {
            val safeX = frameData.x.coerceIn(0, bitmap.width  - 1)
            val safeY = frameData.y.coerceIn(0, bitmap.height - 1)
            val safeW = frameData.width .coerceAtMost(bitmap.width  - safeX)
            val safeH = frameData.height.coerceAtMost(bitmap.height - safeY)
            if (safeW > 0 && safeH > 0 && frameData.pixels.size >= safeW * safeH) {
                bitmap.setPixels(frameData.pixels, 0, frameData.width,
                    safeX, safeY, safeW, safeH)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  واجهة استرجاع البيانات
    // ══════════════════════════════════════════════════════════════════

    fun getAllFrames()    : List<LrecFrame>   = _frames.toList()
    fun getScreenFrames(): List<LrecFrame>   = _frames.toList()
    fun getAudioBlocks() : List<AudioBlock>  = _audioBlocks.toList()   // ← جديد
    fun getChatFrames()  : List<LrecFrame>   = emptyList()
    fun getDurationMs()  : Long              = _durationMs
    fun getTotalFrames() : Int               = _frames.size
    fun isPaletteLoaded(): Boolean           = paletteLoaded
    fun getChatEntries() : List<ChatEntry>   = _chatEntries.toList()

    /** إرجاع الكتل الصوتية التي يجب تشغيلها ابتداءً من وقت معيّن */
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

    // ── مساعدات ──────────────────────────────────────────────────────

    private fun readU16LE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return  (data[offset].toLong()   and 0xFF) or
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

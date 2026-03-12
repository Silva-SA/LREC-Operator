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

        // ⚠️ هذه القيم افتراضية فقط — سيتم تحديثها من الملف الفعلي
        const val FALLBACK_WIDTH  = 1024
        const val FALLBACK_HEIGHT = 768

        const val LINKTIVITY_SIG = "Linktivity"

        private const val ZLIB_OFFSET        = 12
        private const val FULL_WIDTH_OFFSET  = 9
        private const val FULL_HEIGHT_OFFSET = 13
        private const val PIXEL_DATA_OFFSET  = 21
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

    // الأبعاد الحقيقية — تُكتشف من أول Full Frame
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

    // لوحة الألوان — مُهيَّأة بألوان رمادية حتى تُقرأ اللوحة الحقيقية
    private val colorPalette     = IntArray(256) { i ->
        val v = (i * 255 / 255)
        Color.rgb(v, v, v)
    }
    private var paletteLoaded    = false
    private val decompressBuffer = ByteArray(4_000_000) // 4MB كافٍ لأي دقة

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

                // تحديث الأبعاد الحقيقية في metadata
                if (_realWidth > 0 && _realHeight > 0) {
                    metadata = metadata.copy(
                        screenWidth  = _realWidth,
                        screenHeight = _realHeight
                    )
                }

                Log.d(TAG, "الأبعاد الحقيقية: ${metadata.screenWidth}×${metadata.screenHeight}")
                Log.d(TAG, "الإطارات: ${_frames.size} | الصوت: ${_audioBlocks.size} | الدردشة: ${_chatEntries.size}")

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
                                    SUBTYPE_PALETTE -> {
                                        extractPalette(blockData)
                                    }
                                    SUBTYPE_FULLFRAME -> {
                                        // اكتشاف الأبعاد من أول Full Frame
                                        if (_realWidth == 0) {
                                            detectDimensionsFromBlock(blockData)
                                        }
                                        _frames.add(LrecFrame(
                                            fileOffset = pos,
                                            type       = subtype,
                                            dataLength = dataLen,
                                            timestamp  = tsMs,
                                            rawData    = blockData
                                        ))
                                        tsMs += MS_PER_FRAME
                                    }
                                    SUBTYPE_DELTA -> {
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
    }

    // ══════════════════════════════════════════════════════════════════
    //  اكتشاف الأبعاد الحقيقية من Full Frame
    //  ← هذا هو إصلاح مشكلة الصورة المقطوعة
    // ══════════════════════════════════════════════════════════════════

    private fun detectDimensionsFromBlock(blockData: ByteArray) {
        val decompressed = zlibDecompress(blockData) ?: return
        if (decompressed.size <= PIXEL_DATA_OFFSET) return

        val w = readU16LE(decompressed, FULL_WIDTH_OFFSET)
        val h = readU16LE(decompressed, FULL_HEIGHT_OFFSET)

        if (w in 100..3840 && h in 100..2160) {
            _realWidth  = w
            _realHeight = h
            Log.d(TAG, "اكتُشفت الأبعاد الحقيقية: ${w}×${h}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  استخراج الكتلة الصوتية
    //  ← إصلاح مشكلة التشويش الصوتي
    // ══════════════════════════════════════════════════════════════════

    private fun extractAudioBlock(blockData: ByteArray, timestampMs: Long): AudioBlock? {
        if (blockData.size < 16) return null

        // ── البحث عن بداية البيانات الصوتية الفعلية ──────────────────
        // نتجاوز الـ header ونبحث عن بيانات صوتية منطقية
        val audioOffset = findValidAudioOffset(blockData)
        if (audioOffset < 0) return null

        val audioLen = blockData.size - audioOffset
        if (audioLen < 8) return null

        return AudioBlock(
            timestampMs = timestampMs,
            rawData     = blockData,
            dataOffset  = audioOffset,
            sampleRate  = 8000,
            channels    = 1
        )
    }

    /**
     * البحث عن الـ offset الصحيح لبيانات الصوت
     * يتجاوز الـ header ويجد أول byte منطقي لـ G.711
     */
    private fun findValidAudioOffset(blockData: ByteArray): Int {
        // G.711 μ-law: القيم تتراوح بين 0x00 و 0xFF
        // لكن القيم الصالحة صوتياً تميل لأن تكون موزّعة بشكل معقول
        // الـ header عادةً يحتوي على أصفار أو قيم ثابتة

        // جرب offsets شائعة
        val candidates = listOf(8, 12, 16, 20, 4)
        for (offset in candidates) {
            if (offset + 8 > blockData.size) continue
            val segment = blockData.copyOfRange(offset,
                (offset + 64).coerceAtMost(blockData.size))

            // تحقق من أن البيانات تبدو كصوت G.711
            if (looksLikeAudio(segment)) {
                return offset
            }
        }
        // إذا لم نجد offset مناسب، ارجع null لتجنب التشويش
        return -1
    }

    /**
     * التحقق من أن البيانات تبدو كصوت حقيقي وليس مجرد بيانات عشوائية
     */
    private fun looksLikeAudio(data: ByteArray): Boolean {
        if (data.size < 8) return false

        // عدّ التغيّرات بين القيم المتتالية
        // الصوت الحقيقي له تغيّرات منتظمة نسبياً
        var transitions = 0
        var zeros = 0
        for (i in 1 until data.size.coerceAtMost(32)) {
            val diff = Math.abs(
                (data[i].toInt() and 0xFF) - (data[i-1].toInt() and 0xFF)
            )
            if (diff > 5) transitions++
            if (data[i] == 0.toByte()) zeros++
        }

        // إذا كانت كل القيم أصفاراً → header وليس صوت
        if (zeros > data.size * 0.8) return false

        // إذا كانت التغيّرات كثيرة جداً → ضجيج وليس صوت
        if (transitions > data.size * 0.9) return false

        return transitions > 2
    }

    // ══════════════════════════════════════════════════════════════════
    //  استخراج رسالة الدردشة
    //  ← إصلاح مشكلة النص المشوّه
    // ══════════════════════════════════════════════════════════════════

    private fun extractChatEntry(blockData: ByteArray, timestampMs: Long): ChatEntry? {
        if (blockData.size < 10) return null

        // ── المحاولة 1: نص ASCII/Latin واضح فقط ──────────────────────
        // نبحث عن سلاسل نصية مستمرة من حروف ASCII قابلة للقراءة
        val asciiResult = extractAsciiText(blockData)
        if (asciiResult != null) {
            Log.d(TAG, "دردشة ASCII: ${asciiResult.second}")
            return ChatEntry(timestampMs, asciiResult.first, asciiResult.second, true)
        }

        // ── المحاولة 2: Base64 → UTF-8 ────────────────────────────────
        val base64Result = tryBase64Decode(blockData)
        if (base64Result != null) {
            Log.d(TAG, "دردشة Base64: ${base64Result.second}")
            return ChatEntry(timestampMs, base64Result.first, base64Result.second, true)
        }

        // ── تسجيل للتشخيص ─────────────────────────────────────────────
        Log.d(TAG,
            "كتلة دردشة | الحجم=${blockData.size} " +
            "| أول 16 byte=${blockData.take(16).joinToString(" ") {
                "%02X".format(it.toInt() and 0xFF) }}")

        return null
    }

    private fun extractAsciiText(blockData: ByteArray): Pair<String, String>? {
        // نبحث عن أطول سلسلة ASCII مستمرة داخل الكتلة
        var bestStr = ""
        var current = StringBuilder()

        for (i in 4 until blockData.size) {
            val c = blockData[i].toInt() and 0xFF
            if (c in 32..126) {
                current.append(c.toChar())
            } else {
                if (current.length > bestStr.length) {
                    bestStr = current.toString()
                }
                current = StringBuilder()
            }
        }
        if (current.length > bestStr.length) bestStr = current.toString()

        // تنظيف النص
        val cleaned = bestStr.trim().replace(Regex("\\s{2,}"), " ")

        // ✅ التحقق الصارم: يجب أن يكون نصاً مقروءاً حقيقياً
        if (cleaned.length < 3) return null
        if (cleaned.length > 500) return null

        // يجب أن تكون الغالبية حروفاً وأرقاماً
        val alphaNum = cleaned.count { it.isLetterOrDigit() || it == ' ' }
        if (alphaNum.toFloat() / cleaned.length < 0.7f) return null

        // يجب ألا يكون مجرد رموز أو أرقام متكررة
        val uniqueChars = cleaned.toSet().size
        if (uniqueChars < 3) return null

        return parseChatText(cleaned)
    }

    private fun tryBase64Decode(blockData: ByteArray): Pair<String, String>? {
        for (offset in listOf(4, 6, 8, 12)) {
            if (offset >= blockData.size) continue
            val remaining = blockData.size - offset
            if (remaining < 8) continue

            try {
                val rawStr = String(blockData, offset, remaining, Charsets.ISO_8859_1)
                // Base64 صالح: فقط الحروف والأرقام و +/= ويجب أن يكون طويلاً كافياً
                val base64Regex = Regex("[A-Za-z0-9+/]{12,}={0,2}")
                val match = base64Regex.find(rawStr) ?: continue

                val decoded = android.util.Base64.decode(match.value,
                                  android.util.Base64.DEFAULT)
                if (decoded.size < 3) continue

                val text = String(decoded, Charsets.UTF_8).trim()

                // ✅ تحقق صارم من النص المفكوك
                if (text.length < 3) continue
                if (text.length > 500) continue
                val printable = text.count { it.code in 32..126 || it.code > 160 }
                if (printable.toFloat() / text.length < 0.8f) continue

                return parseChatText(text)
            } catch (e: Exception) { continue }
        }
        return null
    }

    private fun isValidChatText(text: String): Boolean {
        if (text.length < 3 || text.length > 500) return false
        val printable = text.count { it.code in 32..126 || it.code > 160 }
        return printable.toFloat() / text.length > 0.8f
    }

    private fun parseChatText(text: String): Pair<String, String> {
        val colonIdx = text.indexOf(':')
        return if (colonIdx in 1..30) {
            val sender  = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            if (sender.isNotBlank() && message.isNotBlank() && sender.length < 30)
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
        Log.d(TAG, "لوحة الألوان مُحمَّلة ✅")
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
                val n = inflater.inflate(
                    decompressBuffer, total,
                    decompressBuffer.size - total
                )
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
                    val n = inf2.inflate(
                        decompressBuffer, total,
                        decompressBuffer.size - total
                    )
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
        } catch (e: Exception) {
            Log.w(TAG, "خطأ في فك تشفير الإطار: ${e.message}")
            null
        }
    }

    private fun decodeFullFrame(raw: ByteArray): ScreenFrameData? {
        if (raw.size < PIXEL_DATA_OFFSET + 100) return null

        val w = readU16LE(raw, FULL_WIDTH_OFFSET).let {
            if (it in 100..3840) it else (_realWidth.takeIf { r -> r > 0 } ?: FALLBACK_WIDTH)
        }
        val h = readU16LE(raw, FULL_HEIGHT_OFFSET).let {
            if (it in 100..2160) it else (_realHeight.takeIf { r -> r > 0 } ?: FALLBACK_HEIGHT)
        }

        // تحديث الأبعاد الحقيقية عند اكتشافها
        if (_realWidth == 0 && w > 0) { _realWidth = w; _realHeight = h }

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
                bitmap.setPixels(
                    frameData.pixels, 0, frameData.width,
                    safeX, safeY, safeW, safeH
                )
            }
        }
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
        if (offset + 1 >= data.size) return 0
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
                    if (str.isNotBlank() &&
                        str.all { it.code in 32..126 || it.code > 160 })
                        result.add(str)
                }
                s = i + 1
            }
        }
        return result
    }
}

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

    private fun extractAsciiText(blockData: ByteArray): Pair<String, Stri

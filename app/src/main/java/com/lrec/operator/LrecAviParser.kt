package com.lrec.operator

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * محلّل RIFF/AVI كامل لملفات Inter-Tel .lrec (الصيغة الجديدة)
 * يستخرج 4 مسارات: فيديو + صوت + دردشة + شاشة
 */
class LrecAviParser(private val file: File) {

    companion object {
        private const val TAG = "LrecAviParser"

        // FOURCC constants
        private fun fourcc(s: String): Int {
            return (s[0].code) or (s[1].code shl 8) or
                   (s[2].code shl 16) or (s[3].code shl 24)
        }

        val RIFF = fourcc("RIFF")
        val AVI_ = fourcc("AVI ")
        val LIST = fourcc("LIST")
        val HDRL = fourcc("hdrl")
        val MOVI = fourcc("movi")
        val AVIH = fourcc("avih")
        val STRL = fourcc("strl")
        val STRH = fourcc("strh")
        val STRF = fourcc("strf")
        val IDX1 = fourcc("idx1")

        // Stream types
        val VIDS = fourcc("vids")
        val AUDS = fourcc("auds")
        val TXTS = fourcc("txts")

        // Codec FourCCs
        val H263 = fourcc("H263")
        val H263_LOWER = fourcc("h263")
        val I263 = fourcc("I263")
    }

    // ══════════════════════════════════════════════════════════════════
    //  نماذج البيانات
    // ══════════════════════════════════════════════════════════════════

    data class AviHeader(
        val microSecPerFrame: Long = 0,
        val maxBytesPerSec:   Long = 0,
        val totalFrames:      Long = 0,
        val streams:          Int  = 0,
        val width:            Int  = 0,
        val height:           Int  = 0
    )

    data class StreamHeader(
        val type:          Int    = 0,
        val handler:       Int    = 0,
        val scale:         Long   = 1,
        val rate:          Long   = 1,
        val length:        Long   = 0,
        val sampleSize:    Int    = 0
    ) {
        val fps: Double get() = if (scale > 0) rate.toDouble() / scale else 0.0
    }

    data class AudioFormat(
        val formatTag:     Int = 7,  // 6=A-law, 7=μ-law, 1=PCM
        val channels:      Int = 1,
        val sampleRate:    Int = 8000,
        val bitsPerSample: Int = 8
    ) {
        val codec: G711Codec.Codec get() = when (formatTag) {
            6    -> G711Codec.Codec.ALAW
            7    -> G711Codec.Codec.ULAW
            1    -> if (bitsPerSample == 8) G711Codec.Codec.PCM8 else G711Codec.Codec.PCM16
            else -> G711Codec.Codec.ULAW
        }
    }

    data class AviChunk(
        val streamIndex: Int,
        val fourcc:      Int,
        val offset:      Long,   // موقع البيانات في الملف
        val size:        Int,
        val timestampMs: Long
    )

    data class ParseResult(
        val aviHeader:     AviHeader               = AviHeader(),
        val videoStream:   StreamHeader?            = null,
        val audioStream:   StreamHeader?            = null,
        val audioFormat:   AudioFormat              = AudioFormat(),
        val videoWidth:    Int                      = 0,
        val videoHeight:   Int                      = 0,
        val videoChunks:   List<AviChunk>           = emptyList(),
        val audioChunks:   List<AviChunk>           = emptyList(),
        val textChunks:    List<AviChunk>           = emptyList(),
        val durationMs:    Long                     = 0,
        val isValid:       Boolean                  = false
    )

    // ══════════════════════════════════════════════════════════════════
    //  التحليل الرئيسي
    // ══════════════════════════════════════════════════════════════════

    fun parse(): ParseResult {
        if (!file.exists() || file.length() < 64) return ParseResult()
        return try {
            RandomAccessFile(file, "r").use { raf -> parseAvi(raf) }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل AVI", e)
            ParseResult()
        }
    }

    private fun parseAvi(raf: RandomAccessFile): ParseResult {
        // RIFF header
        val riffId   = raf.readIntLE()
        val fileSize = raf.readIntLE()
        val aviId    = raf.readIntLE()

        if (riffId != RIFF || aviId != AVI_) {
            Log.w(TAG, "ليس ملف AVI صالح")
            return ParseResult()
        }

        var aviHeader:   AviHeader?     = null
        var videoStream: StreamHeader?  = null
        var audioStream: StreamHeader?  = null
        var audioFormat                 = AudioFormat()
        var videoWidth                  = 0
        var videoHeight                 = 0
        val streamHeaders               = mutableListOf<StreamHeader>()
        val audioFormats                = mutableListOf<AudioFormat>()
        var moviOffset                  = 0L
        var moviSize                    = 0

        // مسح الـ chunks الرئيسية
        var pos = 12L
        val fileLen = raf.length()

        while (pos < fileLen - 8) {
            raf.seek(pos)
            val chunkId   = raf.readIntLE()
            val chunkSize = raf.readIntLE()
            if (chunkSize < 0 || chunkSize > fileLen) break

            when (chunkId) {
                LIST -> {
                    val listType = raf.readIntLE()
                    when (listType) {
                        HDRL -> parseHdrl(raf, pos + 12,
                            (chunkSize - 4).toLong(), raf.length(),
                            streamHeaders, audioFormats).also { result ->
                            aviHeader = result.first
                            if (result.second > 0) { videoWidth = result.second; videoHeight = result.third }
                        }
                        MOVI -> {
                            moviOffset = pos + 12
                            moviSize   = chunkSize - 4
                        }
                    }
                }
            }

            pos += 8 + chunkSize
            if (chunkSize % 2 != 0) pos++ // RIFF padding
        }

        // ربط الـ streams
        streamHeaders.forEachIndexed { i, sh ->
            when (sh.type) {
                VIDS -> { videoStream = sh }
                AUDS -> {
                    audioStream = sh
                    audioFormat = audioFormats.getOrElse(i) { AudioFormat() }
                }
            }
        }

        if (moviOffset == 0L) return ParseResult()

        // استخراج الـ chunks من movi
        val allChunks = parseMovi(raf, moviOffset, moviSize.toLong(),
            videoStream, audioStream, audioFormat)

        val videoChunks = allChunks.filter { it.streamIndex == 0 }
        val audioChunks = allChunks.filter { it.streamIndex == 1 }
        val textChunks  = allChunks.filter { it.streamIndex >= 2 }

        val durationMs = when {
            videoStream != null && videoStream!!.fps > 0 ->
                (videoChunks.size * 1000.0 / videoStream!!.fps).toLong()
            audioStream != null && audioStream!!.sampleSize > 0 ->
                audioChunks.sumOf { it.size }.toLong() * 1000L /
                (audioFormat.sampleRate * audioFormat.channels)
            else -> 0L
        }

        Log.d(TAG, "AVI: ${videoChunks.size} فيديو | ${audioChunks.size} صوت | ${textChunks.size} نص | مدة=${durationMs}ms")

        return ParseResult(
            aviHeader   = aviHeader ?: AviHeader(),
            videoStream = videoStream,
            audioStream = audioStream,
            audioFormat = audioFormat,
            videoWidth  = videoWidth,
            videoHeight = videoHeight,
            videoChunks = videoChunks,
            audioChunks = audioChunks,
            textChunks  = textChunks,
            durationMs  = durationMs,
            isValid     = videoChunks.isNotEmpty() || audioChunks.isNotEmpty()
        )
    }

    private fun parseHdrl(
        raf: RandomAccessFile,
        start: Long,
        size: Long,
        fileLen: Long,
        streams: MutableList<StreamHeader>,
        formats: MutableList<AudioFormat>
    ): Triple<AviHeader, Int, Int> {
        var aviHeader = AviHeader()
        var vw = 0; var vh = 0
        var pos = start

        while (pos < start + size && pos < fileLen - 8) {
            raf.seek(pos)
            val id        = raf.readIntLE()
            val chunkSize = raf.readIntLE()
            if (chunkSize < 0) break

            when (id) {
                AVIH -> aviHeader = parseAvih(raf).also {
                    vw = it.width; vh = it.height
                }
                LIST -> {
                    val listType = raf.readIntLE()
                    if (listType == STRL) {
                        parseStrl(raf, pos + 12, (chunkSize - 4).toLong(), fileLen, streams, formats)
                    }
                }
            }
            pos += 8 + chunkSize
            if (chunkSize % 2 != 0) pos++
        }
        return Triple(aviHeader, vw, vh)
    }

    private fun parseAvih(raf: RandomAccessFile): AviHeader {
        return AviHeader(
            microSecPerFrame = raf.readIntLE().toLong() and 0xFFFFFFFFL,
            maxBytesPerSec   = raf.readIntLE().toLong() and 0xFFFFFFFFL,
            totalFrames      = run { raf.skipBytes(8); raf.readIntLE().toLong() and 0xFFFFFFFFL },
            streams          = run { raf.skipBytes(12); raf.readIntLE() },
            width            = run { raf.skipBytes(4);  raf.readIntLE() },
            height           = raf.readIntLE()
        )
    }

    private fun parseStrl(
        raf: RandomAccessFile,
        start: Long,
        size: Long,
        fileLen: Long,
        streams: MutableList<StreamHeader>,
        formats: MutableList<AudioFormat>
    ) {
        var streamHeader = StreamHeader()
        var audioFmt     = AudioFormat()
        var pos          = start

        while (pos < start + size && pos < fileLen - 8) {
            raf.seek(pos)
            val id        = raf.readIntLE()
            val chunkSize = raf.readIntLE()
            if (chunkSize < 0) break

            when (id) {
                STRH -> streamHeader = parseStrh(raf)
                STRF -> audioFmt = parseStrf(raf, streamHeader.type, chunkSize)
            }
            pos += 8 + chunkSize
            if (chunkSize % 2 != 0) pos++
        }

        streams.add(streamHeader)
        formats.add(audioFmt)
    }

    private fun parseStrh(raf: RandomAccessFile): StreamHeader {
        val type    = raf.readIntLE()
        val handler = raf.readIntLE()
        raf.skipBytes(8)
        val scale   = raf.readIntLE().toLong() and 0xFFFFFFFFL
        val rate    = raf.readIntLE().toLong() and 0xFFFFFFFFL
        raf.skipBytes(8)
        val length  = raf.readIntLE().toLong() and 0xFFFFFFFFL
        raf.skipBytes(4)
        val sampleSize = raf.readIntLE()
        return StreamHeader(type, handler, scale.coerceAtLeast(1), rate, length, sampleSize)
    }

    private fun parseStrf(raf: RandomAccessFile, streamType: Int, size: Int): AudioFormat {
        return if (streamType == AUDS && size >= 14) {
            AudioFormat(
                formatTag     = raf.readShortLE().toInt() and 0xFFFF,
                channels      = raf.readShortLE().toInt() and 0xFFFF,
                sampleRate    = raf.readIntLE(),
                bitsPerSample = run { raf.skipBytes(6); raf.readShortLE().toInt() and 0xFFFF }
            )
        } else AudioFormat()
    }

    private fun parseMovi(
        raf: RandomAccessFile,
        start: Long,
        size: Long,
        videoStream: StreamHeader?,
        audioStream: StreamHeader?,
        audioFormat: AudioFormat
    ): List<AviChunk> {
        val chunks   = mutableListOf<AviChunk>()
        var pos      = start
        val end      = start + size
        val fileLen  = raf.length()

        val videoFps     = videoStream?.fps?.takeIf { it > 0 } ?: 25.0
        var videoCount   = 0L
        var audioSamples = 0L

        while (pos < end - 8 && pos < fileLen - 8) {
            raf.seek(pos)
            val id        = raf.readIntLE()
            val chunkSize = raf.readIntLE()

            if (chunkSize < 0 || chunkSize > 50_000_000) { pos++; continue }

            // chunk ID format: "00dc" (video), "01wb" (audio), "02tx" (text)
            val streamIdx = extractStreamIndex(id)
            val chunkType = id and 0x0000FFFF.inv().inv() // last 2 bytes

            if (streamIdx >= 0) {
                val tsMs = when (streamIdx) {
                    0    -> (videoCount * 1000.0 / videoFps).toLong()
                    1    -> if (audioFormat.sampleRate > 0)
                                audioSamples * 1000L / audioFormat.sampleRate
                            else videoCount * 1000L / videoFps.toLong()
                    else -> (videoCount * 1000.0 / videoFps).toLong()
                }

                chunks.add(AviChunk(
                    streamIndex = streamIdx,
                    fourcc      = id,
                    offset      = pos + 8,
                    size        = chunkSize,
                    timestampMs = tsMs
                ))

                when (streamIdx) {
                    0 -> videoCount++
                    1 -> audioSamples += if (audioFormat.bitsPerSample > 0)
                         chunkSize.toLong()
                         else chunkSize.toLong()
                }
            }

            pos += 8 + chunkSize
            if (chunkSize % 2 != 0) pos++
        }

        return chunks
    }

    private fun extractStreamIndex(fourcc: Int): Int {
        val c0 = (fourcc and 0xFF).toChar()
        val c1 = ((fourcc shr 8) and 0xFF).toChar()
        if (c0.isDigit() && c1.isDigit()) {
            return (c0 - '0') * 10 + (c1 - '0')
        }
        return -1
    }

    /** قراءة بيانات chunk من الملف */
    fun readChunkData(raf: RandomAccessFile, chunk: AviChunk): ByteArray {
        val data = ByteArray(chunk.size)
        raf.seek(chunk.offset)
        raf.read(data)
        return data
    }

    // ══════════════════════════════════════════════════════════════════
    //  مساعدات قراءة Little-Endian
    // ══════════════════════════════════════════════════════════════════

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun RandomAccessFile.readShortLE(): Short {
        val b0 = read(); val b1 = read()
        if (b0 < 0 || b1 < 0) return -1
        return (b0 or (b1 shl 8)).toShort()
    }
}

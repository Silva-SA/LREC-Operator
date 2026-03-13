package com.lrec.operator

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * فك ترميز H.263 عبر MediaCodec:
 * - يكتشف تلقائياً MIME type المدعوم
 * - تحويل YUV420 → ARGB
 * - إخراج إلى ByteBuffer
 */
class LrecVideoDecoder(
    private val width:  Int,
    private val height: Int
) {

    companion object {
        private const val TAG = "LrecVideoDecoder"

        private val H263_MIMES = listOf(
            "video/3gpp",   // H.263
            "video/h263",
            "video/x-h263"
        )

        fun findSupportedMime(): String? {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (mime in H263_MIMES) {
                    if (info.supportedTypes.contains(mime)) {
                        Log.d(TAG, "MIME مدعوم: $mime (${info.name})")
                        return mime
                    }
                }
            }
            return null
        }
    }

    private var codec:      MediaCodec? = null
    private var isStarted   = false
    private val outputWidth  = width
    private val outputHeight = height

    // ══════════════════════════════════════════════════════════════════
    //  تهيئة الـ Codec
    // ══════════════════════════════════════════════════════════════════

    fun initialize(): Boolean {
        val mime = findSupportedMime() ?: run {
            Log.w(TAG, "⚠️ لا يوجد decoder H.263 — سيتم تخطي الفيديو")
            return false
        }

        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            codec = MediaCodec.createDecoderByType(mime)
            codec!!.configure(format, null, null, 0)
            codec!!.start()
            isStarted = true
            Log.d(TAG, "✅ H.263 decoder: ${width}×${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تهيئة decoder: ${e.message}")
            release()
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  فك ترميز إطار
    // ══════════════════════════════════════════════════════════════════

    fun decodeFrame(frameData: ByteArray, timestampUs: Long): IntArray? {
        val c = codec ?: return null
        if (!isStarted) return null

        return try {
            // ── إدخال البيانات ──────────────────────────────────────
            val inputIdx = c.dequeueInputBuffer(10_000L)
            if (inputIdx >= 0) {
                val inputBuf = c.getInputBuffer(inputIdx) ?: return null
                inputBuf.clear()
                val toCopy = minOf(frameData.size, inputBuf.capacity())
                inputBuf.put(frameData, 0, toCopy)
                c.queueInputBuffer(inputIdx, 0, toCopy, timestampUs, 0)
            }

            // ── استخراج النتيجة ─────────────────────────────────────
            val bufferInfo = MediaCodec.BufferInfo()
            val outputIdx  = c.dequeueOutputBuffer(bufferInfo, 10_000L)

            if (outputIdx >= 0) {
                val outputBuf = c.getOutputBuffer(outputIdx)
                val argb      = outputBuf?.let { convertToArgb(it, bufferInfo.size) }
                c.releaseOutputBuffer(outputIdx, false)
                argb
            } else null

        } catch (e: Exception) {
            Log.w(TAG, "خطأ في فك إطار: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  تحويل YUV420 → ARGB
    // ══════════════════════════════════════════════════════════════════

    private fun convertToArgb(yuvBuffer: ByteBuffer, size: Int): IntArray {
        val yuv    = ByteArray(size)
        yuvBuffer.rewind()
        yuvBuffer.get(yuv, 0, minOf(size, yuv.size))

        val argb   = IntArray(outputWidth * outputHeight)
        val ySize  = outputWidth * outputHeight
        val uvSize = ySize / 4

        for (j in 0 until outputHeight) {
            for (i in 0 until outputWidth) {
                val yIdx  = j * outputWidth + i
                val uvIdx = ySize + (j / 2) * (outputWidth / 2) + (i / 2)

                val y = (yuv.getOrElse(yIdx)  { 0 }.toInt() and 0xFF)
               val u = (yuv.getOrElse(uvIdx)  { 128.toByte() }.toInt() and 0xFF) - 128
               val v = (yuv.getOrElse(uvIdx + uvSize) { 128.toByte() }.toInt() and 0xFF) - 128

                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)

                argb[yIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return argb
    }

    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) { }
        codec     = null
        isStarted = false
    }

    val isReady: Boolean get() = isStarted && codec != null
}

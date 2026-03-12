package com.lrec.operator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class LrecAudioPlayer {

    companion object {
        private const val TAG = "LrecAudioPlayer"
        private const val SAMPLE_RATE = 8000

        // جدول فك ترميز G.711 μ-law → PCM 16-bit
        private val ULAW_TABLE = IntArray(256) { i ->
            val ulaw   = i.inv() and 0xFF
            val sign   = ulaw and 0x80
            val exp    = (ulaw shr 4) and 0x07
            val mant   = ulaw and 0x0F
            var sample = ((mant shl 1) or 0x21) shl exp
            sample -= 0x21
            if (sign != 0) -sample else sample
        }

        // جدول فك ترميز G.711 A-law → PCM 16-bit
        private val ALAW_TABLE = IntArray(256) { i ->
            val alaw   = i xor 0x55
            val sign   = alaw and 0x80
            val exp    = (alaw shr 4) and 0x07
            val mant   = alaw and 0x0F
            var sample = if (exp == 0) {
                (mant shl 1) or 1
            } else {
                ((mant or 0x10) shl 1) shl (exp - 1)
            }
            sample = sample shl 3
            if (sign == 0) -sample else sample
        }

        fun decodeUlaw(input: ByteArray, offset: Int, length: Int): ShortArray {
            val out = ShortArray(length)
            for (i in 0 until length) {
                out[i] = ULAW_TABLE[input[offset + i].toInt() and 0xFF]
                    .coerceIn(-32768, 32767).toShort()
            }
            return out
        }

        fun decodeAlaw(input: ByteArray, offset: Int, length: Int): ShortArray {
            val out = ShortArray(length)
            for (i in 0 until length) {
                out[i] = ALAW_TABLE[input[offset + i].toInt() and 0xFF]
                    .coerceIn(-32768, 32767).toShort()
            }
            return out
        }
    }

    enum class AudioCodec { ULAW, ALAW, PCM16 }

    private var audioTrack:    AudioTrack? = null
    private var isPlaying      = false
    private var isPaused       = false
    private var audioThread:   Thread? = null
    private var detectedCodec  = AudioCodec.ULAW

    private val audioQueue = ArrayDeque<ByteArray>()
    private val queueLock  = Object()

    // ── تهيئة AudioTrack ──────────────────────────────────────────────
    fun initialize(sampleRate: Int = SAMPLE_RATE, stereo: Boolean = false) {
        release()
        val channelConfig = if (stereo)
            AudioFormat.CHANNEL_OUT_STEREO
        else
            AudioFormat.CHANNEL_OUT_MONO

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "AudioTrack جاهز: ${sampleRate}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "فشل تهيئة AudioTrack: ${e.message}")
            audioTrack = null
        }
    }

    // ── تشغيل الصوت ──────────────────────────────────────────────────
    fun startPlayback(
        audioBlocks: List<ByteArray>,
        startIndex:  Int = 0,
        dataOffset:  Int = 8
    ) {
        if (audioTrack == null) initialize()
        if (isPlaying) return

        isPlaying = true
        isPaused  = false

        // اكتشاف الكودك من أول كتلة
        if (audioBlocks.isNotEmpty()) {
            detectedCodec = detectCodec(audioBlocks[0], dataOffset)
            Log.d(TAG, "الكودك المكتشف: $detectedCodec")
        }

        synchronized(queueLock) {
            audioQueue.clear()
            for (i in startIndex until audioBlocks.size) {
                audioQueue.addLast(audioBlocks[i])
            }
        }

        audioTrack?.play()

        audioThread = Thread {
            processQueue(dataOffset)
        }.also { it.start() }
    }

    private fun processQueue(dataOffset: Int) {
        while (isPlaying) {
            val block: ByteArray?
            synchronized(queueLock) {
                block = if (audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
            }

            if (block == null) { Thread.sleep(10); continue }

            while (isPaused && isPlaying) Thread.sleep(10)
            if (!isPlaying) break

            val offset  = dataOffset.coerceAtMost(block.size - 1)
            val len     = block.size - offset
            if (len <= 4) continue

            try {
                val pcm: ShortArray = when (detectedCodec) {
                    AudioCodec.ULAW  -> decodeUlaw(block, offset, len)
                    AudioCodec.ALAW  -> decodeAlaw(block, offset, len)
                    AudioCodec.PCM16 -> ShortArray(len / 2) { i ->
                        val lo = block[offset + i * 2].toInt() and 0xFF
                        val hi = block[offset + i * 2 + 1].toInt() and 0xFF
                        ((hi shl 8) or lo).toShort()
                    }
                }
                audioTrack?.write(pcm, 0, pcm.size)
            } catch (e: Exception) {
                Log.w(TAG, "خطأ في معالجة كتلة صوت: ${e.message}")
            }
        }
    }

    /**
     * اكتشاف الكودك بناءً على إحصاء توزيع القيم
     * G.711 μ-law: معظم القيم بين 0x00-0x1F أو 0xE0-0xFF
     * G.711 A-law: معظم القيم بين 0x20-0xDF
     * PCM16: توزيع أكثر عشوائية
     */
    private fun detectCodec(blockData: ByteArray, offset: Int): AudioCodec {
        if (blockData.size - offset < 16) return AudioCodec.ULAW

        val start = offset.coerceAtMost(blockData.size - 1)
        val end   = (start + 128).coerceAtMost(blockData.size)

        var ulawRange  = 0  // قيم في نطاق μ-law النموذجي
        var alawRange  = 0  // قيم في نطاق A-law النموذجي

        for (i in start until end) {
            val v = blockData[i].toInt() and 0xFF
            if (v in 0x00..0x1F || v in 0xE0..0xFF) ulawRange++
            if (v in 0x20..0xDF) alawRange++
        }

        return when {
            ulawRange > alawRange -> AudioCodec.ULAW
            alawRange > ulawRange -> AudioCodec.ALAW
            else                  -> AudioCodec.ULAW  // الافتراضي
        }
    }

    fun enqueueBlock(blockData: ByteArray) {
        synchronized(queueLock) { audioQueue.addLast(blockData) }
    }

    fun flushQueue() {
        synchronized(queueLock) { audioQueue.clear() }
        audioTrack?.flush()
    }

    fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        audioTrack?.pause()
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        audioTrack?.play()
    }

    fun stop() {
        isPlaying = false
        isPaused  = false
        audioThread?.interrupt()
        audioThread = null
        try { audioTrack?.pause(); audioTrack?.flush() } catch (e: Exception) { }
        synchronized(queueLock) { audioQueue.clear() }
    }

    fun release() {
        stop()
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) { }
        audioTrack = null
    }

    val isActive: Boolean get() = isPlaying && !isPaused
}

package com.lrec.operator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class LrecAudioPlayer {

    companion object {
        private const val TAG = "LrecAudioPlayer"

        private val ULAW_TABLE: ShortArray = ShortArray(256) { i ->
            val ulaw  = (i xor 0xFF) and 0xFF
            val sign  = ulaw and 0x80
            val exp   = (ulaw shr 4) and 0x07
            val mant  = ulaw and 0x0F
            var value = ((mant + 33) shl (exp + 2)) - 132
            value     = if (sign != 0) -value else value
            value.coerceIn(-32768, 32767).toShort()
        }

        private val ALAW_TABLE: ShortArray = ShortArray(256) { i ->
            val alaw  = (i xor 0x55) and 0xFF
            val sign  = alaw and 0x80
            val exp   = (alaw shr 4) and 0x07
            val mant  = alaw and 0x0F
            val value = when (exp) {
                0    -> (mant shl 1) or 1
                else -> ((mant or 0x10) shl 1 or 1) shl (exp - 1)
            }
            val signed = if (sign != 0) value else -value
            (signed * 8).coerceIn(-32768, 32767).toShort()
        }

        private const val GAIN = 3.0f

        fun decodeUlaw(input: ByteArray, offset: Int, length: Int): ShortArray {
            val out = ShortArray(length)
            for (i in 0 until length) {
                out[i] = ULAW_TABLE[input[offset + i].toInt() and 0xFF]
            }
            return out
        }

        fun decodeAlaw(input: ByteArray, offset: Int, length: Int): ShortArray {
            val out = ShortArray(length)
            for (i in 0 until length) {
                out[i] = ALAW_TABLE[input[offset + i].toInt() and 0xFF]
            }
            return out
        }

        private fun applyGain(samples: ShortArray): ShortArray {
            return ShortArray(samples.size) { i ->
                (samples[i] * GAIN).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    enum class AudioCodec { ULAW, ALAW, PCM16 }

    private var audioTrack:   AudioTrack? = null
    private var isPlaying     = false
    private var isPaused      = false
    private var audioThread:  Thread? = null
    private var currentCodec  = AudioCodec.ULAW

    private val audioQueue = ArrayDeque<ByteArray>()
    private val queueLock  = Object()

    fun initialize(sampleRate: Int = 8000, stereo: Boolean = false) {
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

            Log.d(TAG, "✅ AudioTrack: ${sampleRate}Hz | codec=$currentCodec")
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل: ${e.message}")
            audioTrack = null
        }
    }

    fun startPlayback(
        audioBlocks: List<ByteArray>,
        startIndex:  Int = 0,
        dataOffset:  Int = 8,
        codec:       AudioCodec = AudioCodec.ULAW
    ) {
        if (audioTrack == null) initialize()
        if (isPlaying) return

        currentCodec = codec
        isPlaying    = true
        isPaused     = false

        Log.d(TAG, "▶ تشغيل: ${audioBlocks.size} كتلة | codec=$codec | offset=$dataOffset")

        synchronized(queueLock) {
            audioQueue.clear()
            for (i in startIndex until audioBlocks.size) {
                audioQueue.addLast(audioBlocks[i])
            }
        }

        audioTrack?.play()
        audioThread = Thread { processQueue(dataOffset) }.also { it.start() }
    }

    private fun processQueue(dataOffset: Int) {
        while (isPlaying) {
            val block: ByteArray?
            synchronized(queueLock) {
                block = if (audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
            }

            if (block == null) { Thread.sleep(5); continue }

            while (isPaused && isPlaying) Thread.sleep(10)
            if (!isPlaying) break

            val offset = dataOffset.coerceIn(0, block.size - 1)
            val len    = block.size - offset
            if (len < 4) continue

            try {
                val pcmRaw: ShortArray = when (currentCodec) {
                    AudioCodec.ULAW  -> decodeUlaw(block, offset, len)
                    AudioCodec.ALAW  -> decodeAlaw(block, offset, len)
                    AudioCodec.PCM16 -> ShortArray(len / 2) { i ->
                        val lo = block[offset + i * 2].toInt()     and 0xFF
                        val hi = block[offset + i * 2 + 1].toInt() and 0xFF
                        ((hi shl 8) or lo).toShort()
                    }
                }
                val pcm = applyGain(pcmRaw)
                audioTrack?.write(pcm, 0, pcm.size)
            } catch (e: Exception) {
                Log.w(TAG, "خطأ: ${e.message}")
            }
        }
    }

    fun detectBestCodec(sampleBlocks: List<ByteArray>, dataOffset: Int): AudioCodec {
        if (sampleBlocks.isEmpty()) return AudioCodec.ULAW
        var ulawScore = 0
        var alawScore = 0
        var checked   = 0
        for (block in sampleBlocks.take(10)) {
            val off = dataOffset.coerceIn(0, block.size - 1)
            val end = (off + 200).coerceAtMost(block.size)
            for (i in off until end) {
                val v = block[i].toInt() and 0xFF
                if (v >= 0x80) ulawScore++
                if (v in 0x40..0xBF) alawScore++
                checked++
            }
        }
        Log.d(TAG, "codec detection: ulaw=$ulawScore alaw=$alawScore")
        return if (alawScore > ulawScore * 1.5) AudioCodec.ALAW else AudioCodec.ULAW
    }

    fun enqueueBlock(blockData: ByteArray) {
        synchronized(queueLock) { audioQueue.addLast(blockData) }
    }

    fun flushQueue() {
        synchronized(queueLock) { audioQueue.clear() }
        try { audioTrack?.flush() } catch (e: Exception) { }
    }

    fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        try { audioTrack?.pause() } catch (e: Exception) { }
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        try { audioTrack?.play() } catch (e: Exception) { }
    }

    fun stop() {
        isPlaying = false
        isPaused  = false
        audioThread?.interrupt()
        audioThread = null
        synchronized(queueLock) { audioQueue.clear() }
        try { audioTrack?.pause(); audioTrack?.flush() } catch (e: Exception) { }
    }

    fun release() {
        stop()
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) { }
        audioTrack = null
    }

    val isActive: Boolean get() = isPlaying && !isPaused
}

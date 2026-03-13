package com.lrec.operator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * مشغّل صوت بـ AudioTrack:
 * - خيط مستهلك منفصل مع قائمة تخزين مؤقت
 * - مزامنة مع timeline الفيديو
 * - flush فوري عند البحث (seek)
 * - يدعم G.711 μ-law / A-law / PCM عبر G711Codec
 */
class LrecAudioPlayer {

    companion object {
        private const val TAG = "LrecAudioPlayer"
    }

    enum class AudioCodec { ULAW, ALAW, PCM8, PCM16 }

    // ══════════════════════════════════════════════════════════════════
    //  الحالة الداخلية
    // ══════════════════════════════════════════════════════════════════

    private var audioTrack:   AudioTrack? = null
    private var isPlaying     = false
    private var isPaused      = false
    private var audioThread:  Thread? = null

    private var currentCodec      = AudioCodec.ULAW
    private var currentGain       = 3.0f
    private var currentSampleRate = 8000
    private var currentDataOffset = 8

    // ── قائمة الانتظار مع مزامنة ──────────────────────────────────────
    private val audioQueue   = ArrayDeque<ByteArray>()
    private val queueLock    = Object()

    // مزامنة مع الفيديو
    @Volatile private var videoTimeMs  = 0L
    @Volatile private var audioTimeMs  = 0L

    val codecName: String get() = when (currentCodec) {
        AudioCodec.ULAW  -> "G.711 μ-law"
        AudioCodec.ALAW  -> "G.711 A-law"
        AudioCodec.PCM8  -> "PCM 8-bit"
        AudioCodec.PCM16 -> "PCM 16-bit"
    }

    // ══════════════════════════════════════════════════════════════════
    //  تهيئة AudioTrack
    // ══════════════════════════════════════════════════════════════════

    fun initialize(sampleRate: Int = 8000, stereo: Boolean = false) {
        release()
        currentSampleRate = sampleRate

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

            Log.d(TAG, "✅ AudioTrack: ${sampleRate}Hz | $channelConfig")
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل: ${e.message}")
            audioTrack = null
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  تشغيل الصوت
    // ══════════════════════════════════════════════════════════════════

    fun startPlayback(
        audioBlocks: List<ByteArray>,
        startIndex:  Int        = 0,
        dataOffset:  Int        = 8,
        codec:       AudioCodec = AudioCodec.ULAW,
        gain:        Float      = 3.0f
    ) {
        if (audioTrack == null) initialize()
        if (isPlaying) return

        currentCodec      = codec
        currentGain       = gain
        currentDataOffset = dataOffset
        isPlaying         = true
        isPaused          = false
        audioTimeMs       = 0L

        Log.d(TAG, "▶ ${audioBlocks.size} كتلة | codec=$codecName | offset=$dataOffset")

        synchronized(queueLock) {
            audioQueue.clear()
            for (i in startIndex until audioBlocks.size) {
                audioQueue.addLast(audioBlocks[i])
            }
        }

        audioTrack?.play()
        audioThread = Thread { processQueue() }.also { it.start() }
    }

    private fun processQueue() {
        while (isPlaying) {
            // ── مزامنة مع الفيديو: انتظر إذا تقدّم الصوت ──────────────
            if (audioTimeMs > videoTimeMs + 200) {
                Thread.sleep(20)
                continue
            }

            val block: ByteArray?
            synchronized(queueLock) {
                block = if (audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
            }

            if (block == null) { Thread.sleep(5); continue }

            while (isPaused && isPlaying) Thread.sleep(10)
            if (!isPlaying) break

            val offset = currentDataOffset.coerceIn(0, block.size - 1)
            val len    = block.size - offset
            if (len < 4) continue

            try {
                val g711Codec = when (currentCodec) {
                    AudioCodec.ULAW  -> G711Codec.Codec.ULAW
                    AudioCodec.ALAW  -> G711Codec.Codec.ALAW
                    AudioCodec.PCM8  -> G711Codec.Codec.PCM8
                    AudioCodec.PCM16 -> G711Codec.Codec.PCM16
                }

                val pcm = G711Codec.decode(block, offset, len, g711Codec, currentGain)
                audioTrack?.write(pcm, 0, pcm.size)

                // تحديث وقت الصوت
                audioTimeMs += (len.toLong() * 1000L / currentSampleRate)

            } catch (e: Exception) {
                Log.w(TAG, "خطأ: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  اكتشاف الكودك
    // ══════════════════════════════════════════════════════════════════

    fun detectBestCodec(sampleBlocks: List<ByteArray>, dataOffset: Int): AudioCodec {
        if (sampleBlocks.isEmpty()) return AudioCodec.ULAW

        // جمّع عينات من أول 10 كتل
        val sampleData = mutableListOf<Byte>()
        for (block in sampleBlocks.take(10)) {
            val off = dataOffset.coerceIn(0, block.size - 1)
            val end = minOf(off + 200, block.size)
            for (i in off until end) sampleData.add(block[i])
        }

        if (sampleData.isEmpty()) return AudioCodec.ULAW

        val arr   = sampleData.toByteArray()
        val codec = G711Codec.detectCodec(arr)

        Log.d(TAG, "codec مكتشف: $codec")

        return when (codec) {
            G711Codec.Codec.ALAW  -> AudioCodec.ALAW
            G711Codec.Codec.PCM8  -> AudioCodec.PCM8
            G711Codec.Codec.PCM16 -> AudioCodec.PCM16
            else                  -> AudioCodec.ULAW
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  واجهة التحكم
    // ══════════════════════════════════════════════════════════════════

    /** تحديث وقت الفيديو للمزامنة */
    fun syncVideoTime(timeMs: Long) { videoTimeMs = timeMs }

    fun enqueueBlock(blockData: ByteArray) {
        synchronized(queueLock) { audioQueue.addLast(blockData) }
    }

    fun flushQueue() {
        synchronized(queueLock) { audioQueue.clear() }
        audioTimeMs = videoTimeMs
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

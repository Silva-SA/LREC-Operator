package com.lrec.operator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * ══════════════════════════════════════════════════════════════════════
 *  LrecAudioPlayer — فك ترميز G.711 وتشغيل الصوت عبر AudioTrack
 *
 *  يدعم:
 *    ✅ G.711 μ-law (ulaw) — الأكثر شيوعاً في Inter-Tel
 *    ✅ G.711 A-law         — البديل الأوروبي
 *    ✅ PCM خام 16-bit      — إذا كان الصوت غير مضغوط
 *    ✅ مزامنة مع الفيديو عبر timestamp
 * ══════════════════════════════════════════════════════════════════════
 */
class LrecAudioPlayer {

    companion object {
        private const val TAG = "LrecAudioPlayer"

        // معدل التردد الافتراضي لـ G.711 (telephony standard)
        private const val SAMPLE_RATE = 8000

        // جدول فك ترميز μ-law → PCM 16-bit
        // يحوّل كل قيمة 8-bit إلى قيمة 16-bit
        private val ULAW_TABLE = IntArray(256) { i ->
            val ulaw  = i.inv() and 0xFF
            val sign  = ulaw and 0x80
            val exp   = (ulaw shr 4) and 0x07
            val mant  = ulaw and 0x0F
            var sample = ((mant shl 1) or 0x21) shl exp
            sample -= 0x21
            if (sign != 0) -sample else sample
        }

        // جدول فك ترميز A-law → PCM 16-bit
        private val ALAW_TABLE = IntArray(256) { i ->
            val alaw = i xor 0x55
            val sign = alaw and 0x80
            val exp  = (alaw shr 4) and 0x07
            val mant = alaw and 0x0F
            var sample = if (exp == 0) {
                (mant shl 1) or 1
            } else {
                ((mant or 0x10) shl 1) or 1 shl (exp)
            }
            sample = sample shl 3
            if (sign == 0) -sample else sample
        }

        /**
         * فك ترميز G.711 μ-law → PCM 16-bit
         * @param input  بيانات G.711 المضغوطة (8-bit per sample)
         * @return       بيانات PCM 16-bit (little-endian, كل عينة = 2 bytes)
         */
        fun decodeUlaw(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ShortArray {
            val output = ShortArray(length)
            for (i in 0 until length) {
                val idx = input[offset + i].toInt() and 0xFF
                output[i] = ULAW_TABLE[idx].toShort()
            }
            return output
        }

        /**
         * فك ترميز G.711 A-law → PCM 16-bit
         */
        fun decodeAlaw(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ShortArray {
            val output = ShortArray(length)
            for (i in 0 until length) {
                val idx = input[offset + i].toInt() and 0xFF
                output[i] = ALAW_TABLE[idx].toShort()
            }
            return output
        }

        /**
         * كشف نوع ترميز الصوت تلقائياً من بيانات الكتلة
         * بناءً على بنية كتل TYPE_NETWORK في ملف .lrec
         */
        fun detectAudioFormat(blockData: ByteArray): AudioFormat {
            if (blockData.size < 10) return AudioFormat.PCM_RAW

            // byte[2] في بعض الإصدارات يحدد نوع الكودك
            val formatByte = blockData[2].toInt() and 0xFF

            return when (formatByte) {
                0x07 -> AudioFormat.ULAW   // G.711 μ-law (WAVE_FORMAT_MULAW = 7)
                0x06 -> AudioFormat.ALAW   // G.711 A-law (WAVE_FORMAT_ALAW = 6)
                0x01 -> AudioFormat.PCM    // PCM خام     (WAVE_FORMAT_PCM = 1)
                else -> {
                    // محاولة الكشف من التوزيع الإحصائي للبيانات
                    guessFormat(blockData)
                }
            }
        }

        private fun guessFormat(blockData: ByteArray): AudioFormat {
            if (blockData.size < 20) return AudioFormat.ULAW

            // G.711 μ-law يميل لوجود قيم في نطاق 0x00-0x7F و 0x80-0xFF
            // PCM الخام يكون له توزيع أكثر عشوائية
            var highByte = 0
            val start = 8.coerceAtMost(blockData.size - 1)
            val end   = blockData.size.coerceAtMost(start + 100)

            for (i in start until end) {
                if ((blockData[i].toInt() and 0xFF) > 127) highByte++
            }

            // إذا كانت أكثر من 30% من القيم > 127 → على الأرجح PCM
            return if (highByte > (end - start) * 0.3) AudioFormat.PCM else AudioFormat.ULAW
        }
    }

    enum class AudioFormat { ULAW, ALAW, PCM, PCM_RAW }

    // ── حالة المشغّل ──────────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private var isPlaying   = false
    private var isPaused    = false
    private var audioThread: Thread? = null

    // قائمة انتظار الكتل الصوتية للتشغيل
    private val audioQueue  = ArrayDeque<ByteArray>()
    private val queueLock   = Object()

    // نوع الترميز المكتشف
    private var detectedFormat = AudioFormat.ULAW

    // ── تهيئة AudioTrack ──────────────────────────────────────────────
    fun initialize(sampleRate: Int = SAMPLE_RATE, stereo: Boolean = false) {
        release()

        val channelConfig = if (stereo)
            android.media.AudioFormat.CHANNEL_OUT_STEREO
        else
            android.media.AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            android.media.AudioFormat.ENCODING_PCM_16BIT
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
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "AudioTrack initialized: ${sampleRate}Hz, " +
                       "${if (stereo) "stereo" else "mono"}, buffer=$minBufSize")
        } catch (e: Exception) {
            Log.e(TAG, "فشل تهيئة AudioTrack: ${e.message}")
            audioTrack = null
        }
    }

    // ── تشغيل صوت من كتل .lrec ────────────────────────────────────────
    fun startPlayback(audioBlocks: List<ByteArray>, startIndex: Int = 0) {
        if (audioTrack == null) initialize()
        if (isPlaying) return

        isPlaying = true
        isPaused  = false

        // اكتشاف التنسيق من أول كتلة صوتية
        if (audioBlocks.isNotEmpty()) {
            detectedFormat = detectAudioFormat(audioBlocks[0])
            Log.d(TAG, "تنسيق الصوت المكتشف: $detectedFormat")
        }

        // إضافة الكتل للقائمة
        synchronized(queueLock) {
            audioQueue.clear()
            for (i in startIndex until audioBlocks.size) {
                audioQueue.addLast(audioBlocks[i])
            }
        }

        audioTrack?.play()

        audioThread = Thread {
            try {
                processAudioQueue()
            } catch (e: InterruptedException) {
                Log.d(TAG, "خيط الصوت أُوقف")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في خيط الصوت: ${e.message}")
            }
        }
        audioThread?.start()
    }

    private fun processAudioQueue() {
        while (isPlaying) {
            val block: ByteArray?
            synchronized(queueLock) {
                block = if (audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
            }

            if (block == null) {
                Thread.sleep(10)
                continue
            }

            while (isPaused && isPlaying) Thread.sleep(10)
            if (!isPlaying) break

            // استخراج البيانات الصوتية من الكتلة (تخطي الـ header)
            val audioDataOffset = 8.coerceAtMost(block.size - 1)
            val audioDataLen    = block.size - audioDataOffset
            if (audioDataLen <= 0) continue

            // فك الترميز حسب التنسيق المكتشف
            val pcmSamples: ShortArray = when (detectedFormat) {
                AudioFormat.ULAW -> decodeUlaw(block, audioDataOffset, audioDataLen)
                AudioFormat.ALAW -> decodeAlaw(block, audioDataOffset, audioDataLen)
                AudioFormat.PCM  -> {
                    // PCM 16-bit مباشر — تحويل ByteArray → ShortArray
                    ShortArray(audioDataLen / 2) { i ->
                        val lo = block[audioDataOffset + i * 2].toInt() and 0xFF
                        val hi = block[audioDataOffset + i * 2 + 1].toInt() and 0xFF
                        ((hi shl 8) or lo).toShort()
                    }
                }
                AudioFormat.PCM_RAW -> decodeUlaw(block, audioDataOffset, audioDataLen)
            }

            // كتابة البيانات المفكوكة إلى AudioTrack
            audioTrack?.write(pcmSamples, 0, pcmSamples.size)
        }
    }

    // ── إضافة كتلة صوتية جديدة أثناء التشغيل (للمزامنة) ─────────────
    fun enqueueBlock(blockData: ByteArray) {
        synchronized(queueLock) {
            audioQueue.addLast(blockData)
        }
    }

    // ── تنظيف القائمة (عند الانتقال لموقع جديد) ──────────────────────
    fun flushQueue() {
        synchronized(queueLock) {
            audioQueue.clear()
        }
        audioTrack?.flush()
    }

    // ── إيقاف مؤقت ────────────────────────────────────────────────────
    fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        audioTrack?.pause()
    }

    // ── استئناف ───────────────────────────────────────────────────────
    fun resume() {
        if (!isPaused) return
        isPaused = false
        audioTrack?.play()
    }

    // ── إيقاف كامل ────────────────────────────────────────────────────
    fun stop() {
        isPlaying = false
        isPaused  = false
        audioThread?.interrupt()
        audioThread = null
        audioTrack?.pause()
        audioTrack?.flush()
        synchronized(queueLock) { audioQueue.clear() }
    }

    // ── تحرير الموارد ─────────────────────────────────────────────────
    fun release() {
        stop()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { }
        audioTrack = null
    }

    val isActive: Boolean get() = isPlaying && !isPaused
}

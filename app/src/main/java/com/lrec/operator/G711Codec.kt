package com.lrec.operator

/**
 * محوّل G.711 نقي بـ Kotlin — بدون أي مكتبة خارجية
 * يدعم: μ-law، A-law، PCM 8-bit unsigned → 16-bit signed
 */
object G711Codec {

    // ══════════════════════════════════════════════════════════════════
    //  جداول Lookup مُحسَبة مسبقاً (256 قيمة لكل منهما)
    // ══════════════════════════════════════════════════════════════════

    /** G.711 μ-law → PCM 16-bit (ITU-T G.711) */
    val ULAW_TABLE: ShortArray = ShortArray(256) { i ->
        val ulaw  = (i xor 0xFF) and 0xFF
        val sign  = ulaw and 0x80
        val exp   = (ulaw shr 4) and 0x07
        val mant  = ulaw and 0x0F
        var value = ((mant + 33) shl (exp + 2)) - 132
        value = if (sign != 0) -value else value
        value.coerceIn(-32768, 32767).toShort()
    }

    /** G.711 A-law → PCM 16-bit (ITU-T G.711) */
    val ALAW_TABLE: ShortArray = ShortArray(256) { i ->
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

    // ══════════════════════════════════════════════════════════════════
    //  دوال فك الترميز
    // ══════════════════════════════════════════════════════════════════

    /** فك ترميز G.711 μ-law */
    fun decodeUlaw(
        input:  ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
        gain:   Float = 3.0f
    ): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            val raw = ULAW_TABLE[input[offset + i].toInt() and 0xFF].toInt()
            out[i]  = (raw * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** فك ترميز G.711 A-law */
    fun decodeAlaw(
        input:  ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
        gain:   Float = 3.0f
    ): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            val raw = ALAW_TABLE[input[offset + i].toInt() and 0xFF].toInt()
            out[i]  = (raw * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** PCM 8-bit unsigned → PCM 16-bit signed */
    fun decodePcm8(
        input:  ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
        gain:   Float = 1.0f
    ): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            val unsigned = input[offset + i].toInt() and 0xFF
            val signed   = (unsigned - 128) * 256  // 8-bit → 16-bit
            out[i] = (signed * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** PCM 16-bit little-endian (نسخ مباشر) */
    fun decodePcm16Le(
        input:  ByteArray,
        offset: Int = 0,
        length: Int = (input.size - offset) / 2
    ): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            val lo = input[offset + i * 2].toInt()     and 0xFF
            val hi = input[offset + i * 2 + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    // ══════════════════════════════════════════════════════════════════
    //  اكتشاف الكودك تلقائياً من توزيع القيم
    // ══════════════════════════════════════════════════════════════════

    enum class Codec { ULAW, ALAW, PCM8, PCM16 }

    /**
     * يحلّل عينة من البيانات ويحدد الكودك الأرجح:
     * - μ-law: صمت = 0xFF، تتمركز القيم قرب 0x80-0xFF
     * - A-law:  صمت ≈ 0xD5، توزيع أكثر اتساعاً
     * - PCM8:   توزيع عشوائي حول 128 (0x80)
     */
    fun detectCodec(data: ByteArray, offset: Int = 0, sampleSize: Int = 256): Codec {
        val end = minOf(offset + sampleSize, data.size)
        if (end - offset < 16) return Codec.ULAW

        var ulawScore = 0
        var alawScore = 0
        var pcmScore  = 0
        var count     = 0

        for (i in offset until end) {
            val v = data[i].toInt() and 0xFF
            // μ-law: القيم العالية شائعة (صمت = 0xFF)
            if (v >= 0xD0 || v <= 0x2F) ulawScore++
            // A-law: القيم المتوسطة شائعة
            if (v in 0x40..0xBF) alawScore++
            // PCM: توزيع حول 128
            if (v in 0x60..0xA0) pcmScore++
            count++
        }

        if (count == 0) return Codec.ULAW

        return when {
            alawScore > ulawScore * 1.5 && alawScore > pcmScore -> Codec.ALAW
            pcmScore  > ulawScore * 1.3 && pcmScore  > alawScore -> Codec.PCM8
            else -> Codec.ULAW
        }
    }

    /** فك الترميز تلقائياً بناءً على الكودك المكتشف */
    fun decode(
        data:   ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        codec:  Codec = Codec.ULAW,
        gain:   Float = 3.0f
    ): ShortArray = when (codec) {
        Codec.ULAW  -> decodeUlaw(data, offset, length, gain)
        Codec.ALAW  -> decodeAlaw(data, offset, length, gain)
        Codec.PCM8  -> decodePcm8(data, offset, length, gain)
        Codec.PCM16 -> decodePcm16Le(data, offset, length / 2)
    }
}

package com.lrec.operator

import java.io.File
import java.io.RandomAccessFile

/**
 * يكتشف تلقائياً صيغة ملف .lrec:
 * - Block: كتل 0x10/0x04  (Bitmap 8-bit indexed + zlib)
 * - AVI:   RIFF/AVI container (H.263 + PCM/G.711)
 */
object LrecFormatDetector {

    enum class Format { BLOCK, AVI, UNKNOWN }

    fun detect(file: File): Format {
        if (!file.exists() || file.length() < 16) return Format.UNKNOWN
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(16)
                raf.read(header)
                detectFromBytes(header)
            }
        } catch (e: Exception) {
            Format.UNKNOWN
        }
    }

    fun detectFromBytes(header: ByteArray): Format {
        if (header.size < 4) return Format.UNKNOWN

        // AVI: يبدأ بـ "RIFF" ثم بعد 4 bytes يأتي "AVI "
        val isRiff = header[0] == 'R'.code.toByte() &&
                     header[1] == 'I'.code.toByte() &&
                     header[2] == 'F'.code.toByte() &&
                     header[3] == 'F'.code.toByte()

        if (isRiff && header.size >= 12) {
            val isAvi = header[8]  == 'A'.code.toByte() &&
                        header[9]  == 'V'.code.toByte() &&
                        header[10] == 'I'.code.toByte() &&
                        header[11] == ' '.code.toByte()
            if (isAvi) return Format.AVI
        }

        // Block: يبدأ بـ 8 أصفار ثم 0x10 0x04
        val allZeros = (0 until 8).all { header.getOrElse(it) { 1 } == 0.toByte() }
        if (allZeros) return Format.BLOCK

        // Fallback: ابحث عن 0x10 0x04 في أول 64 byte
        for (i in 0 until minOf(header.size - 1, 64)) {
            if ((header[i].toInt() and 0xFF) == 0x10 &&
                (header[i + 1].toInt() and 0xFF) == 0x04) {
                return Format.BLOCK
            }
        }

        return Format.UNKNOWN
    }
}

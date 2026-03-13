package com.lrec.operator

import android.util.Log

/**
 * مدير الدردشة المزامَن:
 * - فك Base64 مع fallback لـ UTF-8 المباشر
 * - يدعم العربية والإنجليزية
 * - getNewMessages(currentMs) للمزامنة مع التشغيل
 */
class LrecChatManager {

    companion object {
        private const val TAG = "LrecChatManager"
    }

    data class ChatMessage(
        val timestampMs: Long,
        val sender:      String,
        val text:        String
    ) {
        val formattedTime: String get() {
            val s   = timestampMs / 1000
            val h   = s / 3600
            val m   = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
            else              String.format("%02d:%02d", m, sec)
        }
        // للعرض: "[HH:MM:SS] المرسل: النص"
        val formatted: String get() = "[$formattedTime] $sender: $text"
    }

    private val _messages    = mutableListOf<ChatMessage>()
    private var _lastSentIdx = -1

    val allMessages: List<ChatMessage> get() = _messages.toList()
    val messageCount: Int get() = _messages.size

    // ══════════════════════════════════════════════════════════════════
    //  استيعاب الرسائل من LrecParser
    // ══════════════════════════════════════════════════════════════════

    fun loadFromChatEntries(entries: List<LrecParser.ChatEntry>) {
        _messages.clear()
        _lastSentIdx = -1
        entries.forEach { entry ->
            _messages.add(ChatMessage(
                timestampMs = entry.timestampMs,
                sender      = entry.sender,
                text        = entry.message
            ))
        }
        Log.d(TAG, "✅ تم تحميل ${_messages.size} رسالة")
    }

    // ══════════════════════════════════════════════════════════════════
    //  فك الترميز من بيانات خام
    // ══════════════════════════════════════════════════════════════════

    fun addRawBlock(blockData: ByteArray, timestampMs: Long) {
        val msg = decodeBlock(blockData, timestampMs) ?: return
        _messages.add(msg)
    }

    private fun decodeBlock(blockData: ByteArray, timestampMs: Long): ChatMessage? {
        if (blockData.size < 4) return null

        val offsets = listOf(4, 6, 8, 10, 12)

        // محاولة 1: UTF-8 مباشر (يدعم العربية)
        for (offset in offsets) {
            if (offset >= blockData.size) continue
            try {
                val text = String(blockData, offset, blockData.size - offset, Charsets.UTF_8).trim()
                if (isValidChatText(text)) {
                    return parseMessage(text, timestampMs)
                }
            } catch (e: Exception) { }
        }

        // محاولة 2: UTF-16LE (Windows)
        for (offset in offsets) {
            val len = blockData.size - offset
            if (offset >= blockData.size || len < 4 || len % 2 != 0) continue
            try {
                val text = String(blockData, offset, len, Charsets.UTF_16LE).trim()
                if (isValidChatText(text)) {
                    return parseMessage(text, timestampMs)
                }
            } catch (e: Exception) { }
        }

        // محاولة 3: Base64 → UTF-8
        for (offset in offsets) {
            if (offset >= blockData.size) continue
            try {
                val rawStr = String(blockData, offset, blockData.size - offset, Charsets.ISO_8859_1)
                val match  = Regex("[A-Za-z0-9+/]{12,}={0,2}").find(rawStr) ?: continue
                val decoded = android.util.Base64.decode(match.value, android.util.Base64.DEFAULT)
                val text    = String(decoded, Charsets.UTF_8).trim()
                if (isValidChatText(text)) {
                    return parseMessage(text, timestampMs)
                }
            } catch (e: Exception) { }
        }

        // محاولة 4: تحليل "[HH:MM:SS] sender: message"
        for (offset in listOf(0, 4, 6)) {
            if (offset >= blockData.size) continue
            try {
                val raw = String(blockData, offset, blockData.size - offset, Charsets.UTF_8).trim()
                val msg = parseTimestampedFormat(raw, timestampMs)
                if (msg != null) return msg
            } catch (e: Exception) { }
        }

        return null
    }

    /**
     * تحليل صيغة: "[HH:MM:SS] المرسل: النص"
     */
    private fun parseTimestampedFormat(text: String, fallbackMs: Long): ChatMessage? {
        val regex = Regex("""^\[(\d{1,2}:\d{2}:\d{2})\]\s*(.+?):\s*(.+)$""")
        val match = regex.find(text.trim()) ?: return null

        val timeStr = match.groupValues[1]
        val sender  = match.groupValues[2].trim()
        val message = match.groupValues[3].trim()

        if (!isValidChatText(message)) return null

        val parts = timeStr.split(":")
        val tsMs  = if (parts.size == 3) {
            val h = parts[0].toLongOrNull() ?: 0
            val m = parts[1].toLongOrNull() ?: 0
            val s = parts[2].toLongOrNull() ?: 0
            (h * 3600 + m * 60 + s) * 1000L
        } else fallbackMs

        return ChatMessage(tsMs, sender, message)
    }

    private fun parseMessage(text: String, timestampMs: Long): ChatMessage {
        // جرّب "[HH:MM:SS] sender: msg" أولاً
        parseTimestampedFormat(text, timestampMs)?.let { return it }

        // "sender: message"
        val colonIdx = text.indexOf(':')
        return if (colonIdx in 1..40 && colonIdx + 1 < text.length) {
            val sender  = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            if (sender.isNotBlank() && message.isNotBlank() && !sender.contains('\n'))
                ChatMessage(timestampMs, sender, message)
            else
                ChatMessage(timestampMs, "مشارك", text)
        } else {
            ChatMessage(timestampMs, "مشارك", text)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  واجهة المزامنة مع التشغيل
    // ══════════════════════════════════════════════════════════════════

    /**
     * يُعيد الرسائل الجديدة التي ظهرت حتى currentMs
     * (للعرض التدريجي أثناء التشغيل)
     */
    fun getNewMessages(currentMs: Long): List<ChatMessage> {
        val newMessages = mutableListOf<ChatMessage>()
        for (i in (_lastSentIdx + 1) until _messages.size) {
            if (_messages[i].timestampMs <= currentMs) {
                newMessages.add(_messages[i])
                _lastSentIdx = i
            } else break
        }
        return newMessages
    }

    /** جميع الرسائل حتى وقت معين */
    fun getMessagesUpTo(timeMs: Long): List<ChatMessage> =
        _messages.filter { it.timestampMs <= timeMs }

    /** الرسالة النشطة الآن */
    fun getActiveMessage(timeMs: Long): ChatMessage? =
        _messages.lastOrNull { it.timestampMs <= timeMs }

    /** إعادة ضبط المؤشر (عند الـ seek) */
    fun resetCursor(timeMs: Long) {
        _lastSentIdx = _messages.indexOfLast { it.timestampMs <= timeMs }
    }

    // ══════════════════════════════════════════════════════════════════
    //  التحقق من النص
    // ══════════════════════════════════════════════════════════════════

    private fun isValidChatText(text: String): Boolean {
        if (text.length < 2 || text.length > 1000) return false

        var validCount = 0
        for (c in text) {
            when {
                c.code in 32..126         -> validCount++ // ASCII
                c.code in 0x0600..0x06FF  -> validCount++ // عربية
                c.code in 0x0750..0x077F  -> validCount++ // ملحق عربي
                c.code in 0xFB50..0xFDFF  -> validCount++ // عربية أ
                c.code in 0xFE70..0xFEFF  -> validCount++ // عربية ب
                c.isWhitespace()          -> validCount++
                c.isLetterOrDigit()       -> validCount++
            }
        }

        if (validCount.toFloat() / text.length < 0.70f) return false
        if (!text.any { it.isLetter() }) return false
        return text.toSet().size >= 2
    }
}

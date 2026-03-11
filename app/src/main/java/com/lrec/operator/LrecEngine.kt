package com.lrec.operator

/**
 * LrecEngine — كان يستخدم مكتبة C++ (liblrec_engine.so) لفك ضغط ملفات .lrec
 *
 * ⚠️  هذا الملف غير مُستخدَم الآن.
 *    تم استبداله بـ LrecParser.kt (تنفيذ Kotlin خالص أكثر موثوقية)
 *    التنفيذ الجديد في LrecPlayerActivity.kt يستخدم LrecParser مباشرةً.
 *
 *    المكتبة الأصلية C++ (lrec_engine.cpp) كانت stub فارغة تُعيد بيانات وهمية.
 */
object LrecEngine {

    // ── تم تعطيل تحميل المكتبة C++ لمنع UnsatisfiedLinkError ──────
    //   init { System.loadLibrary("lrec_engine") }

    // ── الدوال التالية غير مستخدمة (محفوظة للتوافق فقط) ──────────
    @Deprecated("Use LrecParser.kt instead")
    fun open(path: String): Long = 0L

    @Deprecated("Use LrecParser.kt instead")
    fun close(handle: Long) { }

    @Deprecated("Use LrecParser.kt instead")
    fun getWidth(handle: Long): Int = 0

    @Deprecated("Use LrecParser.kt instead")
    fun getHeight(handle: Long): Int = 0

    @Deprecated("Use LrecParser.kt instead")
    fun getTotalFrames(handle: Long): Int = 0

    @Deprecated("Use LrecParser.kt instead")
    fun decodeFrameNative(handle: Long, frame: Int): ByteArray = ByteArray(0)
}

package com.lrec.operator

import android.util.Log

/**
 * ══════════════════════════════════════════════════════════════════
 *  LrecEngine — جسر Kotlin لمكتبة liblrec_engine.so الخاصة بنا
 *
 *  المكتبة تُنفِّذ:
 *    • قراءة وتحليل ملفات .lrec بالكامل في C++
 *    • فك ضغط zlib لكل إطار (أسرع بكثير من Java)
 *    • إدارة حالة الشاشة (Full + Delta frames)
 *    • بحث ثنائي سريع للانتقال الزمني
 *
 *  الاستخدام:
 *    val handle = LrecEngine.nativeOpen(filePath)  // > 0 = نجاح
 *    LrecEngine.nativeRenderFrame(handle, idx, pixels)
 *    LrecEngine.nativeClose(handle)
 * ══════════════════════════════════════════════════════════════════
 */
object LrecEngine {

    private const val TAG = "LrecEngine"

    /** هل المكتبة محمَّلة وجاهزة؟ */
    @Volatile
    var isAvailable: Boolean = false
        private set

    init {
        isAvailable = try {
            System.loadLibrary("lrec_engine")
            Log.i(TAG, "✅ liblrec_engine.so محمَّلة")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ فشل تحميل liblrec_engine.so: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ خطأ أمني: ${e.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  الدوال الأصلية
    // ════════════════════════════════════════════════════════════════

    /**
     * فتح ملف .lrec وتحليله كاملاً.
     * @param filePath المسار الكامل على نظام الملفات (يجب أن يكون مسار حقيقي)
     * @return handle > 0 عند النجاح، 0 عند الفشل
     */
    external fun nativeOpen(filePath: String): Long

    /** عدد الإطارات الإجمالي */
    external fun nativeGetFrameCount(handle: Long): Int

    /** عرض الشاشة بالبكسل */
    external fun nativeGetScreenWidth(handle: Long): Int

    /** ارتفاع الشاشة بالبكسل */
    external fun nativeGetScreenHeight(handle: Long): Int

    /** المدة الكاملة للتسجيل بالميلي-ثانية */
    external fun nativeGetDurationMs(handle: Long): Long

    /** الوقت الزمني للإطار [idx] بالميلي-ثانية */
    external fun nativeGetFrameTimestamp(handle: Long, frameIdx: Int): Long

    /**
     * رسم الإطار وكتابة pixels ARGB في outPixels.
     * يُعيد true عند النجاح.
     * يُدير حالة الشاشة داخلياً (Full + Delta).
     * @param outPixels IntArray بحجم (width × height) على الأقل
     */
    external fun nativeRenderFrame(handle: Long, frameIdx: Int, outPixels: IntArray): Boolean

    /**
     * البحث السريع عن أقرب إطار للوقت المطلوب.
     * @return index الإطار (0..frameCount-1)
     */
    external fun nativeFindFrameAtMs(handle: Long, targetMs: Long): Int

    /**
     * إغلاق الملف وتحرير كل الموارد.
     * يجب استدعاؤها دائماً عند الانتهاء.
     */
    external fun nativeClose(handle: Long)
}

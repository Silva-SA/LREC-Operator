package com.lrec.operator

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ══════════════════════════════════════════════════════════════════
 *  LrecPlayerActivity — مشغِّل ملفات .lrec
 *
 *  يعتمد على مكتبتنا الأصلية liblrec_engine.so كلياً.
 *
 *  المعمارية:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ Load Thread  → نسخ content:// URI + تهيئة المحرك      │
 *   │ Decode Thread → فك الضغط + تحديث bitmap في الخلفية    │
 *   │ UI Thread    → عرض bitmap + تحديث شريط التقدم         │
 *   └─────────────────────────────────────────────────────────┘
 *
 *  مصادر الملف المدعومة:
 *   • content:// URI  (من VideoLibraryActivity أو مدير الملفات)
 *   • file:// URI     (مسار مباشر)
 *   • file_name extra (مسار نصي — للتوافق مع الكود القديم)
 *
 *  ملاحظة: الصوت غير متاح — بيانات الصوت في .lrec مشفرة
 *  بمفتاح CastEncrypt الخاص بـ Inter-Tel Collaboration Client.
 * ══════════════════════════════════════════════════════════════════
 */
class LrecPlayerActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────
    private lateinit var imageViewScreen : ImageView
    private lateinit var seekBar         : SeekBar
    private lateinit var btnPlay         : ImageButton
    private lateinit var btnPause        : ImageButton
    private lateinit var btnStop         : ImageButton
    private lateinit var btnForward      : ImageButton
    private lateinit var btnRewind       : ImageButton
    private lateinit var btnBack         : ImageButton
    private lateinit var tvCurrentTime   : TextView
    private lateinit var tvTotalTime     : TextView
    private lateinit var tvFrameInfo     : TextView
    private lateinit var tvAudioStatus   : TextView
    private lateinit var tvLoadingMsg    : TextView
    private lateinit var layoutLoading   : View

    // ── محرك .lrec ─────────────────────────────────────────────────
    private var engineHandle  : Long    = 0L
    private var totalFrames   : Int     = 0
    private var screenWidth   : Int     = 0
    private var screenHeight  : Int     = 0
    private var durationMs    : Long    = 0L

    // ── bitmap وبيانات البكسل ───────────────────────────────────────
    private var screenBitmap  : Bitmap? = null
    private var pixelBuffer   : IntArray? = null  // buffer ARGB مشترك

    // ── حالة التشغيل (thread-safe) ────────────────────────────────
    private val isPlaying      = AtomicBoolean(false)
    private val currentFrameIdx= AtomicInteger(0)
    private var isReadyToPlay  = false
    private var isSeeking      = false     // UI thread فقط

    // ── الخيوط ────────────────────────────────────────────────────
    private val uiHandler  = Handler(Looper.getMainLooper())
    private var loadThread : Thread? = null
    private var decThread  : Thread? = null

    // ── ملف الـ cache المؤقت (للـ content:// URIs) ────────────────
    private var cachedFile : File? = null

    companion object {
        private const val SEEK_STEP_MS  = 10_000L   // ±10 ثوانٍ
        private const val SEEK_BAR_MAX  = 1000       // دقة شريط التقدم
        private const val COPY_BUF_SIZE = 65_536     // 64KB لنسخ الملفات
    }

    // ════════════════════════════════════════════════════════════════
    //  دورة الحياة
    // ════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lrec_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        bindViews()
        setupControls()
        handleIntent()
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 1. إيقاف الخيوط
        isPlaying.set(false)
        decThread?.interrupt(); decThread = null
        loadThread?.interrupt(); loadThread = null

        // 2. تحرير المحرك الأصلي
        if (engineHandle != 0L && LrecEngine.isAvailable) {
            try { LrecEngine.nativeClose(engineHandle) } catch (_: Exception) {}
            engineHandle = 0L
        }

        // 3. تحرير الذاكرة
        screenBitmap?.recycle()
        screenBitmap  = null
        pixelBuffer   = null

        // 4. حذف ملف الـ cache
        cachedFile?.delete()
        cachedFile = null

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        uiHandler.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = super.onBackPressed()

    // ════════════════════════════════════════════════════════════════
    //  إعداد الواجهة
    // ════════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN         or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION    or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY   or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE      or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun bindViews() {
        imageViewScreen = findViewById(R.id.imageViewScreen)
        seekBar         = findViewById(R.id.seekBar)
        btnPlay         = findViewById(R.id.btnPlay)
        btnPause        = findViewById(R.id.btnPause)
        btnStop         = findViewById(R.id.btnStop)
        btnForward      = findViewById(R.id.btnForward)
        btnRewind       = findViewById(R.id.btnRewind)
        btnBack         = findViewById(R.id.btnBack)
        tvCurrentTime   = findViewById(R.id.tvCurrentTime)
        tvTotalTime     = findViewById(R.id.tvTotalTime)
        tvFrameInfo     = findViewById(R.id.tvFrameInfo)
        tvAudioStatus   = findViewById(R.id.tvAudioStatus)
        tvLoadingMsg    = findViewById(R.id.tvLoadingMsg)
        layoutLoading   = findViewById(R.id.layoutLoading)
    }

    private fun setupControls() {
        btnBack.setOnClickListener    { finish() }
        btnPlay.setOnClickListener    { startPlayback() }
        btnPause.setOnClickListener   { pausePlayback() }
        btnStop.setOnClickListener    { stopPlayback() }
        btnForward.setOnClickListener { seekBy(+SEEK_STEP_MS) }
        btnRewind.setOnClickListener  { seekBy(-SEEK_STEP_MS) }

        seekBar.max = SEEK_BAR_MAX
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && isReadyToPlay && durationMs > 0) {
                    val targetMs = progress.toLong() * durationMs / SEEK_BAR_MAX
                    doSeekToMs(targetMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar)  { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar)   { isSeeking = false }
        })

        setControlsEnabled(false)
    }

    // ════════════════════════════════════════════════════════════════
    //  معالجة Intent الوارد
    // ════════════════════════════════════════════════════════════════

    private fun handleIntent() {
        // التحقق أن المحرك الأصلي موجود
        if (!LrecEngine.isAvailable) {
            showError("فشل تحميل محرك .lrec\nلم يتم بناء المكتبة الأصلية بشكل صحيح")
            return
        }

        // أولوية 1: intent.data = URI (من VideoLibraryActivity أو مدير الملفات)
        val uri: Uri? = intent?.data
        if (uri != null) {
            when (uri.scheme?.lowercase()) {
                "content" -> openFromContentUri(uri)
                "file"    -> openFromFileUri(uri)
                else      -> openFromContentUri(uri)
            }
            return
        }

        // أولوية 2: file_name extra (للتوافق مع الكود القديم)
        val fileName = intent?.getStringExtra("file_name")
        if (!fileName.isNullOrBlank()) {
            val f = findLocalFile(fileName)
            if (f != null) beginLoad(f)
            else showError("لا يمكن إيجاد الملف:\n$fileName")
            return
        }

        showError("لم يتم تمرير ملف للتشغيل")
    }

    // ── فتح من content:// URI ─────────────────────────────────────
    private fun openFromContentUri(uri: Uri) {
        setLoadingMessage("جارٍ فتح الملف…")
        loadThread = Thread {
            try {
                val localFile = copyUriToCache(uri)
                if (localFile == null) {
                    uiHandler.post { showError("تعذّر قراءة الملف من مدير الملفات") }
                    return@Thread
                }
                cachedFile = localFile
                uiHandler.post { beginLoad(localFile) }
            } catch (e: InterruptedException) {
                // إلغاء طبيعي
            } catch (e: Exception) {
                uiHandler.post { showError("خطأ: ${e.message}") }
            }
        }.also { it.isDaemon = true; it.name = "LrecCopy"; it.start() }
    }

    // ── فتح من file:// URI ────────────────────────────────────────
    private fun openFromFileUri(uri: Uri) {
        val path = uri.path
        if (path.isNullOrBlank()) { showError("مسار الملف غير صالح"); return }
        val f = File(path)
        if (!f.exists()) { showError("الملف غير موجود:\n$path"); return }
        beginLoad(f)
    }

    // ── نسخ URI إلى ملف cache محلي ───────────────────────────────
    private fun copyUriToCache(uri: Uri): File? = try {
        val out = File(cacheDir, "lrec_${System.currentTimeMillis()}.lrec")
        contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(out).use { inp.copyTo(it, COPY_BUF_SIZE) }
        }
        if (out.exists() && out.length() > 0L) out else null
    } catch (_: Exception) { null }

    // ── البحث عن ملف في مسارات التطبيق ──────────────────────────
    private fun findLocalFile(path: String): File? {
        File(path).let { if (it.exists()) return it }
        listOfNotNull(filesDir, getExternalFilesDir(null), cacheDir).forEach { dir ->
            File(dir, path).let { if (it.exists()) return it }
            File(dir, File(path).name).let { if (it.exists()) return it }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    //  تهيئة المحرك وتحميل الملف
    // ════════════════════════════════════════════════════════════════

    private fun beginLoad(file: File) {
        setLoadingMessage("جارٍ تحليل الملف…")
        loadThread?.interrupt()

        loadThread = Thread {
            try {
                if (Thread.currentThread().isInterrupted) return@Thread

                // ── فتح الملف بالمحرك الأصلي ───────────────────
                val handle = LrecEngine.nativeOpen(file.absolutePath)

                if (Thread.currentThread().isInterrupted) {
                    if (handle != 0L) LrecEngine.nativeClose(handle)
                    return@Thread
                }

                if (handle == 0L) {
                    uiHandler.post {
                        showError("فشل فتح الملف.\n" +
                                "تأكد أن الملف بتنسيق .lrec صحيح وغير تالف.")
                    }
                    return@Thread
                }

                // ── استرجاع معلومات الملف ───────────────────────
                val w      = LrecEngine.nativeGetScreenWidth(handle)
                val h      = LrecEngine.nativeGetScreenHeight(handle)
                val count  = LrecEngine.nativeGetFrameCount(handle)
                val dur    = LrecEngine.nativeGetDurationMs(handle)

                if (count == 0) {
                    LrecEngine.nativeClose(handle)
                    uiHandler.post { showError("الملف لا يحتوي على إطارات للعرض") }
                    return@Thread
                }

                // ── تخصيص Bitmap وبيانات البكسل ─────────────────
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val pix = IntArray(w * h)

                // ── رسم الإطار الأول ─────────────────────────────
                if (LrecEngine.nativeRenderFrame(handle, 0, pix)) {
                    bmp.setPixels(pix, 0, w, 0, 0, w, h)
                }

                engineHandle  = handle
                totalFrames   = count
                screenWidth   = w
                screenHeight  = h
                durationMs    = dur
                screenBitmap  = bmp
                pixelBuffer   = pix

                uiHandler.post {
                    isReadyToPlay = true
                    layoutLoading.visibility = View.GONE
                    imageViewScreen.setImageBitmap(bmp)
                    tvTotalTime.text  = fmtTime(dur)
                    tvFrameInfo.text  = "$count إطار | ${w}×${h}"
                    tvAudioStatus.text = "🔇 بدون صوت"  // الصوت مشفر في هذا التنسيق
                    seekBar.max = SEEK_BAR_MAX
                    setControlsEnabled(true)
                    updateProgressUI()
                }

            } catch (e: InterruptedException) {
                // إلغاء طبيعي عند onDestroy
            } catch (e: OutOfMemoryError) {
                uiHandler.post { showError("ذاكرة غير كافية لفتح هذا الملف") }
            } catch (e: Exception) {
                uiHandler.post { showError("خطأ غير متوقع:\n${e.message}") }
            }
        }.also { it.isDaemon = true; it.name = "LrecLoad"; it.start() }
    }

    // ════════════════════════════════════════════════════════════════
    //  التحكم بالتشغيل
    // ════════════════════════════════════════════════════════════════

    private fun startPlayback() {
        if (!isReadyToPlay || isPlaying.get()) return
        // انتهى الملف؟ أعد من البداية
        if (currentFrameIdx.get() >= totalFrames - 1) currentFrameIdx.set(0)
        isPlaying.set(true)
        startDecodeLoop()
    }

    private fun pausePlayback() {
        isPlaying.set(false)
        decThread?.interrupt(); decThread = null
    }

    private fun stopPlayback() {
        pausePlayback()
        currentFrameIdx.set(0)
        renderSingleFrame(0)
        uiHandler.post {
            seekBar.progress = 0
            tvCurrentTime.text = "00:00"
        }
    }

    // ── الانتقال بـ ±delta زمني ───────────────────────────────────
    private fun seekBy(deltaMs: Long) {
        val handle = engineHandle; if (handle == 0L) return
        val curIdx = currentFrameIdx.get().coerceIn(0, totalFrames - 1)
        val curMs  = LrecEngine.nativeGetFrameTimestamp(handle, curIdx)
        doSeekToMs((curMs + deltaMs).coerceIn(0L, durationMs))
    }

    // ── الانتقال إلى وقت محدد ─────────────────────────────────────
    private fun doSeekToMs(targetMs: Long) {
        val handle = engineHandle; if (handle == 0L) return
        val wasPlaying = isPlaying.get()
        if (wasPlaying) pausePlayback()

        val targetIdx = LrecEngine.nativeFindFrameAtMs(handle, targetMs)
        currentFrameIdx.set(targetIdx)
        renderSingleFrame(targetIdx)
        uiHandler.post { updateProgressUI() }

        if (wasPlaying) startPlayback()
    }

    // ════════════════════════════════════════════════════════════════
    //  حلقة الـ decoding (background thread)
    // ════════════════════════════════════════════════════════════════

    private fun startDecodeLoop() {
        decThread?.interrupt()
        decThread = Thread {
            val handle = engineHandle
            val bmp    = screenBitmap ?: return@Thread
            val pix    = pixelBuffer  ?: return@Thread

            try {
                while (isPlaying.get() && !Thread.currentThread().isInterrupted) {
                    val idx = currentFrameIdx.get()

                    // انتهى الملف
                    if (idx >= totalFrames) {
                        isPlaying.set(false)
                        uiHandler.post {
                            currentFrameIdx.set(totalFrames - 1)
                            updateProgressUI()
                        }
                        break
                    }

                    val wallStart = System.currentTimeMillis()

                    // ── فك الضغط + رسم الإطار (العملية الثقيلة في C++) ──
                    val ok = LrecEngine.nativeRenderFrame(handle, idx, pix)

                    if (ok) {
                        // كتابة pixels إلى bitmap (سريع — memcpy)
                        bmp.setPixels(pix, 0, screenWidth, 0, 0, screenWidth, screenHeight)

                        // تحديث الـ ImageView في UI thread
                        uiHandler.post {
                            imageViewScreen.setImageBitmap(bmp)
                            if (!isSeeking) updateProgressUI()
                        }
                    }

                    currentFrameIdx.incrementAndGet()

                    // ── تزامن الوقت: انتظر الفارق بين الإطارات ──────────
                    val nextIdx = idx + 1
                    val curTs   = LrecEngine.nativeGetFrameTimestamp(handle, idx)
                    val nextTs  = if (nextIdx < totalFrames)
                        LrecEngine.nativeGetFrameTimestamp(handle, nextIdx)
                    else curTs + 200L

                    val elapsed  = System.currentTimeMillis() - wallStart
                    val interval = (nextTs - curTs).coerceIn(50L, 1000L)
                    val sleepMs  = interval - elapsed

                    if (sleepMs > 2) Thread.sleep(sleepMs)
                }
            } catch (_: InterruptedException) {
                // إيقاف طبيعي
            } catch (e: Exception) {
                uiHandler.post { showError("خطأ أثناء التشغيل:\n${e.message}") }
            }
        }.also { it.isDaemon = true; it.name = "LrecDecode"; it.start() }
    }

    // ── رسم إطار منفرد (عند الـ seek أو الإيقاف) ─────────────────
    private fun renderSingleFrame(idx: Int) {
        val handle = engineHandle; if (handle == 0L) return
        val bmp    = screenBitmap  ?: return
        val pix    = pixelBuffer   ?: return

        Thread {
            try {
                if (LrecEngine.nativeRenderFrame(handle, idx, pix)) {
                    bmp.setPixels(pix, 0, screenWidth, 0, 0, screenWidth, screenHeight)
                    uiHandler.post { imageViewScreen.setImageBitmap(bmp) }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
    }

    // ════════════════════════════════════════════════════════════════
    //  تحديثات الواجهة (UI thread)
    // ════════════════════════════════════════════════════════════════

    private fun updateProgressUI() {
        val handle = engineHandle; if (handle == 0L) return
        val idx = currentFrameIdx.get().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        val ts  = LrecEngine.nativeGetFrameTimestamp(handle, idx)

        tvCurrentTime.text = fmtTime(ts)

        val progress = if (durationMs > 0)
            ((ts.toFloat() / durationMs) * SEEK_BAR_MAX).toInt().coerceIn(0, SEEK_BAR_MAX)
        else 0

        if (!isSeeking) seekBar.progress = progress
    }

    private fun setControlsEnabled(on: Boolean) {
        btnPlay.isEnabled    = on
        btnPause.isEnabled   = on
        btnStop.isEnabled    = on
        btnForward.isEnabled = on
        btnRewind.isEnabled  = on
        seekBar.isEnabled    = on
    }

    private fun setLoadingMessage(msg: String) {
        layoutLoading.visibility = View.VISIBLE
        tvLoadingMsg.text = msg
    }

    private fun showError(msg: String) {
        layoutLoading.visibility = View.GONE
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fmtTime(ms: Long): String {
        val s   = ms / 1000L
        val h   = s / 3600L
        val m   = (s % 3600L) / 60L
        val sec = s % 60L
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else               String.format("%02d:%02d", m, sec)
    }
}

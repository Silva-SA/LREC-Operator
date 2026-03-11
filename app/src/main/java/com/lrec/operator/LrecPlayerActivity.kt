package com.lrec.operator

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ══════════════════════════════════════════════════════════════════════
 *  LrecPlayerActivity — مشغّل ملفات .lrec (إعادة كتابة كاملة)
 *
 *  يستخدم LrecParser.kt مباشرةً (بدون مكتبة C++)
 *  يدعم:
 *    ✅ تشغيل الفيديو (الإطارات الكاملة + إطارات الفروقات)
 *    ✅ شريط تقدم مع عرض الوقت
 *    ✅ تقديم / ترجيع 10 ثوانٍ
 *    ✅ إيقاف مؤقت / استئناف
 *    ✅ معلومات الملف في حوار
 *    ⛔ الصوت: مشفّر (CastEncrypt) — غير مدعوم
 *    ⛔ المحادثة: مشفّرة (CastEncrypt) — غير مدعومة
 * ══════════════════════════════════════════════════════════════════════
 */
class LrecPlayerActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────
    private lateinit var imageView:      ImageView
    private lateinit var btnPlayPause:   ImageButton
    private lateinit var btnRewind:      ImageButton
    private lateinit var btnForward:     ImageButton
    private lateinit var btnBack:        ImageButton
    private lateinit var btnInfo:        ImageButton
    private lateinit var seekBar:        SeekBar
    private lateinit var tvCurrentTime:  TextView
    private lateinit var tvDuration:     TextView
    private lateinit var tvTitle:        TextView
    private lateinit var playerControls: LinearLayout
    private lateinit var topBar:         LinearLayout
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvStatus:       TextView

    // ── حالة المشغّل ──────────────────────────────────────────────────
    private var lrecParser: LrecParser?           = null
    private var frames:     List<LrecParser.LrecFrame> = emptyList()

    // Bitmap مزدوج لمنع التعارض بين خيط الفك وخيط الواجهة
    private var workBitmap:    Bitmap? = null   // خيط الخلفية يكتب هنا
    private var displayBitmap: Bitmap? = null   // الواجهة تعرض هذا

    private val frameIndex = AtomicInteger(0)
    private val isPlaying  = AtomicBoolean(false)
    private val isParsed   = AtomicBoolean(false)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var playThread: Thread? = null

    // ── إظهار / إخفاء التحكم ─────────────────────────────────────────
    private var controlsVisible = true
    private val HIDE_DELAY_MS   = 3_500L
    private val hideRunnable    = Runnable { hideControls() }

    // ── إيماءات ──────────────────────────────────────────────────────
    private lateinit var gestureDetector: GestureDetector

    // ══════════════════════════════════════════════════════════════════
    //  دورة الحياة
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        setContentView(R.layout.activity_lrec_player)

        bindViews()
        setupListeners()
        loadFile()
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
    }

    override fun onDestroy() {
        isPlaying.set(false)
        playThread?.interrupt()
        uiHandler.removeCallbacksAndMessages(null)
        workBitmap?.recycle()
        displayBitmap?.recycle()
        workBitmap    = null
        displayBitmap = null
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════
    //  ربط العناصر
    // ══════════════════════════════════════════════════════════════════

    private fun bindViews() {
        imageView      = findViewById(R.id.lrecImageView)
        btnPlayPause   = findViewById(R.id.lrecBtnPlayPause)
        btnRewind      = findViewById(R.id.lrecBtnRewind)
        btnForward     = findViewById(R.id.lrecBtnForward)
        btnBack        = findViewById(R.id.lrecBtnBack)
        btnInfo        = findViewById(R.id.lrecBtnInfo)
        seekBar        = findViewById(R.id.lrecSeekBar)
        tvCurrentTime  = findViewById(R.id.lrecTvCurrentTime)
        tvDuration     = findViewById(R.id.lrecTvDuration)
        tvTitle        = findViewById(R.id.lrecTvTitle)
        playerControls = findViewById(R.id.lrecPlayerControls)
        topBar         = findViewById(R.id.lrecTopBar)
        progressBar    = findViewById(R.id.lrecProgressBar)
        tvStatus       = findViewById(R.id.lrecTvStatus)

        // أزرار معطّلة حتى يكتمل التحليل
        setControlsEnabled(false)
    }

    // ══════════════════════════════════════════════════════════════════
    //  الأحداث
    // ══════════════════════════════════════════════════════════════════

    private fun setupListeners() {

        btnBack.setOnClickListener        { finish() }
        btnPlayPause.setOnClickListener   { togglePlayPause() }
        btnRewind.setOnClickListener      { seekRelativeSeconds(-10) }
        btnForward.setOnClickListener     { seekRelativeSeconds(+10) }
        btnInfo.setOnClickListener        { showInfoDialog() }

        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && frames.isNotEmpty()) {
                    val target = (progress.toLong() * frames.size / 1000L)
                        .toInt().coerceIn(0, frames.size - 1)
                    frameIndex.set(target)
                    updateTimeTextUI()
                    showControls()
                    if (!isPlaying.get()) renderCurrentFrameAsync()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                uiHandler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                scheduleHide()
            }
        })

        // اكتشاف النقر لإظهار/إخفاء التحكم
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (controlsVisible) hideControls() else showControls()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    togglePlayPause()
                    return true
                }
            })

        imageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  تحميل وتحليل الملف
    // ══════════════════════════════════════════════════════════════════

    private fun loadFile() {
        val uri = intent.data ?: run { finish(); return }

        tvTitle.text = uri.lastPathSegment ?: "ملف .lrec"
        progressBar.visibility = View.VISIBLE
        tvStatus.text          = "جاري تحليل الملف…"
        tvStatus.visibility    = View.VISIBLE

        Thread {
            val filePath = copyToCache(uri)
            val file     = File(filePath)
            val parser   = LrecParser(file)
            val ok       = parser.parse()

            runOnUiThread {
                progressBar.visibility = View.GONE

                if (ok) {
                    lrecParser = parser
                    frames     = parser.getAllFrames()
                    isParsed.set(true)

                    val w = parser.metadata.screenWidth
                    val h = parser.metadata.screenHeight

                    // تحضير Bitmap مزدوج
                    workBitmap    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    displayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

                    tvStatus.text = buildString {
                        append("${frames.size} إطار")
                        if (parser.hasAudioBlocks) append("  |  🔇 صوت مشفّر")
                        if (parser.hasChatBlocks)  append("  |  💬 شات مشفّر")
                    }

                    tvDuration.text    = formatTime(parser.getDurationMs())
                    tvCurrentTime.text = "00:00"

                    setControlsEnabled(true)

                    // تشغيل تلقائي
                    startPlayback()
                    showControls()

                } else {
                    tvStatus.text = "❌  تعذّر فتح الملف — تأكد أنه ملف .lrec صالح"
                }
            }
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════
    //  التشغيل والإيقاف
    // ══════════════════════════════════════════════════════════════════

    private fun togglePlayPause() {
        if (isPlaying.get()) pausePlayback() else startPlayback()
        showControls()
    }

    private fun startPlayback() {
        if (!isParsed.get() || frames.isEmpty()) return
        if (isPlaying.get()) return

        // إذا انتهى الفيديو، ابدأ من الأول
        if (frameIndex.get() >= frames.size) frameIndex.set(0)

        isPlaying.set(true)
        btnPlayPause.setImageResource(R.drawable.ic_pause)

        val parser = lrecParser ?: return

        playThread = Thread {
            val fps          = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
            val frameDelayMs = (1000L / fps).coerceAtLeast(33L)   // ≥ 30ms

            while (isPlaying.get()) {

                val idx = frameIndex.get()

                if (idx >= frames.size) {
                    // انتهى الفيديو
                    uiHandler.post {
                        isPlaying.set(false)
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                        showControls()
                    }
                    break
                }

                val frame     = frames[idx]
                val frameData = try { parser.decodeScreenFrame(frame) } catch (_: Exception) { null }

                // تطبيق الإطار على workBitmap ثم نسخه إلى displayBitmap
                val wBmp = workBitmap
                val dBmp = displayBitmap

                if (frameData != null && wBmp != null && dBmp != null) {
                    parser.applyFrameToBitmap(wBmp, frameData)
                    // نسخ workBitmap إلى displayBitmap بالكامل (آمن من التعارض)
                    val canvas = android.graphics.Canvas(dBmp)
                    canvas.drawBitmap(wBmp, 0f, 0f, null)
                }

                val snap = dBmp
                frameIndex.incrementAndGet()

                uiHandler.post {
                    if (snap != null) imageView.setImageBitmap(snap)
                    updateProgressUI(frameIndex.get())
                }

                try { Thread.sleep(frameDelayMs) }
                catch (_: InterruptedException) { break }
            }
        }

        playThread?.start()
    }

    private fun pausePlayback() {
        if (!isPlaying.get()) return
        isPlaying.set(false)
        playThread?.interrupt()
        playThread = null
        btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    // ══════════════════════════════════════════════════════════════════
    //  التقديم والترجيع
    // ══════════════════════════════════════════════════════════════════

    private fun seekRelativeSeconds(seconds: Int) {
        if (!isParsed.get() || frames.isEmpty()) return

        val parser = lrecParser ?: return
        val fps    = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
        val delta  = seconds * fps
        val newIdx = (frameIndex.get() + delta).coerceIn(0, frames.size - 1)

        frameIndex.set(newIdx)
        updateProgressUI(newIdx)
        updateTimeTextUI()
        showControls()

        if (!isPlaying.get()) renderCurrentFrameAsync()
    }

    // رسم الإطار الحالي (عند الإيقاف المؤقت / السحب في شريط التقدم)
    private fun renderCurrentFrameAsync() {
        val parser = lrecParser ?: return
        val idx    = frameIndex.get().coerceIn(0, frames.size - 1)
        val frame  = frames.getOrNull(idx) ?: return

        Thread {
            val frameData = try { parser.decodeScreenFrame(frame) } catch (_: Exception) { null }
            val wBmp = workBitmap
            val dBmp = displayBitmap
            if (frameData != null && wBmp != null && dBmp != null) {
                parser.applyFrameToBitmap(wBmp, frameData)
                val canvas = android.graphics.Canvas(dBmp)
                canvas.drawBitmap(wBmp, 0f, 0f, null)
                uiHandler.post { imageView.setImageBitmap(dBmp) }
            }
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════
    //  حوار المعلومات
    // ══════════════════════════════════════════════════════════════════

    private fun showInfoDialog() {
        pausePlayback()

        val parser = lrecParser
        val m      = parser?.metadata

        val msg = buildString {
            appendLine("📹  معلومات الملف")
            appendLine()
            appendLine("• دقة الشاشة:   ${m?.screenWidth ?: "?"} × ${m?.screenHeight ?: "?"} بكسل")
            appendLine("• إجمالي الإطارات:  ${frames.size}")
            appendLine("• مدة التسجيل:   ${formatTime(parser?.getDurationMs() ?: 0)}")
            appendLine("• الإصدار:        ${m?.version ?: "?"}")
            if (m?.serverAddr?.isNotEmpty() == true)
                appendLine("• الخادم:         ${m.serverAddr}")
            appendLine()
            appendLine("─────────────────────────────────")
            appendLine()
            appendLine("🔊  الصوت")
            if (parser?.hasAudioBlocks == true) {
                appendLine("تم اكتشاف ${parser.audioBlockCount} حزمة صوتية.")
                appendLine("الصوت مشفَّر بخوارزمية CastEncrypt الخاصة")
                appendLine("بنظام Inter-Tel Collaboration Client.")
                appendLine("⚠️  لا يمكن تشغيل الصوت بدون مفتاح التشفير.")
            } else {
                appendLine("لا توجد بيانات صوتية في هذا الملف.")
            }
            appendLine()
            appendLine("─────────────────────────────────")
            appendLine()
            appendLine("💬  المحادثة (Chat)")
            if (parser?.hasChatBlocks == true) {
                appendLine("تم اكتشاف ${parser.chatBlockCount} رسالة محادثة.")
                appendLine("المحادثة مشفَّرة بنفس الخوارزمية.")
                appendLine("⚠️  لا يمكن عرض المحادثة بدون مفتاح التشفير.")
            } else {
                appendLine("لا توجد بيانات محادثة في هذا الملف.")
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle("ℹ️  معلومات الملف")
            .setMessage(msg)
            .setPositiveButton("إغلاق", null)
            .show()

        showControls()
    }

    // ══════════════════════════════════════════════════════════════════
    //  تحديث واجهة شريط التقدم
    // ══════════════════════════════════════════════════════════════════

    private fun updateProgressUI(currentFrameIdx: Int) {
        if (frames.isEmpty()) return
        val progress = (currentFrameIdx.toLong() * 1000L / frames.size).toInt().coerceIn(0, 1000)
        seekBar.progress = progress
        updateTimeTextUI()
    }

    private fun updateTimeTextUI() {
        val parser = lrecParser ?: return
        val fps    = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
        val ms     = frameIndex.get().toLong() * 1000L / fps
        tvCurrentTime.text = formatTime(ms)
    }

    // ══════════════════════════════════════════════════════════════════
    //  إظهار / إخفاء التحكم
    // ══════════════════════════════════════════════════════════════════

    private fun showControls() {
        uiHandler.removeCallbacks(hideRunnable)
        if (!controlsVisible) {
            controlsVisible = true
            listOf(playerControls, topBar).forEach { v ->
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(200).start()
            }
        }
        scheduleHide()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        playerControls.animate().alpha(0f).setDuration(300)
            .withEndAction { playerControls.visibility = View.INVISIBLE }.start()
        topBar.animate().alpha(0f).setDuration(300)
            .withEndAction { topBar.visibility = View.INVISIBLE }.start()
    }

    private fun scheduleHide() {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    // ══════════════════════════════════════════════════════════════════
    //  مساعدات
    // ══════════════════════════════════════════════════════════════════

    private fun setControlsEnabled(enabled: Boolean) {
        btnPlayPause.isEnabled = enabled
        btnRewind.isEnabled    = enabled
        btnForward.isEnabled   = enabled
        seekBar.isEnabled      = enabled
    }

    private fun enterFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN           or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION      or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY     or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN    or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun copyToCache(uri: Uri): String {
        val file = File(cacheDir, "lrec_temp.lrec")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { out ->
                val buf = ByteArray(65_536)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        return file.absolutePath
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h   = totalSec / 3600
        val m   = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else              String.format("%02d:%02d", m, sec)
    }
}

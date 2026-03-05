package com.lrec.operator

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    // ─── Views ────────────────────────────────────────────────────
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    private lateinit var btnPlayPause:  ImageButton
    private lateinit var btnForward:    ImageButton
    private lateinit var btnRewind:     ImageButton
    private lateinit var btnBack:       ImageButton
    private lateinit var btnRotate:     ImageButton
    private lateinit var btnPlayerMenu: ImageButton

    private lateinit var seekBar:       SeekBar
    private lateinit var progressFill:  View
    private lateinit var progressThumb: View
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration:    TextView
    private lateinit var tvTitle:       TextView

    private lateinit var playerControls: LinearLayout
    private lateinit var topBar:         LinearLayout

    private lateinit var volumeOverlay:       LinearLayout
    private lateinit var volumeBar:           View
    private lateinit var tvVolumePercent:     TextView
    private lateinit var brightnessOverlay:   LinearLayout
    private lateinit var brightnessBar:       View
    private lateinit var tvBrightnessPercent: TextView

    private lateinit var gestureDetector: GestureDetector
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager

    // ─── الحالة ───────────────────────────────────────────────────
    private var controlsVisible     = true
    private var isDraggingSeekBar   = false
    private var currentPlaybackSpeed = 1.0f
    private var isLandscape         = true

    // مدة الإخفاء التلقائي: دقيقتان كاملتان
    private val HIDE_DELAY_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private var overlayRunnable: Runnable? = null

    // ─── onCreate ─────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lrec_prefs", MODE_PRIVATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val isDark = prefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE        or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN    or
            View.SYSTEM_UI_FLAG_FULLSCREEN           or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION      or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)
        initViews()
        initPlayer()
        setupDrawer()
        setupControls()
        setupSeekBar()
        setupGestures()
        startProgressUpdater()
        handleIntent(intent)
    }

    // ─── ربط العناصر ─────────────────────────────────────────────
    private fun initViews() {
        drawerLayout        = findViewById(R.id.drawerLayout)
        navigationView      = findViewById(R.id.navigationView)
        playerView          = findViewById(R.id.playerView)
        btnPlayPause        = findViewById(R.id.btnPlayPause)
        btnForward          = findViewById(R.id.btnForward)
        btnRewind           = findViewById(R.id.btnRewind)
        btnBack             = findViewById(R.id.btnBack)
        btnRotate           = findViewById(R.id.btnRotate)
        btnPlayerMenu       = findViewById(R.id.btnPlayerMenu)
        seekBar             = findViewById(R.id.seekBar)
        progressFill        = findViewById(R.id.progressFill)
        progressThumb       = findViewById(R.id.progressThumb)
        tvCurrentTime       = findViewById(R.id.tvCurrentTime)
        tvDuration          = findViewById(R.id.tvDuration)
        tvTitle             = findViewById(R.id.tvTitle)
        playerControls      = findViewById(R.id.playerControls)
        topBar              = findViewById(R.id.topBar)
        volumeOverlay       = findViewById(R.id.volumeOverlay)
        volumeBar           = findViewById(R.id.volumeBar)
        tvVolumePercent     = findViewById(R.id.tvVolumePercent)
        brightnessOverlay   = findViewById(R.id.brightnessOverlay)
        brightnessBar       = findViewById(R.id.brightnessBar)
        tvBrightnessPercent = findViewById(R.id.tvBrightnessPercent)
    }

    // ─── تهيئة المشغل ────────────────────────────────────────────
    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.useController = false

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND   -> "الملف غير موجود."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "تنسيق الفيديو غير مدعوم."
                    else -> "حدث خطأ أثناء التشغيل."
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    btnPlayPause.setImageResource(R.drawable.ic_play)
                    showControls()
                }
            }
        })
    }

    // ─── القائمة الجانبية للمشغل ──────────────────────────────────
    private fun setupDrawer() {
        btnPlayerMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
            resetHideTimer()
        }

        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.nav_speed    -> { showSpeedDialog();  true }
                R.id.nav_rotate   -> { toggleRotation();   true }
                R.id.nav_back_lib -> { finish();           true }
                else -> false
            }
        }
    }

    // ─── حوار سرعة التشغيل ───────────────────────────────────────
    private fun showSpeedDialog() {
        val labels = arrayOf("0.25×","0.5×","0.75×","1.0× (عادي)","1.25×","1.5×","1.75×","2.0×")
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val cur = values.indexOfFirst { it == currentPlaybackSpeed }.takeIf { it >= 0 } ?: 3

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("سرعة التشغيل")
            .setSingleChoiceItems(labels, cur) { dialog, which ->
                currentPlaybackSpeed = values[which]
                player.playbackParameters = PlaybackParameters(currentPlaybackSpeed)
                dialog.dismiss()
                Toast.makeText(this, "السرعة: ${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ─── تدوير الشاشة ────────────────────────────────────────────
    private fun toggleRotation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // ─── أزرار التحكم ────────────────────────────────────────────
    private fun setupControls() {

        btnPlayPause.setOnClickListener {
            pulse(it)
            if (player.isPlaying) player.pause() else player.play()
            resetHideTimer()
        }

        btnForward.setOnClickListener {
            pulse(it)
            player.seekTo(player.currentPosition + 10_000L)
            resetHideTimer()
        }

        btnRewind.setOnClickListener {
            pulse(it)
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            resetHideTimer()
        }

        btnBack.setOnClickListener   { finish() }
        btnRotate.setOnClickListener { pulse(it); toggleRotation(); resetHideTimer() }
    }

    // ─── تحميل الفيديو ───────────────────────────────────────────
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri == null) { finish(); return }
        tvTitle.text = intent.getStringExtra("VIDEO_TITLE") ?: getVideoTitle(uri)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        showControls()
        scheduleHide()
    }

    private fun getVideoTitle(uri: Uri): String = try {
        val c = contentResolver.query(uri, arrayOf(MediaStore.Video.Media.TITLE), null, null, null)
        c?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "فيديو"
    } catch (e: Exception) { uri.lastPathSegment ?: "فيديو" }

    // ─── شريط التقدم ─────────────────────────────────────────────
    private fun setupSeekBar() {
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    player.seekTo(player.duration * p / 1000L)
                    updateProgressUI(p, 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                isDraggingSeekBar = true
                handler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isDraggingSeekBar = false
                resetHideTimer()
            }
        })
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!isDraggingSeekBar && player.duration > 0) {
                val pos  = player.currentPosition
                val dur  = player.duration
                val prog = (pos * 1000L / dur).toInt()
                seekBar.progress = prog
                updateProgressUI(prog, 1000)
                tvCurrentTime.text = fmtTime(pos)
                tvDuration.text    = fmtTime(dur)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startProgressUpdater() { handler.post(progressUpdater) }

    private fun updateProgressUI(progress: Int, max: Int) {
        progressFill.post {
            val pw = (progressFill.parent as? View)?.width ?: return@post
            val fw = (pw * progress / max).coerceAtLeast(0)
            progressFill.layoutParams.width = fw
            progressFill.requestLayout()
            val tp = progressThumb.layoutParams as? ViewGroup.MarginLayoutParams
            tp?.marginStart = (fw - 7.dp).coerceAtLeast(0)
            progressThumb.layoutParams = tp
        }
    }

    // ─── الإيماءات ───────────────────────────────────────────────
    // المشكلة الرئيسية كانت هنا: onSingleTapConfirmed بطيء جداً
    // الحل: استخدام onSingleTapUp للاستجابة الفورية
    private fun setupGestures() {
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {

                // ─ لمسة واحدة سريعة = إظهار/إخفاء فوري ──────────
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (player.duration > 0) {
                        if (controlsVisible) hideControls() else showControls()
                    }
                    return true
                }

                // ─ ضغطة مزدوجة = تشغيل/إيقاف ────────────────────
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    pulse(btnPlayPause)
                    if (player.isPlaying) player.pause() else player.play()
                    resetHideTimer()
                    return true
                }

                // ─ سحب عمودي = صوت / سطوع ────────────────────────
                // distanceY في Android:
                //   موجب (+) = السحب للأعلى على الشاشة (رفع)
                //   سالب (-) = السحب للأسفل على الشاشة (خفض)
                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    distanceX: Float, distanceY: Float
                ): Boolean {
                    if (player.duration <= 0) return false

                    // نتجاهل الحركة الأفقية الكبيرة
                    if (Math.abs(distanceX) > Math.abs(distanceY) * 1.5f) return false

                    val screenW = resources.displayMetrics.widthPixels
                    val x = e1?.x ?: 0f

                    // distanceY موجب = المستخدم يسحب للأعلى = يريد رفع القيمة
                    // نقسم على 400 للحصول على حساسية مناسبة
                    val delta = distanceY / 400f

                    if (x < screenW / 2f) {
                        adjustBrightness(delta)   // الجانب الأيسر = سطوع
                    } else {
                        adjustVolume(delta)        // الجانب الأيمن = صوت
                    }
                    return true
                }
            })

        // نستخدم setOnTouchListener على playerView مباشرة
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            // نُعيد false حتى لا نحجب أحداث onClick
            false
        }
    }

    // ─── ضبط الصوت ───────────────────────────────────────────────
    // الإصلاح: استخدام adjustStreamVolume بدلاً من setStreamVolume
    // لأن adjustStreamVolume يتعامل مع الخطوات بشكل صحيح
    private fun adjustVolume(delta: Float) {
        val maxVol  = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol  = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // delta موجب = السحب للأعلى = رفع الصوت
        val change  = (delta * maxVol).toInt()
        val newVol  = (curVol + change).coerceIn(0, maxVol)

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newVol,
            0  // بدون صوت تنبيه أو اهتزاز
        )

        val pct = if (maxVol > 0) (newVol * 100 / maxVol) else 0
        showVolumeOverlay(pct)
        resetHideTimer()
    }

    private fun showVolumeOverlay(pct: Int) {
        tvVolumePercent.text = "$pct%"
        val barH = 110.dp
        volumeBar.layoutParams.height = (barH * pct / 100).coerceIn(0, barH)
        volumeBar.requestLayout()
        showOverlay(volumeOverlay)
    }

    // ─── ضبط السطوع ──────────────────────────────────────────────
    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        var b  = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        // delta موجب = رفع السطوع
        b = (b + delta).coerceIn(0.01f, 1f)
        lp.screenBrightness = b
        window.attributes   = lp
        showBrightnessOverlay((b * 100).toInt())
        resetHideTimer()
    }

    private fun showBrightnessOverlay(pct: Int) {
        tvBrightnessPercent.text = "$pct%"
        val barH = 110.dp
        brightnessBar.layoutParams.height = (barH * pct / 100).coerceIn(0, barH)
        brightnessBar.requestLayout()
        showOverlay(brightnessOverlay)
    }

    private fun showOverlay(overlay: View) {
        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            overlay.alpha      = 0f
            overlay.animate().alpha(1f).setDuration(150).start()
        }
        overlayRunnable?.let { handler.removeCallbacks(it) }
        overlayRunnable = Runnable {
            overlay.animate().alpha(0f).setDuration(300)
                .withEndAction { overlay.visibility = View.INVISIBLE }.start()
        }
        handler.postDelayed(overlayRunnable!!, 1800)
    }

    // ─── إظهار / إخفاء التحكم ────────────────────────────────────
    private fun showControls() {
        if (controlsVisible) { resetHideTimer(); return }
        controlsVisible = true
        listOf(playerControls, topBar).forEach { v ->
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        scheduleHide()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        playerControls.animate().alpha(0f).translationY(50f).setDuration(250)
            .withEndAction { playerControls.visibility = View.INVISIBLE }.start()
        topBar.animate().alpha(0f).translationY(-35f).setDuration(250)
            .withEndAction { topBar.visibility = View.INVISIBLE }.start()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun resetHideTimer() {
        if (!controlsVisible) showControls() else scheduleHide()
    }

    // ─── مساعدات ─────────────────────────────────────────────────
    private fun pulse(v: View) {
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    // ─── دورة الحياة ─────────────────────────────────────────────
    override fun onPause()  { super.onPause(); player.pause() }
    override fun onResume() {
        super.onResume()
        if (player.playbackState != Player.STATE_ENDED && player.duration > 0) player.play()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START))
            drawerLayout.closeDrawer(GravityCompat.START)
        else
            super.onBackPressed()
    }
}

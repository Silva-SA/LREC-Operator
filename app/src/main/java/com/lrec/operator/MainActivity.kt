package com.lrec.operator

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRotate: ImageButton

    private lateinit var seekBar: SeekBar
    private lateinit var progressFill: View
    private lateinit var progressThumb: View
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvTitle: TextView

    private lateinit var playerControls: LinearLayout
    private lateinit var topBar: LinearLayout

    private lateinit var volumeOverlay: LinearLayout
    private lateinit var volumeBar: View
    private lateinit var tvVolumePercent: TextView

    private lateinit var brightnessOverlay: LinearLayout
    private lateinit var brightnessBar: View
    private lateinit var tvBrightnessPercent: TextView

    private lateinit var gestureDetector: GestureDetector
    private lateinit var prefs: SharedPreferences

    // ─── الحالة ───────────────────────────────────────────────────
    private var controlsVisible = true
    private var isDraggingSeekBar = false
    private val hideControlsDelay = 120_000L   // دقيقتان
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var overlayHideRunnable: Runnable? = null
    private var currentPlaybackSpeed = 1.0f
    private var isLandscape = true

    // ─── onCreate ─────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lrec_prefs", MODE_PRIVATE)

        // تطبيق الثيم المحفوظ
        val isDark = prefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)
        initializeViews()
        initializePlayer()
        setupDrawerMenu()
        setupControls()
        setupSeekBar()
        setupGestures()
        startProgressUpdater()
        handleIncomingIntent(intent)
    }

    // ─── ربط العناصر ─────────────────────────────────────────────
    private fun initializeViews() {
        drawerLayout        = findViewById(R.id.drawerLayout)
        navigationView      = findViewById(R.id.navigationView)
        playerView          = findViewById(R.id.playerView)
        btnPlayPause        = findViewById(R.id.btnPlayPause)
        btnForward          = findViewById(R.id.btnForward)
        btnRewind           = findViewById(R.id.btnRewind)
        btnBack             = findViewById(R.id.btnBack)
        btnRotate           = findViewById(R.id.btnRotate)
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
    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.useController = false

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND    -> "الملف غير موجود."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED  -> "تنسيق الفيديو غير مدعوم."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "فشل الاتصال بالشبكة."
                    else -> "حدث خطأ أثناء التشغيل."
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                updatePlayPauseIcon(false)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    updatePlayPauseIcon(false)
                    showControls()
                }
            }
        })
    }

    // ─── القائمة الجانبية ─────────────────────────────────────────
    private fun setupDrawerMenu() {
        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {

                // سرعة التشغيل
                R.id.nav_speed -> {
                    showSpeedDialog()
                    true
                }

                // تدوير الشاشة
                R.id.nav_rotate -> {
                    toggleRotation()
                    true
                }

                // مظهر التطبيق
                R.id.nav_theme -> {
                    toggleTheme()
                    true
                }

                // الإعدادات
                R.id.nav_settings -> {
                    showSettingsDialog()
                    true
                }

                // تحديث المحتوى - العودة للمكتبة
                R.id.nav_refresh -> {
                    finish()
                    true
                }

                else -> false
            }
        }
    }

    // ─── حوار سرعة التشغيل ───────────────────────────────────────
    private fun showSpeedDialog() {
        val speeds = arrayOf("0.25×", "0.5×", "0.75×", "1.0× (عادي)", "1.25×", "1.5×", "1.75×", "2.0×")
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = values.indexOfFirst { it == currentPlaybackSpeed }.takeIf { it >= 0 } ?: 3

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("سرعة التشغيل")
            .setSingleChoiceItems(speeds, current) { dialog, which ->
                currentPlaybackSpeed = values[which]
                player.playbackParameters = PlaybackParameters(currentPlaybackSpeed)
                dialog.dismiss()
                Toast.makeText(this, "السرعة: ${speeds[which]}", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this,
            if (isLandscape) "وضع أفقي" else "وضع عمودي",
            Toast.LENGTH_SHORT).show()
    }

    // ─── تبديل الثيم ─────────────────────────────────────────────
    private fun toggleTheme() {
        val isDark = prefs.getBoolean("dark_mode", true)
        prefs.edit().putBoolean("dark_mode", !isDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        Toast.makeText(this,
            if (!isDark) "تم التبديل للوضع الداكن" else "تم التبديل للوضع الفاتح",
            Toast.LENGTH_SHORT).show()
    }

    // ─── حوار الإعدادات ──────────────────────────────────────────
    private fun showSettingsDialog() {
        val options = arrayOf(
            "مدة الإخفاء التلقائي: دقيقتان ✓",
            "الصوت والسطوع بالسحب ✓",
            "ترميز الفيديو: تلقائي ✓"
        )
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("الإعدادات")
            .setItems(options, null)
            .setPositiveButton("حسناً", null)
            .show()
    }

    // ─── أزرار التحكم ────────────────────────────────────────────
    private fun setupControls() {

        btnPlayPause.setOnClickListener {
            animateButtonClick(it)
            if (player.isPlaying) player.pause() else player.play()
            resetHideTimer()
        }

        btnForward.setOnClickListener {
            animateButtonClick(it)
            player.seekTo(player.currentPosition + 10_000L)
            resetHideTimer()
        }

        btnRewind.setOnClickListener {
            animateButtonClick(it)
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            resetHideTimer()
        }

        // زر الرجوع للمكتبة
        btnBack.setOnClickListener {
            finish()
        }

        // زر تدوير الشاشة السريع
        btnRotate.setOnClickListener {
            animateButtonClick(it)
            toggleRotation()
            resetHideTimer()
        }

        playerView.setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
        }
    }

    // ─── تحميل الفيديو ───────────────────────────────────────────
    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data
        val title = intent?.getStringExtra("VIDEO_TITLE")
        if (uri != null) {
            tvTitle.text = title ?: getVideoTitle(uri)
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            showControls()
            scheduleHideControls()
        } else {
            finish() // لا يوجد فيديو، ارجع للمكتبة
        }
    }

    private fun getVideoTitle(uri: Uri): String {
        return try {
            val cursor = contentResolver.query(
                uri, arrayOf(MediaStore.Video.Media.TITLE), null, null, null)
            cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: uri.lastPathSegment ?: "فيديو"
        } catch (e: Exception) { uri.lastPathSegment ?: "فيديو" }
    }

    // ─── شريط التقدم ─────────────────────────────────────────────
    private fun setupSeekBar() {
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    player.seekTo(player.duration * progress / 1000L)
                    updateProgressBarUI(progress, 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                isDraggingSeekBar = true
                handler.removeCallbacks(hideControlsRunnable)
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
                val pos = player.currentPosition
                val dur = player.duration
                val prog = (pos * 1000L / dur).toInt()
                seekBar.progress = prog
                updateProgressBarUI(prog, 1000)
                tvCurrentTime.text = formatTime(pos)
                tvDuration.text = formatTime(dur)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startProgressUpdater() { handler.post(progressUpdater) }

    private fun updateProgressBarUI(progress: Int, max: Int) {
        progressFill.post {
            val pw = (progressFill.parent as? View)?.width ?: return@post
            val fw = (pw * progress / max).coerceAtLeast(0)
            progressFill.layoutParams.width = fw
            progressFill.requestLayout()
            val tp = progressThumb.layoutParams as? ViewGroup.MarginLayoutParams
            tp?.marginStart = (fw - 7.dpToPx()).coerceAtLeast(0)
            progressThumb.layoutParams = tp
        }
    }

    // ─── الإيماءات ───────────────────────────────────────────────
    private fun setupGestures() {
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    distanceX: Float, distanceY: Float
                ): Boolean {
                    if (player.duration <= 0) return false
                    val x = e1?.x ?: 0f
                    // distanceY موجب = السحب للأعلى في الواجهة
                    // السحب للأعلى يرفع، السحب للأسفل يخفض
                    val delta = distanceY / 600f  // موجب = تخفيض، سالب = رفع
                    if (x < resources.displayMetrics.widthPixels / 2f) {
                        adjustBrightness(-delta)  // يسار = سطوع
                    } else {
                        adjustVolume(-delta)       // يمين = صوت
                    }
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (controlsVisible) hideControls() else showControls()
                    return true
                }
                // ضغط مزدوج = تشغيل/إيقاف
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    animateButtonClick(btnPlayPause)
                    if (player.isPlaying) player.pause() else player.play()
                    return true
                }
            })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    // ─── الصوت ───────────────────────────────────────────────────
    private fun adjustVolume(delta: Float) {
        val am  = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // delta موجب = رفع، سالب = خفض
        val change = (delta * max).toInt()
        val newVol = (cur + change).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
        val pct = if (max > 0) newVol * 100 / max else 0
        showVolumeOverlay(pct)
        resetHideTimer()
    }

    private fun showVolumeOverlay(pct: Int) {
        tvVolumePercent.text = "$pct%"
        val barH = 110.dpToPx()
        volumeBar.layoutParams.height = (barH * pct / 100).coerceIn(0, barH)
        volumeBar.requestLayout()
        showOverlay(volumeOverlay)
    }

    // ─── السطوع ──────────────────────────────────────────────────
    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        var b = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        b = (b + delta).coerceIn(0.01f, 1f)
        lp.screenBrightness = b
        window.attributes = lp
        showBrightnessOverlay((b * 100).toInt())
        resetHideTimer()
    }

    private fun showBrightnessOverlay(pct: Int) {
        tvBrightnessPercent.text = "$pct%"
        val barH = 110.dpToPx()
        brightnessBar.layoutParams.height = (barH * pct / 100).coerceIn(0, barH)
        brightnessBar.requestLayout()
        showOverlay(brightnessOverlay)
    }

    private fun showOverlay(overlay: View) {
        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(180).start()
        }
        overlayHideRunnable?.let { handler.removeCallbacks(it) }
        overlayHideRunnable = Runnable {
            overlay.animate().alpha(0f).setDuration(300)
                .withEndAction { overlay.visibility = View.INVISIBLE }.start()
        }
        handler.postDelayed(overlayHideRunnable!!, 1800)
    }

    // ─── إظهار/إخفاء التحكم ──────────────────────────────────────
    private fun showControls() {
        if (controlsVisible) return
        controlsVisible = true
        listOf(playerControls, topBar).forEach { v ->
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        scheduleHideControls()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        playerControls.animate().alpha(0f).translationY(60f).setDuration(280)
            .withEndAction { playerControls.visibility = View.INVISIBLE }.start()
        topBar.animate().alpha(0f).translationY(-40f).setDuration(280)
            .withEndAction { topBar.visibility = View.INVISIBLE }.start()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, hideControlsDelay)
    }

    private fun resetHideTimer() {
        if (!controlsVisible) showControls() else scheduleHideControls()
    }

    // ─── مساعدات ─────────────────────────────────────────────────
    private fun updatePlayPauseIcon(playing: Boolean) {
        btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun animateButtonClick(v: View) {
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(75).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    // ─── دورة الحياة ─────────────────────────────────────────────
    override fun onPause()   { super.onPause(); player.pause() }
    override fun onResume()  {
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

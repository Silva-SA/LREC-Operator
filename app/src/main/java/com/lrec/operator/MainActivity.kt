package com.lrec.operator

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    // ─── المشغل والواجهة ───────────────────────────────────────────
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnOpenFile: ImageButton

    private lateinit var seekBar: SeekBar
    private lateinit var progressFill: View
    private lateinit var progressThumb: View
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvTitle: TextView

    private lateinit var playerControls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var emptyState: LinearLayout

    private lateinit var volumeOverlay: LinearLayout
    private lateinit var volumeBar: View
    private lateinit var tvVolumePercent: TextView

    private lateinit var brightnessOverlay: LinearLayout
    private lateinit var brightnessBar: View
    private lateinit var tvBrightnessPercent: TextView

    private lateinit var gestureDetector: GestureDetector

    // ─── الحالة ────────────────────────────────────────────────────
    private var controlsVisible = true
    private var isDraggingSeekBar = false
    private var currentVideoTitle = "LREC Player"
    private val hideControlsDelay = 3500L
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var overlayHideRunnable: Runnable? = null

    companion object {
        private const val REQUEST_PICK_VIDEO = 1001
        private const val REQUEST_PERMISSION = 1002
    }

    // ─── onCreate ──────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        setContentView(R.layout.activity_main)

        initializeViews()
        initializePlayer()
        setupControls()
        setupSeekBar()
        setupGestures()
        startProgressUpdater()

        // التحقق إذا فُتح التطبيق من ملف فيديو
        handleIncomingIntent(intent)
    }

    // ─── ربط العناصر ──────────────────────────────────────────────
    private fun initializeViews() {
        drawerLayout        = findViewById(R.id.drawerLayout)
        playerView          = findViewById(R.id.playerView)
        btnPlayPause        = findViewById(R.id.btnPlayPause)
        btnForward          = findViewById(R.id.btnForward)
        btnRewind           = findViewById(R.id.btnRewind)
        btnMenu             = findViewById(R.id.btnMenu)
        btnOpenFile         = findViewById(R.id.btnOpenFile)
        seekBar             = findViewById(R.id.seekBar)
        progressFill        = findViewById(R.id.progressFill)
        progressThumb       = findViewById(R.id.progressThumb)
        tvCurrentTime       = findViewById(R.id.tvCurrentTime)
        tvDuration          = findViewById(R.id.tvDuration)
        tvTitle             = findViewById(R.id.tvTitle)
        playerControls      = findViewById(R.id.playerControls)
        topBar              = findViewById(R.id.topBar)
        emptyState          = findViewById(R.id.emptyState)
        volumeOverlay       = findViewById(R.id.volumeOverlay)
        volumeBar           = findViewById(R.id.volumeBar)
        tvVolumePercent     = findViewById(R.id.tvVolumePercent)
        brightnessOverlay   = findViewById(R.id.brightnessOverlay)
        brightnessBar       = findViewById(R.id.brightnessBar)
        tvBrightnessPercent = findViewById(R.id.tvBrightnessPercent)
    }

    // ─── تهيئة المشغل ─────────────────────────────────────────────
    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.useController = false

        player.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
            }

            // ─── معالجة الأخطاء ───────────────────────────────────
            override fun onPlayerError(error: PlaybackException) {
                val message = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "تعذّر الاتصال بالشبكة. تحقق من الإنترنت."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "الملف غير موجود أو تم نقله."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "تنسيق الفيديو غير مدعوم."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "انتهت مهلة الاتصال. حاول مجدداً."
                    else -> "حدث خطأ أثناء التشغيل."
                }
                showError(message)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    updatePlayPauseIcon(false)
                    showControls()
                }
            }
        })
    }

    // ─── أزرار التحكم ─────────────────────────────────────────────
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

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
            resetHideTimer()
        }

        // زر اختيار ملف فيديو
        btnOpenFile.setOnClickListener {
            checkPermissionAndOpenPicker()
        }

        playerView.setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
        }
    }

    // ─── اختيار الفيديو من الجهاز ─────────────────────────────────
    private fun checkPermissionAndOpenPicker() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED) {
            openVideoPicker()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_PERMISSION)
        }
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
            type = "video/*"
        }
        startActivityForResult(intent, REQUEST_PICK_VIDEO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openVideoPicker()
            } else {
                Toast.makeText(this, "يجب منح إذن الوصول للملفات لاختيار فيديو.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_VIDEO && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            loadVideo(uri)
        }
    }

    // ─── تحميل الفيديو ────────────────────────────────────────────
    private fun loadVideo(uri: Uri) {
        // استخراج اسم الفيديو
        currentVideoTitle = getVideoTitle(uri)
        tvTitle.text = currentVideoTitle

        // إخفاء الشاشة الفارغة
        emptyState.visibility = View.GONE
        playerControls.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        playerControls.alpha = 1f
        topBar.alpha = 1f
        controlsVisible = true

        // تشغيل الفيديو
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        scheduleHideControls()
    }

    private fun getVideoTitle(uri: Uri): String {
        return try {
            val cursor = contentResolver.query(uri, arrayOf(MediaStore.Video.Media.TITLE), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "فيديو"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "فيديو"
        }
    }

    // ─── فتح التطبيق من ملف خارجي ─────────────────────────────────
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            loadVideo(uri)
        } else {
            // إظهار شاشة اختيار الفيديو
            emptyState.visibility = View.VISIBLE
            playerControls.alpha = 0f
            playerControls.visibility = View.INVISIBLE
            topBar.alpha = 0f
            topBar.visibility = View.INVISIBLE
            controlsVisible = false
        }
    }

    // ─── شريط التقدم ──────────────────────────────────────────────
    private fun setupSeekBar() {
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo(duration * progress / 1000L)
                        updateProgressBarUI(progress, 1000)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isDraggingSeekBar = true
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isDraggingSeekBar = false
                resetHideTimer()
            }
        })
    }

    // ─── تحديث التقدم ─────────────────────────────────────────────
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!isDraggingSeekBar && player.duration > 0) {
                val position = player.currentPosition
                val duration = player.duration
                val progress = (position * 1000L / duration).toInt()
                seekBar.progress = progress
                updateProgressBarUI(progress, 1000)
                tvCurrentTime.text = formatTime(position)
                tvDuration.text = formatTime(duration)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startProgressUpdater() { handler.post(progressUpdater) }

    private fun updateProgressBarUI(progress: Int, max: Int) {
        progressFill.post {
            val parentWidth = (progressFill.parent as? View)?.width ?: return@post
            val fillWidth = (parentWidth * progress / max).coerceAtLeast(0)
            progressFill.layoutParams.width = fillWidth
            progressFill.requestLayout()
            val tp = progressThumb.layoutParams as? ViewGroup.MarginLayoutParams
            tp?.marginStart = (fillWidth - 7.dpToPx()).coerceAtLeast(0)
            progressThumb.layoutParams = tp
        }
    }

    // ─── الإيماءات ────────────────────────────────────────────────
    private fun setupGestures() {
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    distanceX: Float, distanceY: Float
                ): Boolean {
                    // فقط عند وجود فيديو
                    if (player.duration <= 0) return false
                    val x = e1?.x ?: 0f
                    val percent = -distanceY / 800f
                    if (x < resources.displayMetrics.widthPixels / 2) {
                        adjustBrightness(percent)
                    } else {
                        adjustVolume(percent)
                    }
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (player.duration > 0) {
                        if (controlsVisible) hideControls() else showControls()
                    }
                    return true
                }
            })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    // ─── الصوت مع Overlay ─────────────────────────────────────────
    private fun adjustVolume(fraction: Float) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVol = (am.getStreamVolume(AudioManager.STREAM_MUSIC) +
                (fraction * max).toInt()).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        showVolumeOverlay(if (max > 0) newVol * 100 / max else 0)
        resetHideTimer()
    }

    private fun showVolumeOverlay(percent: Int) {
        tvVolumePercent.text = "$percent%"
        val h = (120.dpToPx() * percent / 100).coerceIn(0, 120.dpToPx())
        volumeBar.layoutParams.height = h
        volumeBar.requestLayout()
        showOverlay(volumeOverlay)
    }

    // ─── السطوع مع Overlay ────────────────────────────────────────
    private fun adjustBrightness(fraction: Float) {
        val lp = window.attributes
        var b = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        b = (b + fraction).coerceIn(0.01f, 1f)
        lp.screenBrightness = b
        window.attributes = lp
        showBrightnessOverlay((b * 100).toInt())
        resetHideTimer()
    }

    private fun showBrightnessOverlay(percent: Int) {
        tvBrightnessPercent.text = "$percent%"
        val h = (120.dpToPx() * percent / 100).coerceIn(0, 120.dpToPx())
        brightnessBar.layoutParams.height = h
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
        handler.postDelayed(overlayHideRunnable!!, 1500)
    }

    // ─── إظهار / إخفاء أدوات التحكم ──────────────────────────────
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

    // ─── مساعدات ──────────────────────────────────────────────────
    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun animateButtonClick(view: View) {
        view.animate().scaleX(0.82f).scaleY(0.82f).setDuration(75).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updatePlayPauseIcon(false)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    // ─── دورة الحياة ──────────────────────────────────────────────
    override fun onPause() { super.onPause(); player.pause() }

    override fun onResume() {
        super.onResume()
        if (player.playbackState != Player.STATE_ENDED && player.duration > 0)
            player.play()
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

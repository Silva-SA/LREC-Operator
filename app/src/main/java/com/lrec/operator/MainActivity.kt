package com.lrec.operator

import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var initialY = 0f
private var isAdjustingVolume = false
private var isAdjustingBrightness = false

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                playVideo(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
val btnRewind = findViewById<ImageButton>(R.id.btnRewind)
val btnForward = findViewById<ImageButton>(R.id.btnForward)

btnPlayPause.setOnClickListener {
    if (player?.isPlaying == true) {
        player?.pause()
        btnPlayPause.setImageResource(R.drawable.ic_play)
    } else {
        player?.play()
        btnPlayPause.setImageResource(R.drawable.ic_pause)
    }
}

btnRewind.setOnClickListener {
    player?.seekBack()
}

btnForward.setOnClickListener {
    player?.seekForward()
}

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_pick -> {
                    pickVideo.launch("video/*")
                }
                R.id.nav_speed -> {
                    player?.setPlaybackSpeed(1.5f)
                }
                R.id.nav_theme -> {
                    AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_YES
                    )
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun playVideo(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {

    val screenWidth = resources.displayMetrics.widthPixels

    when (event.action) {

        MotionEvent.ACTION_DOWN -> {
            initialY = event.y
            isAdjustingVolume = event.x > screenWidth / 2
            isAdjustingBrightness = !isAdjustingVolume
        }

        MotionEvent.ACTION_MOVE -> {
            val delta = initialY - event.y
            val fraction = delta / resources.displayMetrics.heightPixels

            if (isAdjustingVolume) {
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val newVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + fraction * maxVol).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.coerceIn(0, maxVol), 0)
            }

            if (isAdjustingBrightness) {
                val layoutParams = window.attributes
                layoutParams.screenBrightness =
                    (layoutParams.screenBrightness + fraction).coerceIn(0f, 1f)
                window.attributes = layoutParams
            }
        }
    }
    return true
}
}

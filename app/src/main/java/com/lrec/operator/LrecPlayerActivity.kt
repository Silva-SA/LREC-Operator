package com.lrec.operator

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LrecPlayerActivity : AppCompatActivity() {

    private var useGpu = false

    private var glSurface: LrecGLSurface? = null
    private var cpuImageView: ImageView? = null

    private lateinit var playBtn: ImageButton
    private lateinit var pauseBtn: ImageButton
    private lateinit var stopBtn: ImageButton

    private var engineHandle: Long = 0

    private var width = 0
    private var height = 0
    private var totalFrames = 0

    private var bitmap: Bitmap? = null

    private val frameIndex = AtomicInteger(0)
    private val isPlaying = AtomicBoolean(false)

    private val uiHandler = Handler(Looper.getMainLooper())

    private var decodeThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_lrec_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkGpuSupport()

        if (useGpu) {
            glSurface = findViewById(R.id.glSurface)
        } else {
            cpuImageView = findViewById(R.id.imageScreen)
        }

        playBtn = findViewById(R.id.btnPlay)
        pauseBtn = findViewById(R.id.btnPause)
        stopBtn = findViewById(R.id.btnStop)

        loadFile()

        playBtn.setOnClickListener { startPlayback() }
        pauseBtn.setOnClickListener { stopPlayback() }
        stopBtn.setOnClickListener { stopPlayback() }
    }

    private fun checkGpuSupport() {

        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val config = activityManager.deviceConfigurationInfo

        useGpu = config.reqGlEsVersion >= 0x20000
    }

    private fun loadFile() {

        val uri = intent.data ?: return

        Thread {

            val filePath = copyToCache(uri)

            engineHandle = LrecEngine.open(filePath)

            width = LrecEngine.getWidth(engineHandle)
            height = LrecEngine.getHeight(engineHandle)
            totalFrames = LrecEngine.getTotalFrames(engineHandle)

            bitmap = Bitmap.createBitmap(
                width,
                height,
                Config.RGB_565
            )

        }.start()
    }

    private fun startPlayback() {

        if (isPlaying.get()) return

        isPlaying.set(true)

        decodeThread = Thread {

            while (isPlaying.get()) {

                val frame = frameIndex.get()

                if (frame >= totalFrames) {
                    isPlaying.set(false)
                    break
                }

                val frameData = LrecEngine.decodeFrameNative(
                    engineHandle,
                    frame
                )

                val buffer = ByteBuffer.wrap(frameData)

                bitmap?.copyPixelsFromBuffer(buffer)

                uiHandler.post {

                    if (useGpu) {
                        glSurface?.updateFrame(bitmap!!)
                    } else {
                        cpuImageView?.setImageBitmap(bitmap)
                    }

                }

                frameIndex.incrementAndGet()

                Thread.sleep(16)
            }

        }

        decodeThread?.start()
    }

    private fun stopPlayback() {

        isPlaying.set(false)

        frameIndex.set(0)
    }

    private fun copyToCache(uri: Uri): String {

        val file = File(cacheDir, "temp.lrec")

        contentResolver.openInputStream(uri)?.use { input ->

            FileOutputStream(file).use { output ->

                val buffer = ByteArray(65536)

                while (true) {

                    val read = input.read(buffer)

                    if (read <= 0) break

                    output.write(buffer, 0, read)
                }

            }

        }

        return file.absolutePath
    }

    override fun onDestroy() {

        isPlaying.set(false)

        decodeThread?.interrupt()

        if (engineHandle != 0L) {
            LrecEngine.close(engineHandle)
        }

        super.onDestroy()
    }
}

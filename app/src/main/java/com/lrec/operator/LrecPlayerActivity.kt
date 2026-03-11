package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.net.Uri
import android.os.*
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LrecPlayerActivity : AppCompatActivity() {

    private lateinit var imageViewScreen: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var btnStop: ImageButton

    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private var engineHandle: Long = 0
    private var totalFrames = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var durationMs: Long = 0

    // تغيير نوع البكسل لتقليل الذاكرة
    private var screenBitmap: Bitmap? = null
    private var pixelBuffer: ShortArray? = null

    private val isPlaying = AtomicBoolean(false)
    private val currentFrame = AtomicInteger(0)

    private val uiHandler = Handler(Looper.getMainLooper())

    private var decodeThread: Thread? = null

    companion object {
        private const val SEEK_BAR_MAX = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_lrec_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        imageViewScreen = findViewById(R.id.imageScreen)
        seekBar = findViewById(R.id.seekBar)

        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)

        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        loadFile()
    }

    private fun loadFile() {

        val uri = intent.data ?: return

        Thread {

            val path = copyToCache(uri)

            engineHandle = LrecEngine.open(path)

            totalFrames = LrecEngine.getTotalFrames(engineHandle)
            screenWidth = LrecEngine.getWidth(engineHandle)
            screenHeight = LrecEngine.getHeight(engineHandle)
            durationMs = LrecEngine.getDuration(engineHandle)

            pixelBuffer = ShortArray(screenWidth * screenHeight)

            screenBitmap = Bitmap.createBitmap(
                screenWidth,
                screenHeight,
                Config.RGB_565
            )

            runOnUiThread {
                imageViewScreen.setImageBitmap(screenBitmap)
                startDecoder()
            }

        }.start()
    }

    private fun startDecoder() {

        isPlaying.set(true)

        decodeThread = Thread {

            while (isPlaying.get()) {

                val frame = currentFrame.get()

                if (frame >= totalFrames) {
                    isPlaying.set(false)
                    break
                }

                LrecEngine.decodeFrame565(
                    engineHandle,
                    frame,
                    pixelBuffer
                )

                screenBitmap?.copyPixelsFromBuffer(
                    java.nio.ShortBuffer.wrap(pixelBuffer)
                )

                uiHandler.post {
                    imageViewScreen.invalidate()
                }

                currentFrame.incrementAndGet()

                Thread.sleep(33)
            }

        }

        decodeThread!!.start()
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

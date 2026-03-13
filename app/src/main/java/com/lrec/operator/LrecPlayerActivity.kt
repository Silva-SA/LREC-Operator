package com.lrec.operator

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LrecPlayerActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────
    private lateinit var imageView:      ImageView
    private lateinit var btnPlayPause:   ImageButton
    private lateinit var btnRewind:      ImageButton
    private lateinit var btnForward:     ImageButton
    private lateinit var btnBack:        ImageButton
    private lateinit var btnInfo:        ImageButton
    private lateinit var btnChat:        ImageButton
    private lateinit var btnCloseChat:   ImageButton
    private lateinit var seekBar:        SeekBar
    private lateinit var tvCurrentTime:  TextView
    private lateinit var tvDuration:     TextView
    private lateinit var tvTitle:        TextView
    private lateinit var tvAudioStatus:  TextView
    private lateinit var playerControls: LinearLayout
    private lateinit var topBar:         LinearLayout
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvStatus:       TextView
    private lateinit var chatPanel:      LinearLayout
    private lateinit var chatRecycler:   RecyclerView
    private lateinit var tvChatStatus:   TextView

    // ══════════════════════════════════════════════════════════════════
    //  الصيغة المكتشفة + بيانات التشغيل
    // ══════════════════════════════════════════════════════════════════

    private var detectedFormat = LrecFormatDetector.Format.UNKNOWN

    // ── صيغة Block ────────────────────────────────────────────────────
    private var lrecParser:  LrecParser?                 = null
    private var frames:      List<LrecParser.LrecFrame>   = emptyList()
    private var audioBlocks: List<LrecParser.AudioBlock>  = emptyList()

    // ── صيغة AVI ──────────────────────────────────────────────────────
    private var aviParser:   LrecAviParser?              = null
    private var aviResult:   LrecAviParser.ParseResult?  = null
    private var aviFile:     File?                       = null
    private var aviRaf:      RandomAccessFile?           = null
    private var videoDecoder: LrecVideoDecoder?          = null

    // ── مشترك ─────────────────────────────────────────────────────────
    private val chatManager  = LrecChatManager()
    private val audioPlayer  = LrecAudioPlayer()

    private var canvasWidth  = 0
    private var canvasHeight = 0
    private var workBitmap:    Bitmap? = null
    private var displayBitmap: Bitmap? = null

    private val frameIndex   = AtomicInteger(0)
    private val isPlaying    = AtomicBoolean(false)
    private val isParsed     = AtomicBoolean(false)

    private val uiHandler    = Handler(Looper.getMainLooper())
    private var playThread:  Thread? = null

    private var audioEnabled    = false
    private var detectedCodec   = LrecAudioPlayer.AudioCodec.ULAW
    private var audioStatusText = "لا يوجد صوت"

    private var controlsVisible = true
    private val HIDE_DELAY_MS   = 3_500L
    private val hideRunnable    = Runnable { hideControls() }

    private var chatPanelVisible = false
    private var chatAdapter: ChatAdapter? = null

    private lateinit var gestureDetector: GestureDetector

    // ── دورة الحياة ───────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        setContentView(R.layout.activity_lrec_player)
        bindViews()
        setupListeners()
        loadFile()
    }

    override fun onPause()  { super.onPause();  pausePlayback() }
    override fun onResume() { super.onResume(); enterFullscreen() }

    override fun onDestroy() {
        isPlaying.set(false)
        playThread?.interrupt()
        uiHandler.removeCallbacksAndMessages(null)
        audioPlayer.release()
        videoDecoder?.release()
        aviRaf?.close()
        workBitmap?.recycle()
        displayBitmap?.recycle()
        workBitmap    = null
        displayBitmap = null
        super.onDestroy()
    }

    // ── ربط العناصر ───────────────────────────────────────────────────
    private fun bindViews() {
        imageView      = findViewById(R.id.lrecImageView)
        btnPlayPause   = findViewById(R.id.lrecBtnPlayPause)
        btnRewind      = findViewById(R.id.lrecBtnRewind)
        btnForward     = findViewById(R.id.lrecBtnForward)
        btnBack        = findViewById(R.id.lrecBtnBack)
        btnInfo        = findViewById(R.id.lrecBtnInfo)
        btnChat        = findViewById(R.id.lrecBtnChat)
        btnCloseChat   = findViewById(R.id.lrecBtnCloseChat)
        seekBar        = findViewById(R.id.lrecSeekBar)
        tvCurrentTime  = findViewById(R.id.lrecTvCurrentTime)
        tvDuration     = findViewById(R.id.lrecTvDuration)
        tvTitle        = findViewById(R.id.lrecTvTitle)
        playerControls = findViewById(R.id.lrecPlayerControls)
        topBar         = findViewById(R.id.lrecTopBar)
        loadingOverlay = findViewById(R.id.lrecLoadingOverlay)
        progressBar    = findViewById(R.id.lrecProgressBar)
        tvStatus       = findViewById(R.id.lrecTvStatus)
        chatPanel      = findViewById(R.id.lrecChatPanel)
        chatRecycler   = findViewById(R.id.lrecChatRecycler)
        tvChatStatus   = findViewById(R.id.lrecTvChatStatus)

        // tvAudioStatus اختياري — إن لم يوجد في XML يُهمَل
        tvAudioStatus  = TextView(this)

        setControlsEnabled(false)
        btnChat.isEnabled = false
        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
    }

    // ── الأحداث ───────────────────────────────────────────────────────
    private fun setupListeners() {
        btnBack.setOnClickListener      { finish() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnRewind.setOnClickListener    { seekRelativeSeconds(-10) }
        btnForward.setOnClickListener   { seekRelativeSeconds(+10) }
        btnInfo.setOnClickListener      { showInfoDialog() }
        btnChat.setOnClickListener      { toggleChatPanel(); showControls() }
        btnCloseChat.setOnClickListener { hideChatPanel() }

        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val totalFrames = getTotalFrames()
                    if (totalFrames > 0) {
                        val target = (progress.toLong() * totalFrames / 1000L)
                            .toInt().coerceIn(0, totalFrames - 1)
                        frameIndex.set(target)
                        updateTimeTextUI()
                        showControls()
                        syncChatToTime(getTimeAtFrame(target))
                        if (!isPlaying.get()) renderCurrentFrameAsync()
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                uiHandler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) { scheduleHide() }
        })

        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (controlsVisible) hideControls() else showControls(); return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    togglePlayPause(); return true
                }
            })
        imageView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
    }

    // ══════════════════════════════════════════════════════════════════
    //  تحميل الملف — يكتشف الصيغة تلقائياً
    // ══════════════════════════════════════════════════════════════════

    private fun loadFile() {
        val uri = intent.data ?: run { finish(); return }
        tvTitle.text = uri.lastPathSegment ?: "ملف .lrec"
        showLoading(true, "جاري تحليل الملف…")

        Thread {
            val filePath = copyToCache(uri)
            val file     = File(filePath)

            // ── اكتشاف الصيغة ──────────────────────────────────────
            val format = LrecFormatDetector.detect(file)

            val ok = when (format) {
                LrecFormatDetector.Format.AVI   -> loadAviFile(file)
                LrecFormatDetector.Format.BLOCK -> loadBlockFile(file)
                LrecFormatDetector.Format.UNKNOWN -> {
                    // Fallback: جرّب Block أولاً ثم AVI
                    loadBlockFile(file) || loadAviFile(file)
                }
            }

            runOnUiThread { onFileLoaded(ok, format) }
        }.start()
    }

    // ── تحميل صيغة Block ──────────────────────────────────────────────
    private fun loadBlockFile(file: File): Boolean {
        val parser = LrecParser(file)
        val ok     = parser.parse()
        if (!ok) return false

        lrecParser      = parser
        frames          = parser.getAllFrames()
        audioBlocks     = parser.getAudioBlocks()
        detectedFormat  = LrecFormatDetector.Format.BLOCK

        // الدردشة
        chatManager.loadFromChatEntries(parser.getChatEntries())

        // الصوت
        if (audioBlocks.isNotEmpty()) {
            val sr = audioBlocks[0].sampleRate
            audioPlayer.initialize(sr, audioBlocks[0].channels == 2)
            detectedCodec   = audioPlayer.detectBestCodec(
                audioBlocks.take(20).map { it.rawData },
                audioBlocks[0].dataOffset
            )
            audioEnabled    = true
            audioStatusText = audioPlayer.codecName
        } else {
            audioStatusText = if (parser.hasAudioBlocks) "مشفّر" else "لا يوجد"
        }

        canvasWidth  = parser.metadata.screenWidth
        canvasHeight = parser.metadata.screenHeight
        return true
    }

    // ── تحميل صيغة AVI ────────────────────────────────────────────────
    private fun loadAviFile(file: File): Boolean {
        val parser = LrecAviParser(file)
        val result = parser.parse()
        if (!result.isValid) return false

        aviParser      = parser
        aviResult      = result
        aviFile        = file
        aviRaf         = RandomAccessFile(file, "r")
        detectedFormat = LrecFormatDetector.Format.AVI

        canvasWidth  = result.videoWidth.takeIf  { it > 0 } ?: 1024
        canvasHeight = result.videoHeight.takeIf { it > 0 } ?: 768

        // تهيئة H.263 decoder
        if (result.videoChunks.isNotEmpty()) {
            videoDecoder = LrecVideoDecoder(canvasWidth, canvasHeight)
            if (!videoDecoder!!.initialize()) {
                videoDecoder = null
            }
        }

        // الدردشة من كتل النص
        val raf = aviRaf
        if (raf != null) {
            result.textChunks.forEach { chunk ->
                try {
                    val data = ByteArray(chunk.size)
                    raf.seek(chunk.offset)
                    raf.read(data)
                    chatManager.addRawBlock(data, chunk.timestampMs)
                } catch (e: Exception) { }
            }
        }

        // الصوت
        if (result.audioChunks.isNotEmpty()) {
            val af = result.audioFormat
            audioPlayer.initialize(af.sampleRate, af.channels == 2)
            detectedCodec = when (af.codec) {
                G711Codec.Codec.ALAW  -> LrecAudioPlayer.AudioCodec.ALAW
                G711Codec.Codec.PCM8  -> LrecAudioPlayer.AudioCodec.PCM8
                G711Codec.Codec.PCM16 -> LrecAudioPlayer.AudioCodec.PCM16
                else                  -> LrecAudioPlayer.AudioCodec.ULAW
            }
            audioEnabled    = true
            audioStatusText = when (af.formatTag) {
                7    -> "G.711 μ-law"
                6    -> "G.711 A-law"
                1    -> "PCM ${af.bitsPerSample}-bit"
                else -> "غير معروف (${af.formatTag})"
            }
        } else {
            audioStatusText = "لا يوجد صوت"
        }

        return true
    }

    private fun onFileLoaded(ok: Boolean, format: LrecFormatDetector.Format) {
        showLoading(false)
        if (!ok) {
            tvStatus.text       = "❌ تعذّر فتح الملف"
            tvStatus.visibility = View.VISIBLE
            return
        }

        isParsed.set(true)

        workBitmap    = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        displayBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)

        setupChatPanel()
        tvDuration.text    = formatTime(getDurationMs())
        tvCurrentTime.text = "00:00"
        tvAudioStatus.text = audioStatusText
        tvStatus.text      = buildStatusText(format)
        setControlsEnabled(true)
        btnChat.isEnabled  = true
        startPlayback()
        showControls()
    }

    private fun buildStatusText(format: LrecFormatDetector.Format): String = buildString {
        append("${getTotalFrames()} إطار | ${canvasWidth}×${canvasHeight}")
        append("  |  🔊 $audioStatusText")
        val msgCount = chatManager.messageCount
        if (msgCount > 0) append("  |  💬 $msgCount رسالة")
        append("  [${format.name}]")
    }

    // ══════════════════════════════════════════════════════════════════
    //  واجهات موحّدة للصيغتين
    // ══════════════════════════════════════════════════════════════════

    private fun getTotalFrames(): Int = when (detectedFormat) {
        LrecFormatDetector.Format.AVI   -> aviResult?.videoChunks?.size ?: 0
        LrecFormatDetector.Format.BLOCK -> frames.size
        else -> frames.size.takeIf { it > 0 } ?: (aviResult?.videoChunks?.size ?: 0)
    }

    private fun getDurationMs(): Long = when (detectedFormat) {
        LrecFormatDetector.Format.AVI   -> aviResult?.durationMs ?: 0L
        LrecFormatDetector.Format.BLOCK -> lrecParser?.getDurationMs() ?: 0L
        else -> lrecParser?.getDurationMs() ?: aviResult?.durationMs ?: 0L
    }

    private fun getFps(): Int {
        return when (detectedFormat) {
            LrecFormatDetector.Format.AVI -> {
                val fps = aviResult?.videoStream?.fps ?: 25.0
                fps.toInt().coerceAtLeast(1)
            }
            else -> lrecParser?.metadata?.fps?.let {
                if (it <= 0) LrecParser.DEFAULT_FPS else it
            } ?: LrecParser.DEFAULT_FPS
        }
    }

    private fun getTimeAtFrame(idx: Int): Long {
        val fps = getFps()
        return idx.toLong() * 1000L / fps
    }

    // ══════════════════════════════════════════════════════════════════
    //  لوحة الدردشة
    // ══════════════════════════════════════════════════════════════════

    private fun setupChatPanel() {
        val messages = chatManager.allMessages
        if (messages.isNotEmpty()) {
            // تحويل إلى ChatEntry للـ adapter
            val entries = messages.map { msg ->
                LrecParser.ChatEntry(msg.timestampMs, msg.sender, msg.text, true)
            }
            chatAdapter = ChatAdapter(entries)
            chatRecycler.adapter    = chatAdapter
            tvChatStatus.visibility = View.GONE
            chatRecycler.visibility = View.VISIBLE
            btnChat.setColorFilter(Color.parseColor("#4FC3F7"))
        } else {
            tvChatStatus.text       = "لا توجد رسائل دردشة"
            tvChatStatus.visibility = View.VISIBLE
            chatRecycler.visibility = View.GONE
            btnChat.alpha           = 0.5f
        }
    }

    private fun toggleChatPanel() { if (chatPanelVisible) hideChatPanel() else showChatPanel() }

    private fun showChatPanel() {
        if (chatPanelVisible) return
        chatPanelVisible     = true
        chatPanel.visibility = View.VISIBLE
        chatPanel.alpha      = 0f
        chatPanel.translationX = chatPanel.width.toFloat()
        chatPanel.animate().alpha(1f).translationX(0f).setDuration(250).start()
        btnChat.setColorFilter(Color.parseColor("#4FC3F7"))
        syncChatToTime(getTimeAtFrame(frameIndex.get()))
    }

    private fun hideChatPanel() {
        if (!chatPanelVisible) return
        chatPanelVisible = false
        chatPanel.animate().alpha(0f)
            .translationX(chatPanel.width.toFloat())
            .setDuration(200)
            .withEndAction { chatPanel.visibility = View.GONE }
            .start()
    }

    private fun syncChatToTime(timeMs: Long) {
        if (!chatPanelVisible || chatManager.messageCount == 0) return
        val messages  = chatManager.allMessages
        val adapter   = chatAdapter ?: return
        val activeIdx = messages.indexOfLast { it.timestampMs <= timeMs }
        adapter.setActiveIndex(activeIdx)
        if (activeIdx >= 0) chatRecycler.scrollToPosition(activeIdx)
    }

    // ══════════════════════════════════════════════════════════════════
    //  التشغيل
    // ══════════════════════════════════════════════════════════════════

    private fun togglePlayPause() {
        if (isPlaying.get()) pausePlayback() else startPlayback()
        showControls()
    }

    private fun startPlayback() {
        if (!isParsed.get() || getTotalFrames() == 0) return
        if (isPlaying.get()) return
        if (frameIndex.get() >= getTotalFrames()) frameIndex.set(0)

        isPlaying.set(true)
        btnPlayPause.setImageResource(R.drawable.ic_pause)

        startAudioPlayback()
        chatManager.resetCursor(getTimeAtFrame(frameIndex.get()))

        val fps          = getFps()
        val frameDelayMs = (1000L / fps).coerceAtLeast(16L)

        playThread = Thread {
            while (isPlaying.get()) {
                val idx = frameIndex.get()
                if (idx >= getTotalFrames()) {
                    uiHandler.post {
                        isPlaying.set(false)
                        audioPlayer.stop()
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                        showControls()
                    }
                    break
                }

                val timeMs = getTimeAtFrame(idx)
                audioPlayer.syncVideoTime(timeMs)

                // ── فك إطار الفيديو ──────────────────────────────────
                val argbPixels: IntArray? = decodeFrameAt(idx, timeMs)

                val wBmp = workBitmap
                val dBmp = displayBitmap

                if (argbPixels != null && wBmp != null && dBmp != null) {
                    wBmp.setPixels(argbPixels, 0, wBmp.width, 0, 0,
                        minOf(argbPixels.size / wBmp.height, wBmp.width),
                        wBmp.height)
                    val canvas = android.graphics.Canvas(dBmp)
                    canvas.drawBitmap(wBmp, 0f, 0f, null)
                }

                // ── رسائل الدردشة الجديدة ────────────────────────────
                val newMsgs = chatManager.getNewMessages(timeMs)

                val snap       = dBmp
                val currentIdx = frameIndex.incrementAndGet()

                uiHandler.post {
                    if (snap != null) imageView.setImageBitmap(snap)
                    updateProgressUI(currentIdx)
                    if (chatPanelVisible && newMsgs.isNotEmpty()) {
                        syncChatToTime(timeMs)
                    }
                }

                try { Thread.sleep(frameDelayMs) }
                catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    private fun startAudioPlayback() {
        if (!audioEnabled) return

        val fps           = getFps()
        val currentTimeMs = getTimeAtFrame(frameIndex.get())

        when (detectedFormat) {
            LrecFormatDetector.Format.BLOCK -> {
                if (audioBlocks.isEmpty()) return
                val startIdx = audioBlocks.indexOfFirst {
                    it.timestampMs >= currentTimeMs }.coerceAtLeast(0)
                audioPlayer.startPlayback(
                    audioBlocks = audioBlocks.map { it.rawData },
                    startIndex  = startIdx,
                    dataOffset  = audioBlocks.firstOrNull()?.dataOffset ?: 8,
                    codec       = detectedCodec
                )
            }
            LrecFormatDetector.Format.AVI -> {
                val result = aviResult ?: return
                val raf    = aviRaf    ?: return
                val chunks = result.audioChunks
                if (chunks.isEmpty()) return
                val startIdx = chunks.indexOfFirst {
                    it.timestampMs >= currentTimeMs }.coerceAtLeast(0)
                val audioData = chunks.drop(startIdx).map { chunk ->
                    try {
                        val data = ByteArray(chunk.size)
                        raf.seek(chunk.offset)
                        raf.read(data)
                        data
                    } catch (e: Exception) { ByteArray(0) }
                }
                audioPlayer.startPlayback(
                    audioBlocks = audioData,
                    startIndex  = 0,
                    dataOffset  = 0,
                    codec       = detectedCodec
                )
            }
            else -> { }
        }
    }

    // ── فك إطار الفيديو حسب الصيغة ──────────────────────────────────

    private fun decodeFrameAt(idx: Int, timeMs: Long): IntArray? {
        return when (detectedFormat) {
            LrecFormatDetector.Format.BLOCK -> decodeBlockFrame(idx)
            LrecFormatDetector.Format.AVI   -> decodeAviFrame(idx, timeMs)
            else -> decodeBlockFrame(idx) ?: decodeAviFrame(idx, timeMs)
        }
    }

    private fun decodeBlockFrame(idx: Int): IntArray? {
        val parser = lrecParser ?: return null
        val frame  = frames.getOrNull(idx) ?: return null
        val fd     = try { parser.decodeScreenFrame(frame) } catch (_: Exception) { null }
                     ?: return null
        val wBmp   = workBitmap ?: return null
        val result = IntArray(wBmp.width * wBmp.height)
        if (fd.isFullFrame) {
            System.arraycopy(fd.pixels, 0, result, 0,
                minOf(fd.pixels.size, result.size))
        } else {
            // دلتا — نحتاج الصورة السابقة محفوظة في workBitmap
            val temp = IntArray(wBmp.width * wBmp.height)
            wBmp.getPixels(temp, 0, wBmp.width, 0, 0, wBmp.width, wBmp.height)
            val safeX = fd.x.coerceIn(0, wBmp.width  - 1)
            val safeY = fd.y.coerceIn(0, wBmp.height - 1)
            val safeW = fd.width .coerceAtMost(wBmp.width  - safeX)
            val safeH = fd.height.coerceAtMost(wBmp.height - safeY)
            for (row in 0 until safeH) {
                for (col in 0 until safeW) {
                    val dstIdx = (safeY + row) * wBmp.width + (safeX + col)
                    val srcIdx = row * fd.width + col
                    if (dstIdx < temp.size && srcIdx < fd.pixels.size)
                        temp[dstIdx] = fd.pixels[srcIdx]
                }
            }
            return temp
        }
        return result
    }

    private fun decodeAviFrame(idx: Int, timeMs: Long): IntArray? {
        val result = aviResult ?: return null
        val raf    = aviRaf    ?: return null
        val chunk  = result.videoChunks.getOrNull(idx) ?: return null
        val decoder = videoDecoder ?: return null

        return try {
            val data = ByteArray(chunk.size)
            raf.seek(chunk.offset)
            raf.read(data)
            decoder.decodeFrame(data, timeMs * 1000L)
        } catch (e: Exception) { null }
    }

    private fun pausePlayback() {
        if (!isPlaying.get()) return
        isPlaying.set(false)
        playThread?.interrupt()
        playThread = null
        audioPlayer.pause()
        btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    // ── التقديم والترجيع ──────────────────────────────────────────────
    private fun seekRelativeSeconds(seconds: Int) {
        if (!isParsed.get()) return
        val fps    = getFps()
        val newIdx = (frameIndex.get() + seconds * fps).coerceIn(0, getTotalFrames() - 1)
        frameIndex.set(newIdx)
        updateProgressUI(newIdx)
        updateTimeTextUI()
        showControls()

        val timeMs = getTimeAtFrame(newIdx)
        syncChatToTime(timeMs)
        chatManager.resetCursor(timeMs)

        if (audioEnabled && isPlaying.get()) {
            audioPlayer.flushQueue()
        }
        if (!isPlaying.get()) renderCurrentFrameAsync()
    }

    private fun renderCurrentFrameAsync() {
        val idx = frameIndex.get().coerceIn(0, getTotalFrames() - 1)
        Thread {
            val pixels = decodeFrameAt(idx, getTimeAtFrame(idx))
            val wBmp   = workBitmap
            val dBmp   = displayBitmap
            if (pixels != null && wBmp != null && dBmp != null) {
                wBmp.setPixels(pixels, 0, wBmp.width, 0, 0, wBmp.width, wBmp.height)
                val canvas = android.graphics.Canvas(dBmp)
                canvas.drawBitmap(wBmp, 0f, 0f, null)
                uiHandler.post { imageView.setImageBitmap(dBmp) }
            }
        }.start()
    }

    // ── حوار المعلومات ────────────────────────────────────────────────
    private fun showInfoDialog() {
        pausePlayback()
        val msg = buildString {
            appendLine("📹  معلومات الملف")
            appendLine("• الصيغة: ${detectedFormat.name}")
            appendLine("• الدقة: ${canvasWidth}×${canvasHeight}")
            appendLine("• الإطارات: ${getTotalFrames()}")
            appendLine("• المدة: ${formatTime(getDurationMs())}")
            appendLine()
            appendLine("─────────────────────────")
            appendLine("🔊  الصوت: $audioStatusText")
            if (audioEnabled) {
                when (detectedFormat) {
                    LrecFormatDetector.Format.BLOCK -> {
                        appendLine("• الكتل: ${audioBlocks.size}")
                        appendLine("• معدل التردد: ${audioBlocks.firstOrNull()?.sampleRate ?: 8000} Hz")
                    }
                    LrecFormatDetector.Format.AVI -> {
                        val af = aviResult?.audioFormat
                        appendLine("• القنوات: ${af?.channels ?: 1}")
                        appendLine("• معدل التردد: ${af?.sampleRate ?: 8000} Hz")
                        appendLine("• الكتل: ${aviResult?.audioChunks?.size ?: 0}")
                    }
                    else -> { }
                }
                appendLine("✅ يعمل")
            } else {
                appendLine("⚠️ غير متاح")
            }
            appendLine()
            appendLine("─────────────────────────")
            appendLine("💬  الدردشة: ${chatManager.messageCount} رسالة")
            if (chatManager.messageCount > 0) appendLine("✅ تم الاستخراج")
        }.trim()

        AlertDialog.Builder(this)
            .setTitle("ℹ️  معلومات الملف")
            .setMessage(msg)
            .setPositiveButton("إغلاق", null)
            .show()
        showControls()
    }

    // ══════════════════════════════════════════════════════════════════
    //  مساعدات الواجهة
    // ══════════════════════════════════════════════════════════════════

    private fun updateProgressUI(idx: Int) {
        val total = getTotalFrames()
        if (total == 0) return
        seekBar.progress = (idx.toLong() * 1000L / total).toInt().coerceIn(0, 1000)
        updateTimeTextUI()
    }

    private fun updateTimeTextUI() {
        tvCurrentTime.text = formatTime(getTimeAtFrame(frameIndex.get()))
    }

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

    private fun showLoading(show: Boolean, message: String = "") {
        if (show) {
            loadingOverlay.visibility = View.VISIBLE
            tvStatus.text = message
        } else {
            loadingOverlay.visibility = View.GONE
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPlayPause.isEnabled = enabled
        btnRewind.isEnabled    = enabled
        btnForward.isEnabled   = enabled
        seekBar.isEnabled      = enabled
    }

    private fun enterFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN        or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION   or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY  or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun copyToCache(uri: Uri): String {
        val f = File(cacheDir, "lrec_temp.lrec")
        contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(f).use { out ->
                val buf = ByteArray(65_536)
                while (true) { val n = inp.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
            }
        }
        return f.absolutePath
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else              String.format("%02d:%02d", m, sec)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Adapter الدردشة
    // ══════════════════════════════════════════════════════════════════

    private inner class ChatAdapter(
        private val entries: List<LrecParser.ChatEntry>
    ) : RecyclerView.Adapter<ChatAdapter.ChatVH>() {

        private var activeIndex = -1

        fun setActiveIndex(idx: Int) {
            val prev = activeIndex; activeIndex = idx
            if (prev >= 0) notifyItemChanged(prev)
            if (idx  >= 0) notifyItemChanged(idx)
        }

        inner class ChatVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTime:    TextView = v.findViewById(R.id.chatTvTime)
            val tvSender:  TextView = v.findViewById(R.id.chatTvSender)
            val tvMessage: TextView = v.findViewById(R.id.chatTvMessage)
            val container: View     = v.findViewById(R.id.chatContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChatVH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false))

        override fun onBindViewHolder(holder: ChatVH, position: Int) {
            val e = entries[position]
            holder.tvTime.text    = e.formattedTime
            holder.tvSender.text  = e.sender
            holder.tvMessage.text = e.message
            if (position == activeIndex) {
                holder.container.setBackgroundColor(Color.parseColor("#334FC3F7"))
                holder.tvMessage.setTextColor(Color.parseColor("#E3F2FD"))
            } else {
                holder.container.setBackgroundColor(Color.TRANSPARENT)
                holder.tvMessage.setTextColor(Color.parseColor("#CCCCCC"))
            }
        }

        override fun getItemCount() = entries.size
    }
}

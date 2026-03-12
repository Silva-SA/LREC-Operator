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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LrecPlayerActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────
    private lateinit var imageView:       ImageView
    private lateinit var btnPlayPause:    ImageButton
    private lateinit var btnRewind:       ImageButton
    private lateinit var btnForward:      ImageButton
    private lateinit var btnBack:         ImageButton
    private lateinit var btnInfo:         ImageButton
    private lateinit var btnChat:         ImageButton
    private lateinit var btnCloseChat:    ImageButton
    private lateinit var seekBar:         SeekBar
    private lateinit var tvCurrentTime:   TextView
    private lateinit var tvDuration:      TextView
    private lateinit var tvTitle:         TextView
    private lateinit var playerControls:  LinearLayout
    private lateinit var topBar:          LinearLayout
    private lateinit var loadingOverlay:  LinearLayout
    private lateinit var progressBar:     ProgressBar
    private lateinit var tvStatus:        TextView
    private lateinit var chatPanel:       LinearLayout
    private lateinit var chatRecycler:    RecyclerView
    private lateinit var tvChatStatus:    TextView

    // ── حالة المشغّل ──────────────────────────────────────────────────
    private var lrecParser:   LrecParser?               = null
    private var frames:       List<LrecParser.LrecFrame> = emptyList()
    private var chatEntries:  List<LrecParser.ChatEntry> = emptyList()
    private var audioBlocks:  List<LrecParser.AudioBlock> = emptyList()

    private var workBitmap:    Bitmap? = null
    private var displayBitmap: Bitmap? = null

    private val frameIndex = AtomicInteger(0)
    private val isPlaying  = AtomicBoolean(false)
    private val isParsed   = AtomicBoolean(false)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var playThread: Thread? = null

    // ── مشغّل الصوت ───────────────────────────────────────────────────
    private val audioPlayer = LrecAudioPlayer()
    private var audioEnabled = true

    private var controlsVisible  = true
    private val HIDE_DELAY_MS    = 3_500L
    private val hideRunnable     = Runnable { hideControls() }

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
        audioPlayer.release()
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

        setControlsEnabled(false)
        btnChat.isEnabled = false

        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
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
                if (fromUser && frames.isNotEmpty()) {
                    val target = (progress.toLong() * frames.size / 1000L)
                        .toInt().coerceIn(0, frames.size - 1)
                    frameIndex.set(target)
                    updateTimeTextUI()
                    showControls()
                    syncChatToFrame(target)
                    if (!isPlaying.get()) renderCurrentFrameAsync()
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
                    if (controlsVisible) hideControls() else showControls()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    togglePlayPause(); return true
                }
            })

        imageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event); true
        }
    }

    // ── تحميل وتحليل الملف ────────────────────────────────────────────
    private fun loadFile() {
        val uri = intent.data ?: run { finish(); return }
        tvTitle.text = uri.lastPathSegment ?: "ملف .lrec"
        showLoading(true, "جاري تحليل الملف…")

        Thread {
            val filePath = copyToCache(uri)
            val file     = File(filePath)
            val parser   = LrecParser(file)
            val ok       = parser.parse()

            runOnUiThread {
                showLoading(false)
                if (ok) {
                    lrecParser   = parser
                    frames       = parser.getAllFrames()
                    chatEntries  = parser.getChatEntries()
                    audioBlocks  = parser.getAudioBlocks()
                    isParsed.set(true)

                    val w = parser.metadata.screenWidth
                    val h = parser.metadata.screenHeight
                    workBitmap    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    displayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

                    // تهيئة مشغّل الصوت
                    if (audioBlocks.isNotEmpty()) {
                        val firstBlock = audioBlocks[0]
                        audioPlayer.initialize(
                            sampleRate = firstBlock.sampleRate,
                            stereo     = firstBlock.channels == 2
                        )
                        audioEnabled = true
                    }

                    setupChatPanel()
                    tvDuration.text    = formatTime(parser.getDurationMs())
                    tvCurrentTime.text = "00:00"
                    tvStatus.text      = buildStatusText(parser)
                    setControlsEnabled(true)
                    btnChat.isEnabled = true
                    startPlayback()
                    showControls()
                } else {
                    tvStatus.text       = "❌  تعذّر فتح الملف"
                    tvStatus.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun buildStatusText(parser: LrecParser): String = buildString {
        append("${frames.size} إطار")
        when {
            audioBlocks.isNotEmpty() ->
                append("  |  🔊 ${audioBlocks.size} كتلة صوت")
            parser.hasAudioBlocks ->
                append("  |  🔇 صوت (${parser.audioBlockCount} كتلة)")
        }
        when {
            chatEntries.isNotEmpty() -> append("  |  💬 ${chatEntries.size} رسالة")
            parser.hasChatBlocks     -> append("  |  💬 شات (${parser.chatBlockCount} كتلة)")
        }
    }

    // ── لوحة الدردشة ─────────────────────────────────────────────────
    private fun setupChatPanel() {
        if (chatEntries.isNotEmpty()) {
            chatAdapter = ChatAdapter(chatEntries)
            chatRecycler.adapter    = chatAdapter
            tvChatStatus.visibility = View.GONE
            chatRecycler.visibility = View.VISIBLE
            btnChat.setColorFilter(Color.parseColor("#4FC3F7"))
        } else {
            val parser = lrecParser
            tvChatStatus.text = if (parser != null && parser.hasChatBlocks)
                "⚠️ تم اكتشاف ${parser.chatBlockCount} رسالة\nلكن لم يتم فكّ ترميزها"
            else
                "لا توجد رسائل دردشة في هذا الملف"
            tvChatStatus.visibility = View.VISIBLE
            chatRecycler.visibility = View.GONE
            btnChat.alpha = 0.5f
        }
    }

    private fun toggleChatPanel() {
        if (chatPanelVisible) hideChatPanel() else showChatPanel()
    }

    private fun showChatPanel() {
        if (chatPanelVisible) return
        chatPanelVisible     = true
        chatPanel.visibility = View.VISIBLE
        chatPanel.alpha      = 0f
        chatPanel.translationX = chatPanel.width.toFloat()
        chatPanel.animate().alpha(1f).translationX(0f).setDuration(250).start()
        btnChat.setColorFilter(Color.parseColor("#4FC3F7"))
        syncChatToFrame(frameIndex.get())
    }

    private fun hideChatPanel() {
        if (!chatPanelVisible) return
        chatPanelVisible = false
        chatPanel.animate().alpha(0f)
            .translationX(chatPanel.width.toFloat())
            .setDuration(200)
            .withEndAction { chatPanel.visibility = View.GONE }
            .start()
        if (chatEntries.isEmpty()) btnChat.clearColorFilter()
    }

    private fun syncChatToFrame(frameIdx: Int) {
        if (!chatPanelVisible || chatEntries.isEmpty()) return
        val parser = lrecParser ?: return
        val fps    = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
        val timeMs = frameIdx.toLong() * 1000L / fps
        val adapter   = chatAdapter ?: return
        val activeIdx = chatEntries.indexOfLast { it.timestampMs <= timeMs }
        adapter.setActiveIndex(activeIdx)
        if (activeIdx >= 0) chatRecycler.scrollToPosition(activeIdx)
    }

    // ══════════════════════════════════════════════════════════════════
    //  التشغيل والإيقاف (مع مزامنة الصوت)
    // ══════════════════════════════════════════════════════════════════

    private fun togglePlayPause() {
        if (isPlaying.get()) pausePlayback() else startPlayback()
        showControls()
    }

    private fun startPlayback() {
        if (!isParsed.get() || frames.isEmpty()) return
        if (isPlaying.get()) return
        if (frameIndex.get() >= frames.size) frameIndex.set(0)

        isPlaying.set(true)
        btnPlayPause.setImageResource(R.drawable.ic_pause)

        // ── بدء تشغيل الصوت مع الفيديو ───────────────────────────────
        if (audioEnabled && audioBlocks.isNotEmpty()) {
            val parser = lrecParser
            val fps    = parser?.metadata?.fps?.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
                         ?: LrecParser.DEFAULT_FPS
            val currentTimeMs = frameIndex.get().toLong() * 1000L / fps

            // ابدأ من الكتلة الصوتية المقابلة للموقع الحالي
            val startAudioIdx = audioBlocks.indexOfFirst {
                it.timestampMs >= currentTimeMs
            }.coerceAtLeast(0)

            audioPlayer.startPlayback(
                audioBlocks = audioBlocks.map { it.rawData },
                startIndex  = startAudioIdx
            )
        }

        val parser = lrecParser ?: return

        playThread = Thread {
            val fps          = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
            val frameDelayMs = (1000L / fps).coerceAtLeast(33L)

            while (isPlaying.get()) {
                val idx = frameIndex.get()
                if (idx >= frames.size) {
                    uiHandler.post {
                        isPlaying.set(false)
                        audioPlayer.stop()
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                        showControls()
                    }
                    break
                }

                val frame     = frames[idx]
                val frameData = try { parser.decodeScreenFrame(frame) } catch (_: Exception) { null }
                val wBmp      = workBitmap
                val dBmp      = displayBitmap

                if (frameData != null && wBmp != null && dBmp != null) {
                    parser.applyFrameToBitmap(wBmp, frameData)
                    val canvas = android.graphics.Canvas(dBmp)
                    canvas.drawBitmap(wBmp, 0f, 0f, null)
                }

                val snap       = dBmp
                val currentIdx = frameIndex.incrementAndGet()

                uiHandler.post {
                    if (snap != null) imageView.setImageBitmap(snap)
                    updateProgressUI(currentIdx)
                    if (chatPanelVisible) syncChatToFrame(currentIdx)
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
        audioPlayer.pause()
        btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    // ── التقديم والترجيع ──────────────────────────────────────────────
    private fun seekRelativeSeconds(seconds: Int) {
        if (!isParsed.get() || frames.isEmpty()) return
        val parser = lrecParser ?: return
        val fps    = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
        val newIdx = (frameIndex.get() + seconds * fps).coerceIn(0, frames.size - 1)
        frameIndex.set(newIdx)
        updateProgressUI(newIdx)
        updateTimeTextUI()
        showControls()
        syncChatToFrame(newIdx)

        // مزامنة الصوت عند الانتقال
        if (audioEnabled && audioBlocks.isNotEmpty()) {
            val timeMs = newIdx.toLong() * 1000L / fps
            audioPlayer.flushQueue()
            if (isPlaying.get()) {
                val startIdx = audioBlocks.indexOfFirst { it.timestampMs >= timeMs }
                    .coerceAtLeast(0)
                audioBlocks.drop(startIdx).forEach { block ->
                    audioPlayer.enqueueBlock(block.rawData)
                }
            }
        }

        if (!isPlaying.get()) renderCurrentFrameAsync()
    }

    private fun renderCurrentFrameAsync() {
        val parser = lrecParser ?: return
        val idx    = frameIndex.get().coerceIn(0, frames.size - 1)
        val frame  = frames.getOrNull(idx) ?: return
        Thread {
            val frameData = try { parser.decodeScreenFrame(frame) } catch (_: Exception) { null }
            val wBmp = workBitmap; val dBmp = displayBitmap
            if (frameData != null && wBmp != null && dBmp != null) {
                parser.applyFrameToBitmap(wBmp, frameData)
                val canvas = android.graphics.Canvas(dBmp)
                canvas.drawBitmap(wBmp, 0f, 0f, null)
                uiHandler.post { imageView.setImageBitmap(dBmp) }
            }
        }.start()
    }

    // ── حوار المعلومات ────────────────────────────────────────────────
    private fun showInfoDialog() {
        pausePlayback()
        val parser = lrecParser
        val m      = parser?.metadata
        val msg = buildString {
            appendLine("📹  معلومات الملف")
            appendLine()
            appendLine("• دقة الشاشة:     ${m?.screenWidth ?: "?"} × ${m?.screenHeight ?: "?"}")
            appendLine("• إجمالي الإطارات: ${frames.size}")
            appendLine("• مدة التسجيل:     ${formatTime(parser?.getDurationMs() ?: 0)}")
            appendLine("• الإصدار:          ${m?.version ?: "?"}")
            if (m?.serverAddr?.isNotEmpty() == true) appendLine("• الخادم: ${m.serverAddr}")
            appendLine()
            appendLine("─────────────────────────")
            appendLine("🔊  الصوت")
            if (audioBlocks.isNotEmpty()) {
                appendLine("• الكتل المُستخرجة: ${audioBlocks.size}")
                appendLine("• معدل التردد: ${audioBlocks[0].sampleRate} Hz")
                appendLine("• القنوات: ${if (audioBlocks[0].channels == 2) "ستيريو" else "مونو"}")
                appendLine("✅ الصوت يعمل")
            } else if (parser?.hasAudioBlocks == true) {
                appendLine("• الكتل المكتشفة: ${parser.audioBlockCount}")
                appendLine("⚠️ لم يتم فكّ ترميز الصوت بعد")
            } else {
                appendLine("لا توجد بيانات صوتية")
            }
            appendLine()
            appendLine("─────────────────────────")
            appendLine("💬  المحادثة")
            when {
                chatEntries.isNotEmpty() -> {
                    appendLine("• الرسائل المُستخرجة: ${chatEntries.size}")
                    appendLine("✅ تم فك الترميز")
                }
                parser?.hasChatBlocks == true -> {
                    appendLine("• الكتل: ${parser.chatBlockCount}")
                    appendLine("⚠️ لم يتم فكّ الترميز")
                }
                else -> appendLine("لا توجد رسائل")
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle("ℹ️  معلومات الملف")
            .setMessage(msg)
            .setPositiveButton("إغلاق", null)
            .show()
        showControls()
    }

    // ── تحديث الواجهة ─────────────────────────────────────────────────
    private fun updateProgressUI(currentFrameIdx: Int) {
        if (frames.isEmpty()) return
        val progress = (currentFrameIdx.toLong() * 1000L / frames.size)
            .toInt().coerceIn(0, 1000)
        seekBar.progress = progress
        updateTimeTextUI()
    }

    private fun updateTimeTextUI() {
        val parser = lrecParser ?: return
        val fps    = parser.metadata.fps.let { if (it <= 0) LrecParser.DEFAULT_FPS else it }
        val ms     = frameIndex.get().toLong() * 1000L / fps
        tvCurrentTime.text = formatTime(ms)
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
            if (message.isNotEmpty()) {
                tvStatus.text       = message
                tvStatus.visibility = View.VISIBLE
            }
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
        val file = File(cacheDir, "lrec_temp.lrec")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { out ->
                val buf = ByteArray(65_536)
                while (true) { val n = input.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
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
            if (idx >= 0)  notifyItemChanged(idx)
        }

        inner class ChatVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTime:    TextView = itemView.findViewById(R.id.chatTvTime)
            val tvSender:  TextView = itemView.findViewById(R.id.chatTvSender)
            val tvMessage: TextView = itemView.findViewById(R.id.chatTvMessage)
            val container: View     = itemView.findViewById(R.id.chatContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChatVH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false))

        override fun onBindViewHolder(holder: ChatVH, position: Int) {
            val entry = entries[position]
            holder.tvTime.text    = entry.formattedTime
            holder.tvSender.text  = entry.sender
            holder.tvMessage.text = entry.message
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

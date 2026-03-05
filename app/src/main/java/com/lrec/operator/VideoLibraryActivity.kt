package com.lrec.operator

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.provider.MediaStore
import com.google.android.material.navigation.NavigationView
import java.util.Locale

// ─── نماذج البيانات ────────────────────────────────────────────────
data class VideoItem(
    val id: Long,
    val title: String,
    val duration: Long,
    val size: Long,
    val uri: Uri,
    val folderName: String,
    val folderPath: String
)

data class FolderItem(
    val name: String,
    val path: String,
    val videoCount: Int,
    val videos: List<VideoItem>
)

class VideoLibraryActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvVideoCount: TextView
    private lateinit var tvScreenTitle: TextView
    private lateinit var btnRefreshTop: ImageButton
    private lateinit var btnMenuTop: ImageButton
    private lateinit var btnBack: ImageButton

    private lateinit var prefs: SharedPreferences

    private val allVideos   = mutableListOf<VideoItem>()
    private val folderList  = mutableListOf<FolderItem>()
    private var showingFolders = true
    private var currentFolder: FolderItem? = null
    private var showHidden = false
    private var currentLang = "ar"

    companion object {
        const val REQUEST_PERMISSION = 2001
    }

    // ─── تطبيق اللغة قبل إنشاء الواجهة ──────────────────────────
    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("lrec_prefs", MODE_PRIVATE)
        val lang  = prefs.getString("language", "ar") ?: "ar"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs       = getSharedPreferences("lrec_prefs", MODE_PRIVATE)
        showHidden  = prefs.getBoolean("show_hidden", false)
        currentLang = prefs.getString("language", "ar") ?: "ar"

        applyTheme()

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        setContentView(R.layout.activity_library)
        initViews()
        setupDrawer()
        checkPermissionAndLoad()
    }

    private fun applyTheme() {
        val isDark = prefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun initViews() {
        drawerLayout   = findViewById(R.id.libDrawerLayout)
        navigationView = findViewById(R.id.libNavigationView)
        recyclerView   = findViewById(R.id.recyclerView)
        tvEmpty        = findViewById(R.id.tvEmpty)
        progressBar    = findViewById(R.id.progressBar)
        tvVideoCount   = findViewById(R.id.tvVideoCount)
        tvScreenTitle  = findViewById(R.id.tvScreenTitle)
        btnRefreshTop  = findViewById(R.id.btnRefresh)
        btnMenuTop     = findViewById(R.id.btnMenuTop)
        btnBack        = findViewById(R.id.btnBackLib)

        recyclerView.layoutManager = LinearLayoutManager(this)

        btnRefreshTop.setOnClickListener {
            it.animate().rotationBy(360f).setDuration(500).start()
            checkPermissionAndLoad()
        }
        btnMenuTop.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        btnBack.setOnClickListener    { handleBackNavigation() }
    }

    // ─── القائمة الجانبية الوظيفية ────────────────────────────────
    private fun setupDrawer() {
        navigationView.menu.findItem(R.id.lib_nav_hidden)?.isChecked = showHidden

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {

                R.id.lib_nav_theme -> {
                    val isDark = prefs.getBoolean("dark_mode", true)
                    prefs.edit().putBoolean("dark_mode", !isDark).apply()
                    AppCompatDelegate.setDefaultNightMode(
                        if (!isDark) AppCompatDelegate.MODE_NIGHT_YES
                        else         AppCompatDelegate.MODE_NIGHT_NO
                    )
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                R.id.lib_nav_language -> {
                    val newLang = if (currentLang == "ar") "en" else "ar"
                    prefs.edit().putString("language", newLang).apply()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, VideoLibraryActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                    true
                }

                R.id.lib_nav_hidden -> {
                    showHidden = !showHidden
                    prefs.edit().putBoolean("show_hidden", showHidden).apply()
                    item.isChecked = showHidden
                    Toast.makeText(this,
                        if (showHidden) "سيتم عرض الملفات المخفية" else "لن تُعرض الملفات المخفية",
                        Toast.LENGTH_SHORT).show()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    checkPermissionAndLoad()
                    true
                }

                R.id.lib_nav_refresh -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    checkPermissionAndLoad()
                    true
                }

                R.id.lib_nav_settings -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
            loadVideos()
        else
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            loadVideos()
        else
            showEmpty(getString(R.string.permission_required))
    }

    private fun loadVideos() {
        progressBar.visibility  = View.VISIBLE
        tvEmpty.visibility      = View.GONE
        recyclerView.visibility = View.GONE

        Thread {
            val videos  = queryVideos()
            val folders = groupByFolder(videos)
            runOnUiThread {
                progressBar.visibility = View.GONE
                allVideos.clear();  allVideos.addAll(videos)
                folderList.clear(); folderList.addAll(folders)
                if (folderList.isEmpty()) showEmpty(getString(R.string.no_videos))
                else showFolders()
            }
        }.start()
    }

    private fun queryVideos(): List<VideoItem> {
        val list = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        val cursor: Cursor? = contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idCol     = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol  = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durCol    = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol   = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dataCol   = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val bucketCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (it.moveToNext()) {
                val id       = it.getLong(idCol)
                val title    = it.getString(titleCol) ?: "فيديو"
                val duration = it.getLong(durCol)
                val size     = it.getLong(sizeCol)
                val data     = it.getString(dataCol) ?: ""
                val bucket   = it.getString(bucketCol) ?: "أخرى"
                val uri      = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()
                )

                // فلترة الملفات المخفية (اسم يبدأ أو ينتهي بنقطة)
                if (isHiddenPath(data) && !showHidden) continue

                list.add(VideoItem(id, title, duration, size, uri, bucket, data))
            }
        }
        return list
    }

    // يكشف الملفات المخفية بالتحقق من كل جزء في المسار
    private fun isHiddenPath(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        return filePath.split("/").any { segment ->
            segment.isNotBlank() && (segment.startsWith(".") || segment.endsWith("."))
        }
    }

    private fun groupByFolder(videos: List<VideoItem>): List<FolderItem> {
        val map = linkedMapOf<String, MutableList<VideoItem>>()
        videos.forEach { v -> map.getOrPut(v.folderName) { mutableListOf() }.add(v) }
        return map.map { (name, vids) ->
            FolderItem(name, vids.first().folderPath, vids.size, vids)
        }.sortedByDescending { it.videoCount }
    }

    private fun showFolders() {
        showingFolders     = true
        currentFolder      = null
        tvScreenTitle.text = getString(R.string.app_name)
        btnBack.visibility = View.GONE
        tvVideoCount.text  = "${folderList.size} ${getString(R.string.folders)}"
        recyclerView.visibility = View.VISIBLE
        recyclerView.adapter    = FolderAdapter(folderList) { showFolderContents(it) }
    }

    private fun showFolderContents(folder: FolderItem) {
        showingFolders     = false
        currentFolder      = folder
        tvScreenTitle.text = folder.name
        btnBack.visibility = View.VISIBLE
        tvVideoCount.text  = "${folder.videoCount} ${getString(R.string.videos)}"
        recyclerView.adapter = VideoListAdapter(folder.videos) { openVideo(it) }
    }

    private fun showEmpty(msg: String) {
        recyclerView.visibility = View.GONE
        tvEmpty.text            = msg
        tvEmpty.visibility      = View.VISIBLE
    }

    private fun openVideo(video: VideoItem) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data   = video.uri
            putExtra("VIDEO_TITLE", video.title)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun handleBackNavigation() {
        if (!showingFolders) showFolders() else finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) ->
                drawerLayout.closeDrawer(GravityCompat.START)
            !showingFolders -> showFolders()
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val prevHidden = showHidden
        showHidden  = prefs.getBoolean("show_hidden", false)
        currentLang = prefs.getString("language", "ar") ?: "ar"
        if (prevHidden != showHidden) checkPermissionAndLoad()
        navigationView.menu.findItem(R.id.lib_nav_hidden)?.isChecked = showHidden
    }
}

// ─── Adapter المجلدات ────────────────────────────────────────────────
class FolderAdapter(
    private val items: List<FolderItem>,
    private val onClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:  TextView  = v.findViewById(R.id.tvFolderName)
        val tvCount: TextView  = v.findViewById(R.id.tvFolderCount)
        val ivIcon:  ImageView = v.findViewById(R.id.ivFolderIcon)
        val root:    View      = v.findViewById(R.id.folderRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.tvName.text  = f.name
        holder.tvCount.text = "${f.videoCount} فيديو"
        holder.root.setOnClickListener {
            it.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                onClick(f)
            }.start()
        }
    }

    override fun getItemCount() = items.size
}

// ─── Adapter قائمة الفيديو ────────────────────────────────────────────
class VideoListAdapter(
    private val items: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle:    TextView = v.findViewById(R.id.tvVideoTitle)
        val tvDuration: TextView = v.findViewById(R.id.tvVideoDuration)
        val tvSize:     TextView = v.findViewById(R.id.tvVideoSize)
        val root:       View     = v.findViewById(R.id.videoItemRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items[position]
        holder.tvTitle.text    = v.title
        holder.tvDuration.text = formatDuration(v.duration)
        holder.tvSize.text     = formatSize(v.size)
        holder.root.setOnClickListener {
            it.animate().alpha(0.7f).setDuration(80).withEndAction {
                it.animate().alpha(1f).setDuration(80).start()
                onClick(v)
            }.start()
        }
    }

    override fun getItemCount() = items.size

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> String.format("%.1f MB", bytes / 1_048_576.0)
        else                   -> String.format("%.0f KB", bytes / 1024.0)
    }
}

package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.media.tv.TvContract
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 22)
    private val accent = Color.rgb(126, 190, 255)
    private lateinit var root: LinearLayout
    private lateinit var wallpaperTarget: FrameLayout
    private val actions = mutableListOf<View>()
    private var continueWatchingCard: View? = null
    private var continueBackdropArtwork: File? = null
    private var lastFocusedTitle: String = "Live TV"
    private var refreshOnResume = false
    private val homePreviewPlayers = mutableListOf<ExoPlayer>()
    private val dashboardHandler = Handler(Looper.getMainLooper())
    private val wallpaperHandler = Handler(Looper.getMainLooper())
    private val wallpaperUrls = listOf(
        "https://images.unsplash.com/photo-1470770841072-f978cf4d019e?auto=format&fit=crop&w=1920&q=82",
        "https://images.unsplash.com/photo-1518837695005-2083093ee35b?auto=format&fit=crop&w=1920&q=82",
        "https://images.unsplash.com/photo-1519608487953-e999c86e7455?auto=format&fit=crop&w=1920&q=82",
        "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=1920&q=82"
    )
    private val wallpaperCycle = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
            if (!prefs.getBoolean("wallpaper_auto", true)) return
            val next = (prefs.getInt("wallpaper_index", 0) + 1) % wallpaperUrls.size
            prefs.edit().putInt("wallpaper_index", next).apply()
            applyWallpaper(wallpaperTarget)
            wallpaperHandler.postDelayed(this, 8 * 60 * 1000L)
        }
    }
    private val prayerTimeLabels = mutableListOf<TextView>()
    private var headerTimeLabel: TextView? = null
    private var dashboardGreetingLabel: TextView? = null
    private var dashboardClockLabel: TextView? = null
    private var dashboardDateLabel: TextView? = null
    private var dashboardLocationLabel: TextView? = null
    private var dashboardWeatherLabel: TextView? = null
    private var dashboardNextPrayerLabel: TextView? = null
    private var dashboardAzanButton: TextView? = null
    private var dashboardSnapshot: DashboardServices.Snapshot? = null
    private var toneGenerator: ToneGenerator? = null
    private var azanPlayer: MediaPlayer? = null
    private var lastAzanKey: String = ""
    private var dashboardLoopStarted = false
    private var lastDashboardFetchAttempt = 0L
    private val azanAudioUrl = "https://raw.githubusercontent.com/toniasep/cleartv-Islamic/main/themes/islamik/azan.mp3"
    private val dashboardTick = object : Runnable {
        override fun run() {
            updateDashboardClock()
            checkForAzan()
            val snapshot = dashboardSnapshot
            if (snapshot == null || System.currentTimeMillis() - snapshot.fetchedAt > 15 * 60_000L) fetchDashboardData()
            dashboardHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showHome()
    }

    override fun onPause() {
        refreshOnResume = true
        // Keep the lightweight clock/prayer watcher alive while another TV app is in front.
        // CCTV preview players are still released to save memory.
        releaseHomePreviewPlayers()
        wallpaperHandler.removeCallbacks(wallpaperCycle)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (refreshOnResume) {
            refreshOnResume = false
            showHome()
        } else if (!dashboardLoopStarted) {
            startDashboardUpdates()
        }
        scheduleWallpaperRotation()
    }

    private fun showHome() {
        releaseHomePreviewPlayers()
        actions.clear()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(10), dp(32), dp(10))
            setBackgroundColor(Color.TRANSPARENT)
            isFocusableInTouchMode = true
            clipChildren = false
            clipToPadding = false
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    showGlassSidebar(); true
                } else false
            }
        }

        wallpaperTarget = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 15, 24), Color.rgb(5, 8, 14))
            )
            clipChildren = false
            clipToPadding = false
        }
        val homeScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        homeScroll.addView(root, FrameLayout.LayoutParams(-1, -2))
        wallpaperTarget.addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
        applyWallpaper(wallpaperTarget)
        scheduleWallpaperRotation()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(label("GLASS", 25, Color.WHITE, true))
        brand.addView(label("TV", 25, accent, true))
        brand.addView(label("  /  HOME ENTERTAINMENT", 10, Color.rgb(128, 148, 174), true))
        header.addView(brand)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val time = SimpleDateFormat("EEE, dd MMM  •  HH:mm", Locale.getDefault()).format(Date())
        headerTimeLabel = label(time, 15, Color.rgb(185, 199, 219), false)
        header.addView(headerTimeLabel)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(40)))

        addDashboardPanel()

        addFavoriteDock(root)
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        root.addView(mediaRow, LinearLayout.LayoutParams(-1, dp(158)))
        addContinueWatching(mediaRow)
        addHomeCctvPreview(mediaRow)
        addBackgroundAudioRow(root)
        addPreviewChannelRows(root)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        section.addView(label("JELAJAHI TV", 11, Color.rgb(168, 188, 214), true))
        section.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        section.addView(label("Gunakan tombol arah dan OK", 10, Color.rgb(112, 131, 155), false))
        root.addView(section, LinearLayout.LayoutParams(-1, dp(21)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(5))
            clipChildren = false
            clipToPadding = false
        }
        addAction(row, "Live TV", "Siaran antena") { openLiveTv() }
        addAction(row, "Entertainment", "Pilihan aplikasi") { openApps() }
        addAction(row, "All Apps", "Semua aplikasi TV") { openAllApps() }
        addAction(row, "Kamera CCTV", "2 kamera • RTSP") { showCameras() }
        addAction(row, "Pengaturan", "Preferensi GlassTV") { showSettings() }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(76)))
        setContentView(wallpaperTarget)
        root.post {
            if (lastFocusedTitle == "LANJUTKAN MENONTON") {
                continueWatchingCard?.requestFocus()
            } else {
                val target = actions.firstOrNull {
                    it.contentDescription?.toString()?.startsWith(lastFocusedTitle) == true
                } ?: actions.firstOrNull()
                target?.requestFocus()
            }
        }
        dashboardSnapshot = DashboardServices.cached(this)
        renderDashboardSnapshot(dashboardSnapshot, null)
        startDashboardUpdates()
    }

    private fun addDashboardPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(9), dp(16), dp(9))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(190, 24, 34, 51),
                    Color.argb(178, 14, 20, 31),
                    Color.argb(165, 10, 15, 24)
                )
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.argb(132, 153, 194, 255))
            }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        dashboardGreetingLabel = label("Selamat malam", 13, Color.WHITE, true)
        left.addView(dashboardGreetingLabel)
        dashboardDateLabel = label("--", 11, Color.rgb(174, 192, 216), false)
        left.addView(dashboardDateLabel)
        dashboardClockLabel = label("--:--", 26, Color.WHITE, true)
        left.addView(dashboardClockLabel)
        dashboardLocationLabel = label("📍 Jakarta Timur", 12, accent, true).apply {
            setPadding(0, dp(3), 0, 0)
            isFocusable = true
            isClickable = true
            contentDescription = "Lokasi cuaca dan waktu salat"
            setOnClickListener { showLocationPicker() }
            setOnFocusChangeListener { view, focused ->
                (view as TextView).setTextColor(if (focused) Color.WHITE else accent)
                if (focused) playNavigationSound()
            }
            setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    playSelectSound(); view.performClick(); true
                } else false
            }
        }
        left.addView(dashboardLocationLabel)
        dashboardWeatherLabel = label("Memuat cuaca BMKG…", 11, Color.rgb(232, 237, 246), false).apply {
            setPadding(0, dp(2), 0, 0)
            maxLines = 2
            minHeight = dp(28)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1.0f)
        }
        left.addView(dashboardWeatherLabel)
        panel.addView(left, LinearLayout.LayoutParams(dp(258), -1))

        val prayerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val prayerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(5))
        }
        dashboardNextPrayerLabel = label("SALAT BERIKUTNYA  •  Memuat jadwal…", 11, Color.rgb(224, 233, 247), true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(6), 0)
            setLetterSpacing(0.035f)
        }
        prayerHeader.addView(dashboardNextPrayerLabel, LinearLayout.LayoutParams(0, dp(34), 1f))
        dashboardAzanButton = TextView(this).apply {
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                if (focused) playNavigationSound()
                view.elevation = if (focused) dp(7).toFloat() else dp(1).toFloat()
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (focused) Color.rgb(67, 113, 157) else Color.argb(150, 24, 40, 59))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.argb(150, 126, 190, 255))
                }
            }
            setOnClickListener {
                val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
                val enabled = prefs.getBoolean("azan_enabled", true)
                val player = azanPlayer
                if (player != null) {
                    try { player.stop() } catch (_: Exception) { }
                    try { player.release() } catch (_: Exception) { }
                    azanPlayer = null
                    updateAzanQuickButton()
                    toast("Suara azan dihentikan")
                } else {
                    prefs.edit().putBoolean("azan_enabled", !enabled).apply()
                    if (enabled) toast("Azan otomatis dimatikan") else toast("Azan otomatis diaktifkan")
                    updateAzanQuickButton()
                }
                playSelectSound()
            }
            setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    view.performClick(); true
                } else false
            }
        }
        prayerHeader.addView(dashboardAzanButton, LinearLayout.LayoutParams(-2, dp(32)))
        prayerPanel.addView(prayerHeader, LinearLayout.LayoutParams(-1, dp(36)))
        updateAzanQuickButton()
        val prayerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prayerTimeLabels.clear()
        listOf("Subuh", "Syuruk", "Zuhur", "Asar", "Maghrib", "Isya").forEach { name ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(3), dp(2), dp(3), dp(2))
            }
            item.addView(label(name, 10, Color.rgb(165, 184, 208), false).apply { gravity = Gravity.CENTER; maxLines = 1 })
            val timeLabel = label("--:--", 13, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
            prayerTimeLabels.add(timeLabel)
            item.addView(timeLabel)
            prayerRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        prayerPanel.addView(prayerRow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(prayerPanel, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(panel, LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(7) })
    }

    private fun startDashboardUpdates() {
        if (dashboardLoopStarted) return
        dashboardLoopStarted = true
        updateDashboardClock()
        if (dashboardSnapshot == null || System.currentTimeMillis() - dashboardSnapshot!!.fetchedAt > 15 * 60_000L) fetchDashboardData()
        dashboardHandler.removeCallbacks(dashboardTick)
        dashboardHandler.postDelayed(dashboardTick, 30_000L)
    }

    private fun updateDashboardClock() {
        val now = Date()
        dashboardGreetingLabel?.text = currentGreeting()
        dashboardDateLabel?.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(now)
        dashboardClockLabel?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        headerTimeLabel?.text = SimpleDateFormat("EEE, dd MMM  •  HH:mm", Locale("id", "ID")).format(now)
    }

    private fun fetchDashboardData() {
        val now = System.currentTimeMillis()
        if (now - lastDashboardFetchAttempt < 5 * 60_000L) return
        lastDashboardFetchAttempt = now
        DashboardServices.fetch(this) { snapshot, error ->
            if (snapshot != null) {
                dashboardSnapshot = snapshot
                renderDashboardSnapshot(snapshot, error)
            } else {
                dashboardWeatherLabel?.text = "Cuaca BMKG belum tersedia\nPeriksa koneksi internet"
                dashboardNextPrayerLabel?.text = "Jadwal salat belum tersedia"
            }
        }
    }

    private fun renderDashboardSnapshot(snapshot: DashboardServices.Snapshot?, error: String?) {
        if (snapshot == null) return
        dashboardLocationLabel?.text = "📍 ${snapshot.city}"
        val shortCondition = snapshot.condition.take(24).trimEnd()
        dashboardWeatherLabel?.text = "${snapshot.temperature}  •  ${shortCondition}\nPrakiraan BMKG" + if (error != null) " · tersimpan" else ""
        snapshot.prayerTimes.forEachIndexed { index, time -> prayerTimeLabels.getOrNull(index)?.text = time }
        val next = DashboardServices.nextPrayer(snapshot)
        dashboardNextPrayerLabel?.text = "SALAT BERIKUTNYA  •  ${next.first}  ${next.second}"
    }

    private fun showLocationPicker() {
        val current = DashboardServices.selectedCity(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(label("Cari kota atau kabupaten di Indonesia", 12, Color.rgb(180, 198, 220), true))
        val query = EditText(this).apply {
            hint = "Contoh: Bandung, Surabaya, Yogyakarta"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(125, 145, 171))
            textSize = 16f
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(25, 35, 50))
                setStroke(dp(1), Color.argb(150, 126, 190, 255))
            }
        }
        panel.addView(query, LinearLayout.LayoutParams(-1, dp(48)))
        val search = TextView(this).apply {
            text = "CARI LOKASI"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(45, 77, 112))
                setStroke(dp(1), Color.argb(180, 126, 190, 255))
            }
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                if (focused) playNavigationSound()
                view.alpha = 1f
                view.scaleX = if (focused) 1.015f else 1f
                view.scaleY = if (focused) 1.015f else 1f
                view.elevation = if (focused) dp(8).toFloat() else 0f
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (focused) Color.rgb(74, 126, 180) else Color.rgb(35, 57, 83))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.argb(180, 126, 190, 255))
                }
            }
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        val resultList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val resultScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(resultList, FrameLayout.LayoutParams(-1, -2))
        }
        panel.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(270)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Lokasi cuaca & waktu salat")
            .setView(panel)
            .setNegativeButton("Tutup", null)
            .create()
        fun choose(city: DashboardServices.City) {
            DashboardServices.selectCity(this, city)
            dashboardSnapshot = null
            lastDashboardFetchAttempt = 0L
            dialog.dismiss()
            showHome()
            toast("Lokasi diubah ke ${city.name}")
        }
        fun render(items: List<DashboardServices.City>, message: String? = null) {
            resultList.removeAllViews()
            if (message != null) resultList.addView(label(message, 12, Color.rgb(225, 205, 165), false))
            items.forEach { city ->
                val item = TextView(this).apply {
                    text = (if (city.name == current.name) "✓  " else "") + city.name
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setColor(Color.argb(120, 30, 43, 62))
                        setStroke(dp(1), Color.argb(65, 153, 194, 255))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.alpha = 1f
                        view.scaleX = if (focused) 1.015f else 1f
                        view.scaleY = if (focused) 1.015f else 1f
                        view.elevation = if (focused) dp(6).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(9).toFloat()
                            setColor(if (focused) Color.rgb(58, 91, 130) else Color.argb(120, 30, 43, 62))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.argb(65, 153, 194, 255))
                        }
                    }
                    setOnClickListener { playSelectSound(); choose(city) }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            view.performClick(); true
                        } else false
                    }
                }
                resultList.addView(item, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(5) })
            }
        }
        search.setOnClickListener {
            val term = query.text?.toString()?.trim().orEmpty()
            if (term.length < 2) render(emptyList(), "Ketik minimal 2 karakter untuk mencari.")
            else {
                render(emptyList(), "Mencari lokasi…")
                DashboardServices.searchCities(this@MainActivity, term) { found, error ->
                    if (!dialog.isShowing) return@searchCities
                    if (found.isEmpty()) render(emptyList(), error?.let { "Pencarian gagal: $it" } ?: "Lokasi tidak ditemukan. Coba nama kota lain.")
                    else render(found, "Pilih lokasi untuk memperbarui cuaca dan waktu salat.")
                }
            }
        }
        search.setOnKeyListener { view, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                view.performClick(); true
            } else false
        }
        render(DashboardServices.cities)
        dialog.show()
        dialog.window?.setLayout(
            minOf(dp(600), (resources.displayMetrics.widthPixels * 0.88f).toInt()),
            -2
        )
    }

    private fun checkForAzan() {
        if (!getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("azan_enabled", true)) return
        val snapshot = dashboardSnapshot ?: return
        val index = DashboardServices.duePrayer(snapshot)
        if (index < 0) return
        val now = Calendar.getInstance()
        val key = String.format(Locale.ROOT, "%04d-%03d-%04d-%d",
            now.get(Calendar.YEAR), now.get(Calendar.DAY_OF_YEAR),
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), index)
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (key == lastAzanKey || key == prefs.getString("last_azan_key", "")) return
        lastAzanKey = key
        prefs.edit().putString("last_azan_key", key).apply()
        playAzan()
    }

    private fun updateAzanQuickButton() {
        val button = dashboardAzanButton ?: return
        val enabled = getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("azan_enabled", true)
        button.text = when {
            azanPlayer != null -> "HENTIKAN AZAN"
            enabled -> "AZAN AKTIF"
            else -> "AZAN MATI"
        }
        button.contentDescription = when {
            azanPlayer != null -> "Hentikan suara azan yang sedang diputar"
            enabled -> "Matikan azan otomatis"
            else -> "Aktifkan azan otomatis"
        }
    }

    private fun playAzan() {
        try {
            azanPlayer?.release()
            azanPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(azanAudioUrl)
                setOnPreparedListener { it.start(); runOnUiThread { updateAzanQuickButton() } }
                setOnCompletionListener {
                    try { it.release() } catch (_: Exception) { }
                    if (azanPlayer === it) azanPlayer = null
                    runOnUiThread { updateAzanQuickButton() }
                }
                setOnErrorListener { player, _, _ ->
                    try { player.release() } catch (_: Exception) { }
                    if (azanPlayer === player) azanPlayer = null
                    runOnUiThread { updateAzanQuickButton() }
                    toast("Suara azan tidak dapat diputar. Periksa koneksi internet TV.")
                    true
                }
                prepareAsync()
            }
            updateAzanQuickButton()
            toast("Waktu salat tiba")
        } catch (_: Exception) { toast("Tidak dapat memulai suara azan.") }
    }

    private fun playNavigationSound() {
        if (!getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("ui_sound_enabled", true)) return
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 18)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 28)
        } catch (_: Exception) { }
    }

    private fun playSelectSound() {
        if (!getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("ui_sound_enabled", true)) return
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 18)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 55)
        } catch (_: Exception) { }
    }

    /** Two live RTSP previews are shown directly on the home screen. */
    private fun addHomeCctvPreview(mediaRow: LinearLayout) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), dp(4))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("CCTV LANGSUNG", 12, Color.rgb(160, 178, 201), true))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val manage = label("ATUR KAMERA  ›", 11, accent, true).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { showCameras() }
        }
        manage.setOnFocusChangeListener { _, focused -> if (focused) playNavigationSound() }
        manage.setOnKeyListener { view, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                playSelectSound(); view.performClick(); true
            } else false
        }
        header.addView(manage)
        section.addView(header, LinearLayout.LayoutParams(-1, dp(20)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (index in 1..2) {
            val name = prefs.getString("camera_name_$index", "Kamera $index")
                ?.takeIf { it.isNotBlank() } ?: "Kamera $index"
            val url = prefs.getString("camera_$index", null)?.trim().orEmpty()
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(142, 15, 22, 34))
                    setStroke(dp(1), Color.argb(105, 126, 190, 255))
                }
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnFocusChangeListener { view, focused ->
                    if (focused) playNavigationSound()
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (focused) Color.argb(205, 32, 48, 70) else Color.argb(142, 15, 22, 34))
                        setStroke(if (focused) dp(2) else dp(1),
                            if (focused) accent else Color.argb(105, 126, 190, 255))
                    }
                }
                setOnKeyListener { view, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                         keyCode == KeyEvent.KEYCODE_ENTER ||
                         keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        playSelectSound()
                        view.performClick()
                        true
                    } else false
                }
                setOnClickListener { showCameras() }
            }
            panel.addView(label(name, 11, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val playerView = PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.rgb(5, 8, 13))
                setBackgroundColor(Color.rgb(5, 8, 13))
            }
            panel.addView(playerView, LinearLayout.LayoutParams(-1, dp(56)))
            if (url.isBlank()) {
                playerView.visibility = View.GONE
                panel.addView(label("Belum diatur · buka Atur Kamera", 10, Color.rgb(220, 205, 154), false)
                    .apply { setPadding(0, dp(4), 0, dp(4)) })
            } else if (url.startsWith("rtsp://", true) || url.startsWith("rtsps://", true)) {
                try {
                    val player = ExoPlayer.Builder(this).build()
                    homePreviewPlayers.add(player)
                    playerView.player = player
                    player.volume = 0f
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            // Keep the tile available; detailed diagnostics remain in the CCTV page.
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.playWhenReady = true
                } catch (_: Exception) {
                    playerView.visibility = View.GONE
                    panel.addView(label("Pratinjau tidak tersedia", 10, Color.rgb(220, 160, 150), false))
                }
            } else {
                playerView.visibility = View.GONE
                panel.addView(label("URL RTSP tidak valid", 10, Color.rgb(220, 160, 150), false))
            }
            val lp = LinearLayout.LayoutParams(0, dp(98), 1f)
            lp.setMargins(0, 0, if (index == 1) dp(8) else 0, 0)
            row.addView(panel, lp)
        }
        section.addView(row, LinearLayout.LayoutParams(-1, dp(98)))
        mediaRow.addView(section, LinearLayout.LayoutParams(0, -1, 1.05f))
    }

    private fun releaseHomePreviewPlayers() {
        homePreviewPlayers.forEach { player ->
            try { player.release() } catch (_: Exception) { }
        }
        homePreviewPlayers.clear()
    }

    private fun addAction(row: LinearLayout, title: String, desc: String, click: () -> Unit) {
        val cornerRadius = dp(16).toFloat()

        fun cardBackground(focused: Boolean): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(cornerRadius)
                setColor(
                    if (focused) Color.argb(190, 38, 58, 88)
                    else Color.argb(125, 22, 32, 48)
                )
                setStroke(
                    if (focused) dp(2) else dp(1),
                    if (focused) accent else Color.argb(90, 126, 190, 255)
                )
            }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = cardBackground(false)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = "$title. $desc"
            setOnClickListener { click() }

            setOnFocusChangeListener { view, focused ->
                if (focused) {
                    playNavigationSound()
                    lastFocusedTitle = title
                    applyWallpaper(wallpaperTarget)
                }

                view.background = cardBackground(focused)
                view.animate().cancel()
                view.animate()
                    .scaleX(if (focused) 1.035f else 1f)
                    .scaleY(if (focused) 1.035f else 1f)
                    .setDuration(150L)
                    .start()
            }

            setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    val index = actions.indexOf(view)
                    when (keyCode) {
                        KeyEvent.KEYCODE_MENU -> {
                            showGlassSidebar()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index > 0) actions[index - 1].requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index >= 0 && index < actions.lastIndex) actions[index + 1].requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            playSelectSound()
                            view.performClick()
                            true
                        }
                        else -> false
                    }
                }
            }
        }

        val number = (actions.size + 1).toString().padStart(2, '0')
        val category = when (title) {
            "Live TV" -> "ANTENA DIGITAL"
            "Entertainment" -> "PILIHAN APLIKASI"
            "All Apps" -> "SEMUA APLIKASI"
            "Kamera CCTV" -> "PANTAU LANGSUNG"
            else -> "PREFERENSI"
        }
        card.addView(label("$number  /  $category", 8, Color.rgb(164, 183, 208), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLetterSpacing(0.055f)
        })
        val accentLine = View(this).apply {
            val accentDrawable = GradientDrawable()
            accentDrawable.setCornerRadius(dp(2).toFloat())
            accentDrawable.setColor(Color.rgb(199, 185, 153))
            setBackground(accentDrawable)
        }
        card.addView(accentLine, LinearLayout.LayoutParams(dp(25), dp(2)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        card.addView(label(title, 13, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(label(desc, 9, Color.rgb(166, 183, 205), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })

        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        lp.setMargins(0, 0, dp(8), 0)
        row.addView(card, lp)
        actions.add(card)
    }


    /**
     * Shows the most recently observed YouTube/SmartTube media session.
     * Without metadata, the card only offers the opt-in settings path.
     */
    private fun addContinueWatching(mediaRow: LinearLayout) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        continueBackdropArtwork = null
        val watchItems = prefs.all.keys.filter { it.startsWith("watch_title_") }.mapNotNull { key ->
            val pkg = key.removePrefix("watch_title_")
            val title = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val duration = prefs.getLong("watch_duration_$pkg", 0L)
            val position = prefs.getLong("watch_position_$pkg", 0L)
            val updated = prefs.getLong("watch_updated_$pkg", 0L)
            if (duration < 180_000L || position < 15_000L || updated <= 0L) null
            else Triple(pkg, title, updated)
        }.sortedByDescending { it.third }.take(6)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), dp(4))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label("LANJUTKAN MENONTON", 11, Color.rgb(205, 216, 232), true))
        heading.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        heading.addView(label(if (watchItems.size > 1) "${watchItems.size} TERAKHIR" else "PROGRES TONTONAN", 9,
            Color.rgb(165, 184, 208), false))
        outer.addView(heading, LinearLayout.LayoutParams(-1, dp(20)))

        val cardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        if (watchItems.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = continueCardBackground(false)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                contentDescription = "Lanjutkan menonton. Aktifkan akses media"
                setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    catch (_: Exception) { toast("Aktifkan akses notifikasi GlassTV di pengaturan TV.") }
                }
                setOnFocusChangeListener { view, focused ->
                    if (focused) {
                        playNavigationSound()
                        lastFocusedTitle = "LANJUTKAN MENONTON"
                    }
                    view.background = continueCardBackground(focused)
                }
            }
            empty.addView(label("Belum ada progres tontonan", 13, Color.WHITE, true))
            empty.addView(label("Aktifkan akses media untuk YouTube / SmartTube", 10, Color.rgb(180, 198, 220), false))
            continueWatchingCard = empty
            cardRow.addView(empty, LinearLayout.LayoutParams(-1, dp(92)))
        } else {
            val count = if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 1500f) 6 else 5
            watchItems.take(count).forEachIndexed { index, item ->
                val (pkg, title, _) = item
                val artworkName = prefs.getString("watch_artwork_$pkg", null)
                val artworkFile = artworkName?.let { File(filesDir, it) }
                    ?.takeIf { it.isFile && it.length() > 0L }
                if (index == 0) continueBackdropArtwork = artworkFile
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.TOP
                    setPadding(dp(6), dp(5), dp(6), dp(5))
                    background = continueCardBackground(false)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    contentDescription = "Lanjutkan menonton: $title"
                    setOnFocusChangeListener { view, focused ->
                        if (focused) {
                            continueWatchingCard = view
                            playNavigationSound()
                            lastFocusedTitle = "LANJUTKAN MENONTON"
                            applyContinueWatchingBackdrop(artworkFile)
                        } else {
                            view.post {
                                if (!view.hasFocus() && !isFinishing &&
                                    (continueWatchingCard?.hasFocus() != true ||
                                     continueWatchingCard !== view)) {
                                    applyWallpaper(wallpaperTarget)
                                }
                            }
                        }
                        view.background = continueCardBackground(focused)
                        view.scaleX = if (focused) 1.025f else 1f
                        view.scaleY = if (focused) 1.025f else 1f
                    }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) false else when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                playSelectSound(); view.performClick(); true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> { actions.lastOrNull()?.requestFocus(); true }
                            else -> false
                        }
                    }
                    setOnClickListener { openContinueWatchingApp(pkg) }
                }
                if (index == 0) continueWatchingCard = card
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { setColor(Color.rgb(18, 25, 37)); cornerRadius = dp(7).toFloat() }
                    clipToOutline = true
                    contentDescription = "Artwork $title"
                    var loaded = false
                    if (artworkFile != null) {
                        try {
                            val bitmap = BitmapFactory.decodeFile(artworkFile.absolutePath)
                            if (bitmap != null) { setImageBitmap(bitmap); loaded = true }
                        } catch (_: Exception) { }
                    }
                    if (!loaded) {
                        try { setImageDrawable(packageManager.getApplicationIcon(pkg)); scaleType = ImageView.ScaleType.FIT_CENTER }
                        catch (_: Exception) { }
                    }
                }
                card.addView(thumb, LinearLayout.LayoutParams(-1, dp(56)))
                card.addView(label(title, 10, Color.WHITE, true).apply {
                    maxLines = 2
                    minHeight = dp(26)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
                val duration = prefs.getLong("watch_duration_$pkg", 0L).coerceAtLeast(1L)
                val storedPosition = prefs.getLong("watch_position_$pkg", 0L)
                val updated = prefs.getLong("watch_updated_$pkg", 0L)
                val speed = prefs.getFloat("watch_speed_$pkg", 0f).coerceIn(0f, 2f)
                val estimated = (storedPosition + ((System.currentTimeMillis() - updated).coerceAtLeast(0L) * speed).toLong())
                    .coerceIn(0L, duration)
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    this.progress = ((estimated.toDouble() / duration.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                    progressTintList = ColorStateList.valueOf(accent)
                    progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(51, 66, 86))
                }
                card.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
                card.addView(label("${formatWatchTime(estimated)} / ${formatWatchTime(duration)}", 8,
                    Color.rgb(185, 199, 218), false).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
                val lp = LinearLayout.LayoutParams(dp(126), dp(112))
                lp.setMargins(0, 0, if (index == count - 1 || index == watchItems.take(count).lastIndex) 0 else dp(7), 0)
                cardRow.addView(card, lp)
            }
        }
        val cardScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        cardScroll.addView(cardRow, android.widget.FrameLayout.LayoutParams(-2, dp(118)))
        outer.addView(cardScroll, LinearLayout.LayoutParams(-1, dp(118)))
        mediaRow.addView(outer, LinearLayout.LayoutParams(0, -1, 1.75f))
    }

    private fun addBackgroundAudioRow(container: LinearLayout) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val candidates = prefs.all.keys.filter { it.startsWith("audio_title_") }.mapNotNull { key ->
            val pkg = key.removePrefix("audio_title_")
            val title = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val updated = prefs.getLong("audio_updated_$pkg", 0L)
            val state = prefs.getInt("audio_state_$pkg", 0)
            if (updated <= 0L || System.currentTimeMillis() - updated > 6 * 60 * 60 * 1000L ||
                (state != 3 && state != 2)) null else Triple(pkg, title, updated)
        }.sortedByDescending { it.third }
        val item = candidates.firstOrNull() ?: return
        val (pkg, title, _) = item
        val artist = prefs.getString("audio_artist_$pkg", "").orEmpty().ifBlank { "Audio dari aplikasi lain" }
        val album = prefs.getString("audio_album_$pkg", "").orEmpty()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(155, 18, 28, 43))
                setStroke(dp(1), Color.argb(100, 153, 194, 255))
            }
            isFocusable = true
            isClickable = true
            contentDescription = "Sedang diputar: $title, $artist"
            setOnFocusChangeListener { view, focused ->
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (focused) Color.argb(230, 42, 65, 92) else Color.argb(155, 18, 28, 43))
                    setStroke(dp(if (focused) 2 else 1), if (focused) accent else Color.argb(100, 153, 194, 255))
                }
                if (focused) playNavigationSound()
            }
            setOnClickListener { playSelectSound(); openContinueWatchingApp(pkg) }
            setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    view.performClick(); true
                } else false
            }
        }
        val artworkName = prefs.getString("watch_artwork_$pkg", null)
        val artwork = artworkName?.let { File(filesDir, it) }?.takeIf { it.isFile && it.length() > 0L }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(24, 34, 49)) }
            clipToOutline = true
            if (artwork != null) {
                try { setImageBitmap(BitmapFactory.decodeFile(artwork.absolutePath)) } catch (_: Exception) { }
            }
            if (drawable == null) {
                try { setImageDrawable(packageManager.getApplicationIcon(pkg)); scaleType = ImageView.ScaleType.FIT_CENTER } catch (_: Exception) { }
            }
        }
        row.addView(image, LinearLayout.LayoutParams(dp(52), dp(52)))
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        textCol.addView(label("SEDANG DIPUTAR", 9, accent, true))
        textCol.addView(label(title, 13, Color.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(label(if (album.isBlank()) artist else "$artist  •  $album", 10, Color.rgb(176, 193, 215), false).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("BUKA PEMUTAR  ›", 10, accent, true))
        container.addView(row, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(5) })
    }

    /**
     * When Continue Watching receives remote focus, use that title's own artwork as
     * a soft, darkened backdrop. A small decoded bitmap is intentionally enlarged to
     * create a blur-like effect that also works on Android 11 (API 30).
     */
    private fun applyContinueWatchingBackdrop(artworkFile: File?) {
        if (artworkFile == null || !artworkFile.isFile || artworkFile.length() <= 0L) {
            applyWallpaper(wallpaperTarget)
            return
        }
        Thread({
            try {
                // Decode at a moderate reduction: retain the artwork's composition instead of an overly enlarged pixelated image.
                val options = BitmapFactory.Options().apply { inSampleSize = 3 }
                val bitmap = BitmapFactory.decodeFile(artworkFile.absolutePath, options) ?: return@Thread
                val image = BitmapDrawable(resources, bitmap).apply { gravity = Gravity.FILL }
                val blurScrim = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.argb(72, 4, 8, 16),
                        Color.argb(96, 5, 9, 17),
                        Color.argb(132, 4, 7, 13)
                    )
                )
                runOnUiThread {
                    if (!isFinishing && continueWatchingCard?.hasFocus() == true) {
                        root.background = LayerDrawable(arrayOf(image, blurScrim))
                    } else {
                        bitmap.recycle()
                    }
                }
            } catch (_: Exception) { }
        }, "glasstv-watch-backdrop").start()
    }

    private fun continueCardBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (focused) Color.argb(190, 28, 39, 54) else Color.argb(92, 17, 23, 32))
        setStroke(if (focused) dp(2) else dp(1),
            if (focused) accent else Color.argb(82, 126, 190, 255))
    }

    private fun formatWatchTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun openContinueWatchingApp(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) startActivity(intent)
            else toast("Aplikasi tontonan tidak ditemukan. Buka aplikasinya dari menu Entertainment.")
        } catch (_: Exception) {
            toast("Aplikasi tidak dapat dibuka dari launcher.")
        }
    }

    // Never publish camera addresses or credentials in source control.
    // Existing on-TV values are read from shared preferences and remain untouched.
    private val defaultCameraUrls = listOf("", "", "", "")

    private fun showCameras() {
        try {
            startActivity(Intent(this, CctvActivity::class.java))
        } catch (_: Exception) {
            toast("Halaman CCTV tidak dapat dibuka.")
        }
    }

    private fun manageCamera(number: Int) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val savedUrl = prefs.getString("camera_$number", null)
            ?.takeIf { it.isNotBlank() } ?: defaultCameraUrls[number - 1]
        if (savedUrl.isBlank()) {
            askRtsp(number)
            return
        }
        val cameraName = prefs.getString("camera_name_$number", "Kamera $number")
            ?.takeIf { it.isNotBlank() } ?: "Kamera $number"
        AlertDialog.Builder(this)
            .setTitle(cameraName)
            .setMessage("URL RTSP tersimpan di perangkat:\n$savedUrl")
            .setPositiveButton("Buka") { _, _ -> openExternalStream(savedUrl) }
            .setNeutralButton("Edit URL") { _, _ -> askRtsp(number) }
            .setNegativeButton("Ganti nama") { _, _ -> renameCamera(number) }
            .show()
    }

    private fun renameCamera(number: Int) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val currentName = prefs.getString("camera_name_$number", "Kamera $number")
            ?.takeIf { it.isNotBlank() } ?: "Kamera $number"
        val input = EditText(this).apply {
            hint = "Contoh: Pintu depan"
            setSingleLine(true)
            setText(currentName)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Nama Kamera $number")
            .setMessage("Beri nama sesuai lokasi kamera. Nama disimpan di TV dan tidak mengubah URL RTSP.")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString("camera_name_$number", newName).apply()
                    toast("Nama kamera disimpan")
                    showCameras()
                } else {
                    toast("Nama kamera tidak boleh kosong")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askRtsp(number: Int) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "rtsp://alamat-kamera/stream"
            setSingleLine(true)
            setText(prefs.getString("camera_$number", "rtsp://"))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("URL Kamera $number")
            .setMessage("URL disimpan lokal di TV. Stream dibuka melalui aplikasi pemutar eksternal yang mendukung RTSP (mis. VLC).")
            .setView(input)
            .setPositiveButton("Simpan & buka") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("rtsp://", ignoreCase = true) ||
                    url.startsWith("rtsps://", ignoreCase = true)) {
                    prefs.edit().putString("camera_$number", url).apply()
                    openExternalStream(url)
                } else {
                    toast("URL harus diawali rtsp:// atau rtsps://")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askVideoUrl() {
        val input = EditText(this).apply {
            hint = "https://.../video.mp4"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Sumber video latar")
            .setMessage("Masukkan URL video HTTP(S). Pemutaran langsung bergantung pada codec dan dukungan firmware.")
            .setView(input)
            .setPositiveButton("Buka") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) openExternalStream(url)
                else toast("Masukkan URL HTTP(S) yang valid")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openExternalStream(url: String) {
        try {
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (intent.resolveActivity(packageManager) == null) {
                toast("Tidak ada aplikasi pemutar yang mendukung URL ini. Pasang pemutar yang kompatibel.")
                return
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("URL tidak valid atau tidak ada aplikasi yang dapat membuka stream.")
        }
    }

    private fun openLiveTv() {
        val pm = packageManager
        val preferredPackages = listOf(
            "com.mitv.livetv",
            "com.google.android.tv",
            "com.android.tv"
        )

        // Prefer known TV/tuner apps, but do not assume every vendor uses the same package name.
        for (pkg in preferredPackages) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
                val target = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setPackage(pkg)
                val resolved = pm.queryIntentActivities(target, 0).firstOrNull()?.activityInfo
                if (resolved != null) {
                    startActivity(Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(resolved.packageName, resolved.name))
                    return
                }
            } catch (_: Exception) {
                // Continue with runtime discovery below.
            }
        }

        data class TvCandidate(
            val packageName: String,
            val activityName: String,
            val label: String,
            val score: Int
        )

        val launcherIntents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        val activities = launcherIntents.flatMap { pm.queryIntentActivities(it, 0) }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }

        val candidates = activities.mapNotNull { resolveInfo ->
            val info = resolveInfo.activityInfo
            if (info.packageName == packageName) return@mapNotNull null
            val appLabel = try {
                resolveInfo.loadLabel(pm).toString()
            } catch (_: Exception) {
                info.packageName
            }
            val p = info.packageName.lowercase(Locale.ROOT)
            val label = appLabel.lowercase(Locale.ROOT)
            val score = when {
                p == "com.mitv.livetv" -> 1000
                p == "com.google.android.tv" || p == "com.android.tv" -> 950
                p.contains("livetv") || p.contains("live_tv") -> 900
                p.contains("livechannels") || p.contains("live.channels") -> 850
                label == "live tv" || label == "live channels" || label == "tv" -> 800
                label.contains("live tv") || label.contains("live channel") -> 750
                p.contains("droidlogic") && (p.contains("tv") || label.contains("tv")) -> 650
                label.contains("digital tv") || label == "dtv" || p.contains("dtv") -> 600
                label.contains("television") -> 500
                else -> 0
            }
            if (score == 0) null else TvCandidate(info.packageName, info.name, appLabel, score)
        }.distinctBy { it.packageName }
            .sortedByDescending { it.score }

        if (candidates.isEmpty()) {
            toast("Aplikasi Live TV bawaan belum terdeteksi. Periksa menu Apps atau aplikasi TV sistem.")
            return
        }

        val strongest = candidates.first().score
        val topCandidates = candidates.filter { it.score == strongest }
        if (candidates.size == 1 || (strongest >= 850 && topCandidates.size == 1)) {
            launchTvCandidate(candidates.first().packageName, candidates.first().activityName)
            return
        }

        val labels = candidates.map { "${it.label}  •  ${it.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi Live TV")
            .setItems(labels) { _, which ->
                val candidate = candidates[which]
                launchTvCandidate(candidate.packageName, candidate.activityName)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun launchTvCandidate(packageName: String, activityName: String) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).setClassName(packageName, activityName)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) startActivity(launchIntent)
                else toast("Aplikasi TV tidak dapat dibuka dari launcher.")
            } catch (_: Exception) {
                toast("Aplikasi TV tidak dapat dibuka dari launcher.")
            }
        }
    }

    private fun queryTvApps(): List<android.content.pm.ResolveInfo> {
        val tvApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val regularApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        return (tvApps + regularApps)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.exported }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
    }

    private fun launchTvApp(packageName: String, activityName: String) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(packageName, activityName))
        } catch (_: Exception) {
            toast("Aplikasi tidak dapat dibuka. Coba jalankan dari menu sistem TV.")
        }
    }

    private fun showGlassSidebar() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(176, 3, 7, 14))
            isFocusableInTouchMode = true
            setOnClickListener { wallpaperTarget.removeView(this) }
            setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    wallpaperTarget.removeView(view); true
                } else false
            }
        }
        val canvas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(236, 24, 35, 51), Color.argb(229, 10, 17, 28))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.argb(180, 126, 190, 255))
            }
            elevation = dp(18).toFloat()
            isClickable = true
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
        }
        nav.addView(label("GLASSTV", 20, Color.WHITE, true).apply { setPadding(dp(8), dp(4), 0, dp(2)) })
        nav.addView(label("CONTROL CENTER", 9, accent, true).apply { setPadding(dp(8), 0, 0, dp(18)) })
        val menuItems = listOf(
            "Live TV" to { openLiveTv() },
            "Entertainment" to { openApps() },
            "All Apps" to { openAllApps() },
            "Kamera CCTV" to { showCameras() },
            "Pengaturan" to { showSettings() },
            "Lokasi & cuaca" to { showLocationPicker() }
        )
        var firstItem: View? = null
        menuItems.forEachIndexed { index, item ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(10), dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(80, 24, 39, 58))
                    setStroke(dp(1), Color.argb(65, 126, 190, 255))
                }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { view, focused ->
                    if (focused) playNavigationSound()
                    view.elevation = if (focused) dp(7).toFloat() else 0f
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (focused) Color.rgb(59, 91, 129) else Color.argb(80, 24, 39, 58))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.argb(65, 126, 190, 255))
                    }
                }
                setOnClickListener {
                    playSelectSound()
                    wallpaperTarget.removeView(overlay)
                    item.second()
                }
                setOnKeyListener { view, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        view.performClick(); true
                    } else false
                }
            }
            tile.addView(label(String.format(Locale.ROOT, "%02d", index + 1), 10, accent, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(26), -2))
            tile.addView(label(item.first, 13, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            nav.addView(tile, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
            if (firstItem == null) firstItem = tile
        }
        nav.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        nav.addView(label("Pilih menu dengan remote TV", 10, Color.rgb(154, 174, 200), false).apply {
            setPadding(dp(8), dp(8), 0, dp(4))
        })
        canvas.addView(nav, LinearLayout.LayoutParams(dp(270), -1))
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), dp(22), dp(28), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(98, 56, 78, 105), Color.argb(42, 18, 28, 43))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.argb(75, 190, 216, 255))
            }
        }
        preview.addView(label("RUANG HIBURAN", 10, accent, true))
        preview.addView(label("Semua yang ingin Anda tonton.", 24, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(8))
            maxLines = 2
        })
        preview.addView(label("Lanjutkan tontonan, pantau CCTV, atau buka aplikasi favorit dari satu tempat.", 13, Color.rgb(192, 207, 226), false).apply {
            maxLines = 3
            setPadding(0, 0, 0, dp(18))
        })
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val audioTitle = prefs.getString("audio_title", null)?.takeIf { it.isNotBlank() }
        if (audioTitle != null) {
            preview.addView(label("SEDANG DIPUTAR", 9, accent, true).apply { setPadding(0, 0, 0, dp(4)) })
            preview.addView(label(audioTitle, 15, Color.WHITE, true).apply { maxLines = 2 })
            preview.addView(label(prefs.getString("audio_artist", "").orEmpty(), 11, Color.rgb(169, 189, 214), false)
                .apply { setPadding(0, dp(3), 0, dp(14)); maxLines = 1 })
        }
        val snapshot = dashboardSnapshot
        preview.addView(label("CUACA  /  " + (snapshot?.city ?: "Jakarta Timur"), 10, accent, true)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        preview.addView(label(snapshot?.let { "${it.temperature}  •  ${it.condition}" } ?: "Memuat cuaca dan waktu salat…",
            13, Color.WHITE, false).apply { maxLines = 2 })
        canvas.addView(preview, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
        overlay.addView(canvas, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(28), dp(28), dp(28), dp(28))
        })
        wallpaperTarget.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        firstItem?.requestFocus()
    }

    private fun openAllApps() {
        val apps = queryTvApps()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        showAppGrid("SEMUA APLIKASI", apps)
    }

    private fun openApps() {
        val apps = queryTvApps()
        val selected = getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getStringSet("entertainment_packages", emptySet())?.toSet().orEmpty()
        val favorites = apps.filter { it.activityInfo.packageName in selected }
        if (favorites.isEmpty()) {
            showAppGrid("ENTERTAINMENT", emptyList(), listOf(
                "Pilih aplikasi" to { manageEntertainmentApps() },
                "Semua aplikasi" to { openAllApps() }
            ))
        } else {
            showAppGrid("ENTERTAINMENT", favorites, listOf(
                "Atur Entertainment" to { manageEntertainmentApps() },
                "Semua aplikasi" to { openAllApps() }
            ))
        }
    }

    private fun showAppGrid(
        title: String,
        apps: List<android.content.pm.ResolveInfo>,
        extraActions: List<Pair<String, () -> Unit>> = emptyList()
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        content.addView(label("Pilih kartu lalu tekan OK pada remote.", 11, Color.rgb(169, 190, 216), false)
            .apply { setPadding(dp(4), 0, dp(4), dp(10)) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        val allCards = mutableListOf<Triple<String, android.graphics.drawable.Drawable?, (() -> Unit)>>()
        apps.forEach { app ->
            val info = app.activityInfo
            allCards.add(Triple(app.loadLabel(packageManager).toString(), try { app.loadIcon(packageManager) } catch (_: Exception) { null }, {
                launchTvApp(info.packageName, info.name)
            }))
        }
        extraActions.forEachIndexed { index, action ->
            val iconId = if (index == 0) android.R.drawable.ic_menu_manage else android.R.drawable.ic_menu_more
            allCards.add(Triple(action.first, getDrawable(iconId), action.second))
        }
        val columns = 5
        allCards.chunked(columns).forEach { group ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            group.forEach { (name, iconDrawable, click) ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(8), dp(5), dp(7))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.argb(118, 23, 34, 51))
                        setStroke(dp(1), Color.argb(75, 153, 194, 255))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.scaleX = if (focused) 1.035f else 1f
                        view.scaleY = if (focused) 1.035f else 1f
                        view.elevation = if (focused) dp(8).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(if (focused) Color.rgb(57, 87, 123) else Color.argb(118, 23, 34, 51))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.argb(75, 153, 194, 255))
                        }
                    }
                    setOnClickListener { playSelectSound(); click() }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            view.performClick(); true
                        } else false
                    }
                }
                val icon = ImageView(this).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                card.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
                card.addView(label(name, 10, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(-1, dp(31)))
                line.addView(card, LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            repeat(columns - group.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            rows.addView(line, LinearLayout.LayoutParams(-1, dp(108)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Tutup", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            minOf(dp(780), (resources.displayMetrics.widthPixels * 0.88f).toInt()),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun manageEntertainmentApps() {
        val apps = queryTvApps()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selected = prefs.getStringSet("entertainment_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ENTERTAINMENT  /  PILIH APLIKASI")
            .setView(scroll)
            .setPositiveButton("Simpan", null)
            .setNeutralButton("Pilih semua", null)
            .setNegativeButton("Batal", null)
            .create()
        fun renderApps() {
            list.removeAllViews()
            list.addView(label("Pilih aplikasi yang ingin ditampilkan di baris Entertainment.", 11, Color.rgb(170, 189, 213), false)
                .apply { setPadding(dp(4), dp(2), dp(4), dp(10)) })
            apps.forEach { resolve ->
                val info = resolve.activityInfo
                val pkg = info.packageName
                val isSelected = pkg in selected
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(if (isSelected) Color.argb(190, 37, 61, 88) else Color.argb(115, 25, 36, 53))
                        setStroke(dp(1), if (isSelected) Color.argb(175, 126, 190, 255) else Color.argb(55, 153, 194, 255))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.alpha = 1f
                        view.scaleX = if (focused) 1.025f else 1f
                        view.scaleY = if (focused) 1.025f else 1f
                        view.elevation = if (focused) dp(7).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(11).toFloat()
                            setColor(if (focused) Color.rgb(62, 99, 143) else Color.argb(115, 25, 36, 53))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.argb(55, 153, 194, 255))
                        }
                    }
                    setOnClickListener {
                        playSelectSound()
                        if (isSelected) selected.remove(pkg) else selected.add(pkg)
                        renderApps()
                    }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            view.performClick(); true
                        } else false
                    }
                }
                val icon = ImageView(this).apply {
                    try { setImageDrawable(resolve.loadIcon(packageManager)) } catch (_: Exception) { }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                }
                texts.addView(label(resolve.loadLabel(packageManager).toString(), 13, Color.WHITE, true))
                texts.addView(label(if (isSelected) "Dipilih untuk Entertainment" else "Ketuk untuk menambahkan", 10, Color.rgb(170, 189, 213), false))
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(if (isSelected) "✓" else "+", 19, if (isSelected) accent else Color.rgb(160, 178, 201), true))
                list.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) })
            }
        }
        renderApps()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit().putStringSet("entertainment_packages", selected.toSet()).apply()
                dialog.dismiss()
                toast("Daftar Entertainment disimpan")
                showHome()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                selected.clear()
                apps.forEach { selected.add(it.activityInfo.packageName) }
                renderApps()
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            minOf(dp(650), (resources.displayMetrics.widthPixels * 0.88f).toInt()),
            (resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
    }

    private fun currentGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val salutation = when {
            hour < 11 -> "Selamat pagi"
            hour < 15 -> "Selamat siang"
            hour < 18 -> "Selamat sore"
            else -> "Selamat malam"
        }
        val name = getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getString("greeting_name", "").orEmpty().trim()
        return if (name.isBlank()) salutation else "$salutation, $name"
    }

    private fun showGreetingEditor() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val nameInput = EditText(this).apply {
            hint = "Nama (boleh dikosongkan)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(prefs.getString("greeting_name", "").orEmpty())
            setSelection(text.length)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(label("Nama untuk sapaan di layar utama", 12, Color.rgb(185, 199, 219), true))
            addView(nameInput, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Sapaan GlassTV")
            .setView(form)
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putString("greeting_name", nameInput.text.toString().trim()).apply()
                dashboardGreetingLabel?.text = currentGreeting()
                toast("Sapaan disimpan")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("GLASSTV  /  PENGATURAN")
            .setView(scroll)
            .setNegativeButton("Tutup", null)
            .create()
        fun settingRow(title: String, detail: String, state: String? = null, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(135, 25, 36, 53))
                    setStroke(dp(1), Color.argb(70, 153, 194, 255))
                }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { view, focused ->
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (focused) Color.argb(225, 46, 70, 101) else Color.argb(135, 25, 36, 53))
                        setStroke(dp(if (focused) 2 else 1), if (focused) accent else Color.argb(70, 153, 194, 255))
                    }
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.elevation = if (focused) dp(8).toFloat() else 0f
                    if (focused) playNavigationSound()
                }
                setOnClickListener { playSelectSound(); action() }
                setOnKeyListener { view, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        view.performClick(); true
                    } else false
                }
            }
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(label(title, 14, Color.WHITE, true))
            textColumn.addView(label(detail, 10, Color.rgb(170, 189, 213), false).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            if (state != null) {
                row.addView(label(state, 10, accent, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.argb(80, 126, 190, 255))
                    }
                })
            } else row.addView(label("›", 23, accent, false))
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        }
        fun reopen(message: String) {
            dialog.dismiss()
            toast(message)
            showSettings()
        }
        settingRow("Lokasi cuaca & waktu salat", "Cari kota di Indonesia atau pilih lokasi tersimpan", DashboardServices.selectedCity(this).name) {
            dialog.dismiss(); showLocationPicker()
        }
        settingRow("Aplikasi Entertainment", "Pilih aplikasi yang muncul di bagian hiburan") {
            dialog.dismiss(); manageEntertainmentApps()
        }
        settingRow("Dock aplikasi favorit", "Atur ikon aplikasi favorit dan urutannya") {
            dialog.dismiss(); manageFavoriteDock()
        }
        val soundOn = prefs.getBoolean("ui_sound_enabled", true)
        settingRow("Suara navigasi", "Bunyi singkat saat fokus dan memilih kartu", if (soundOn) "AKTIF" else "MATI") {
            prefs.edit().putBoolean("ui_sound_enabled", !soundOn).apply()
            reopen("Suara navigasi ${if (!soundOn) "diaktifkan" else "dimatikan"}")
        }
        val azanOn = prefs.getBoolean("azan_enabled", true)
        settingRow("Azan otomatis", "Putar azan saat waktu salat tiba", if (azanOn) "AKTIF" else "MATI") {
            val enable = !azanOn
            prefs.edit().putBoolean("azan_enabled", enable).apply()
            if (!enable) {
                try { azanPlayer?.stop() } catch (_: Exception) { }
                try { azanPlayer?.release() } catch (_: Exception) { }
                azanPlayer = null
            }
            updateAzanQuickButton()
            reopen("Azan otomatis ${if (enable) "diaktifkan" else "dimatikan"}")
        }
        settingRow("Uji suara azan", "Putar contoh audio untuk memastikan suara berfungsi") {
            dialog.dismiss(); playAzan()
        }
        val wallpaperAuto = prefs.getBoolean("wallpaper_auto", true)
        settingRow("Wallpaper otomatis", "Ganti wallpaper secara berkala", if (wallpaperAuto) "AKTIF" else "MATI") {
            prefs.edit().putBoolean("wallpaper_auto", !wallpaperAuto).apply()
            scheduleWallpaperRotation()
            reopen("Wallpaper otomatis ${if (!wallpaperAuto) "diaktifkan" else "dimatikan"}")
        }
        settingRow("Wallpaper berikutnya", "Ganti ke foto latar berikutnya") {
            val next = (prefs.getInt("wallpaper_index", 0) + 1) % wallpaperUrls.size
            prefs.edit().putInt("wallpaper_index", next).apply()
            applyWallpaper(wallpaperTarget)
            dialog.dismiss(); toast("Wallpaper diganti")
        }
        settingRow("Google Ambient / Art Gallery", "Buka pengaturan screensaver Android TV") {
            dialog.dismiss(); openGoogleAmbientSettings()
        }
        settingRow("Edit nama sapaan", "Ubah sapaan di panel jam dan cuaca") {
            dialog.dismiss(); showGreetingEditor()
        }
        settingRow("Info GlassTV", "Sumber cuaca, jadwal salat, wallpaper, dan izin yang digunakan") {
            dialog.dismiss()
            AlertDialog.Builder(this).setTitle("GlassTV Launcher")
                .setMessage(
                    "• Cuaca: BMKG untuk lokasi pilihan Jakarta Timur; Open-Meteo untuk kota hasil pencarian\n" +
                    "• Jadwal salat: AlAdhan, metode 20°/18° dengan penyesuaian 2 menit\n" +
                    "• Wallpaper fotografis berganti otomatis\n" +
                    "• Suara navigasi dan azan dapat dimatikan\n\n" +
                    "Data memerlukan internet. Launcher tidak meminta izin lokasi perangkat."
                ).setPositiveButton("Kembali") { _, _ -> showSettings() }.show()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(dp(700), (resources.displayMetrics.heightPixels * 0.82f).toInt())
        dialog.window?.decorView?.setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun favoritePackages(): List<String> {
        return getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getString("favorite_dock_order", "")
            .orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun addFavoriteDock(container: LinearLayout) {
        val chosen = favoritePackages()
        if (chosen.isEmpty()) return
        val apps = queryTvApps().associateBy { it.activityInfo.packageName }
        val available = chosen.mapNotNull { apps[it] }
        if (available.isEmpty()) return
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(4))
        }
        section.addView(label("FAVORIT", 10, Color.rgb(150, 171, 199), true))
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        available.forEach { resolve ->
            val info = resolve.activityInfo
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(12), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(120, 27, 39, 57))
                    setStroke(dp(1), Color.argb(90, 153, 194, 255))
                }
                isFocusable = true
                isClickable = true
                contentDescription = "Favorit ${resolve.loadLabel(packageManager)}"
                setOnClickListener { launchTvApp(info.packageName, info.name) }
                setOnFocusChangeListener { view, focused ->
                    view.alpha = if (focused) 1f else 0.88f
                    if (focused) playNavigationSound()
                }
            }
            val icon = ImageView(this).apply {
                try { setImageDrawable(resolve.loadIcon(packageManager)) } catch (_: Exception) { }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            tile.addView(label(resolve.loadLabel(packageManager).toString(), 11, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(7), 0, 0, 0)
            })
            dock.addView(tile, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
        }
        section.addView(dock, LinearLayout.LayoutParams(-1, dp(48)))
        container.addView(section, LinearLayout.LayoutParams(-1, dp(68)))
    }

    private fun addPreviewChannelRows(container: LinearLayout) {
        val channels = TvPreviewChannels.read(this)
        if (channels.isEmpty()) return
        channels.forEach { channel ->
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5), 0, dp(6))
                clipChildren = false
                clipToPadding = false
            }
            section.addView(label(channel.title.uppercase(Locale.getDefault()), 10, Color.rgb(166, 188, 216), true))
            val horizontal = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
            }
            val cards = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2), dp(5), dp(2), dp(5))
                clipChildren = false
                clipToPadding = false
            }
            channel.programs.forEach { program ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.argb(118, 22, 32, 48))
                        setStroke(dp(1), Color.argb(78, 153, 194, 255))
                    }
                    isFocusable = true
                    isClickable = true
                    contentDescription = program.title
                    setOnClickListener {
                        val intent = TvPreviewChannels.launchIntent(this@MainActivity, channel, program)
                        if (intent != null) {
                            try { startActivity(intent) } catch (_: Exception) { toast("Video tidak dapat dibuka.") }
                        } else toast("Aplikasi " + channel.packageName + " tidak dapat dibuka.")
                    }
                    setOnFocusChangeListener { view, focused ->
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(if (focused) Color.argb(205, 40, 61, 88) else Color.argb(118, 22, 32, 48))
                            setStroke(dp(if (focused) 2 else 1), Color.argb(if (focused) 210 else 78, 153, 194, 255))
                        }
                        if (focused) playNavigationSound()
                    }
                }
                val art = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        cornerRadius = dp(7).toFloat()
                        setColor(Color.rgb(24, 32, 45))
                    }
                }
                tile.addView(art, LinearLayout.LayoutParams(dp(164), dp(78)))
                tile.addView(label(program.title, 11, Color.WHITE, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(4), dp(2), 0)
                }, LinearLayout.LayoutParams(dp(164), dp(34)))
                cards.addView(tile, LinearLayout.LayoutParams(-2, dp(124)).apply { rightMargin = dp(9) })
                loadPreviewArtwork(program.artwork, art)
            }
            horizontal.addView(cards)
            section.addView(horizontal, LinearLayout.LayoutParams(-1, dp(134)))
            container.addView(section, LinearLayout.LayoutParams(-1, dp(155)))
        }
    }

    private fun loadPreviewArtwork(source: String?, target: ImageView) {
        if (source.isNullOrBlank()) return
        Thread({
            try {
                val uri = Uri.parse(source)
                val bitmap = if (uri.scheme == "content") {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } else {
                    val connection = URL(source).openConnection().apply {
                        connectTimeout = 5000
                        readTimeout = 7000
                    }
                    connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                }
                if (bitmap != null) runOnUiThread { if (!isFinishing) target.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }, "glasstv-preview-art").start()
    }

    private fun manageFavoriteDock() {
        val apps = queryTvApps()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val ordered = favoritePackages().filter { pkg -> apps.any { it.activityInfo.packageName == pkg } }.toMutableList()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("FAVORIT  /  ATUR DOCK APLIKASI")
            .setView(scroll)
            .setPositiveButton("Simpan", null)
            .setNeutralButton("Kosongkan", null)
            .setNegativeButton("Batal", null)
            .create()
        fun renderApps() {
            list.removeAllViews()
            list.addView(label("Pilih aplikasi favorit. Nomor menunjukkan urutan ikon pada dock.", 11, Color.rgb(170, 189, 213), false)
                .apply { setPadding(dp(4), dp(2), dp(4), dp(10)) })
            apps.forEach { resolve ->
                val info = resolve.activityInfo
                val pkg = info.packageName
                val order = ordered.indexOf(pkg)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(if (order >= 0) Color.argb(190, 37, 61, 88) else Color.argb(115, 25, 36, 53))
                        setStroke(dp(1), if (order >= 0) Color.argb(175, 126, 190, 255) else Color.argb(55, 153, 194, 255))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        view.alpha = if (focused) 1f else 0.94f
                        if (focused) playNavigationSound()
                    }
                    setOnClickListener {
                        playSelectSound()
                        if (order >= 0) ordered.remove(pkg) else ordered.add(pkg)
                        renderApps()
                    }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            view.performClick(); true
                        } else false
                    }
                }
                val icon = ImageView(this).apply {
                    try { setImageDrawable(resolve.loadIcon(packageManager)) } catch (_: Exception) { }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                }
                texts.addView(label(resolve.loadLabel(packageManager).toString(), 13, Color.WHITE, true))
                texts.addView(label(if (order >= 0) "Favorit ke-${order + 1}" else "Ketuk untuk menambahkan", 10, Color.rgb(170, 189, 213), false))
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(if (order >= 0) "${order + 1}" else "+", 17, if (order >= 0) accent else Color.rgb(160, 178, 201), true))
                list.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) })
            }
        }
        renderApps()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit().putString("favorite_dock_order", ordered.joinToString(",")).apply()
                dialog.dismiss()
                showHome()
                toast("Dock favorit disimpan")
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ordered.clear()
                renderApps()
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(650), (resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun openGoogleAmbientSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            catch (_: Exception) { toast("Pengaturan screensaver Android tidak dapat dibuka dari launcher.") }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showHome()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        dashboardHandler.removeCallbacksAndMessages(null)
        wallpaperHandler.removeCallbacksAndMessages(null)
        try { toneGenerator?.release() } catch (_: Exception) { }
        toneGenerator = null
        try { azanPlayer?.release() } catch (_: Exception) { }
        azanPlayer = null
        super.onDestroy()
    }

    private fun scheduleWallpaperRotation() {
        wallpaperHandler.removeCallbacks(wallpaperCycle)
        if (getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("wallpaper_auto", true)) {
            wallpaperHandler.postDelayed(wallpaperCycle, 8 * 60 * 1000L)
        }
    }

    private fun applyWallpaper(target: FrameLayout) {
        val index = getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getInt("wallpaper_index", 0).mod(wallpaperUrls.size)
        val file = File(cacheDir, "glasstv_wallpaper_$index.jpg")
        fun display(bytesFile: File) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bitmap = BitmapFactory.decodeFile(bytesFile.absolutePath, options) ?: return
                val image = BitmapDrawable(resources, bitmap).apply { gravity = Gravity.FILL }
                val scrim = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(66, 4, 8, 16), Color.argb(92, 5, 9, 17), Color.argb(125, 4, 7, 13))
                )
                runOnUiThread {
                    if (!isFinishing && target === wallpaperTarget) target.background = LayerDrawable(arrayOf(image, scrim))
                }
            } catch (_: Exception) { }
        }
        if (file.exists() && file.length() > 12_000L) {
            display(file)
            return
        }
        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = URL(wallpaperUrls[index]).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "GlassTVLauncher/0.5")
                connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                if (file.length() > 12_000L) display(file) else file.delete()
            } catch (_: Exception) {
                try { file.delete() } catch (_: Exception) { }
            } finally {
                connection?.disconnect()
            }
        }, "glasstv-wallpaper").start()
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(18), dp(11))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(36, 55, 79), Color.rgb(16, 25, 39))
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(190, 126, 190, 255))
            }
            elevation = dp(12).toFloat()
        }
        val mark = TextView(this).apply {
            text = "G"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(130, 126, 190, 255))
            }
        }
        panel.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(11) })
        panel.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 3
        }, LinearLayout.LayoutParams(-2, -2))
        Toast(this).apply {
            duration = Toast.LENGTH_LONG
            view = panel
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(32))
        }.show()
    }
}

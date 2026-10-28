package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.HorizontalScrollView
import android.view.KeyEvent
import android.view.View
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
            applyWallpaper(root)
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 15, 24), Color.rgb(5, 8, 14))
            )
            isFocusableInTouchMode = true
            clipChildren = false
            clipToPadding = false
        }

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

        applyWallpaper(root)
        scheduleWallpaperRotation()
        addDashboardPanel()

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        section.addView(label("JELAJAHI TV", 11, Color.rgb(168, 188, 214), true))
        section.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        section.addView(label("Gunakan tombol arah dan OK", 10, Color.rgb(112, 131, 155), false))
        root.addView(section, LinearLayout.LayoutParams(-1, dp(17)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(1))
            clipChildren = false
            clipToPadding = false
        }
        addAction(row, "Live TV", "Siaran antena") { openLiveTv() }
        addAction(row, "Entertainment", "Pilihan aplikasi") { openApps() }
        addAction(row, "All Apps", "Semua aplikasi TV") { openAllApps() }
        addAction(row, "Kamera CCTV", "2 kamera • RTSP") { showCameras() }
        addAction(row, "Pengaturan", "Preferensi GlassTV") { showSettings() }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(72)))
        addFavoriteDock(root)
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        root.addView(mediaRow, LinearLayout.LayoutParams(-1, dp(142)))
        addContinueWatching(mediaRow)
        addHomeCctvPreview(mediaRow)
        setContentView(root)
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
        dashboardNextPrayerLabel = label("SALAT BERIKUTNYA  •  Memuat jadwal…", 11, Color.rgb(224, 233, 247), true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), dp(8))
            setLetterSpacing(0.035f)
        }
        prayerPanel.addView(dashboardNextPrayerLabel)
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
        val cities = DashboardServices.cities
        AlertDialog.Builder(this)
            .setTitle("Pilih lokasi cuaca & waktu salat")
            .setSingleChoiceItems(cities.map { if (it.name == current.name) "✓  ${it.name}" else it.name }.toTypedArray(),
                cities.indexOfFirst { it.name == current.name }) { dialog, which ->
                DashboardServices.selectCity(this, cities[which])
                dashboardSnapshot = null
                lastDashboardFetchAttempt = 0L
                dialog.dismiss()
                showHome()
                toast("Lokasi diubah ke ${cities[which].name}")
            }
            .setNegativeButton("Batal", null)
            .show()
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

    private fun playAzan() {
        try {
            azanPlayer?.release()
            azanPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(azanAudioUrl)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    try { it.release() } catch (_: Exception) { }
                    if (azanPlayer === it) azanPlayer = null
                }
                setOnErrorListener { player, _, _ ->
                    try { player.release() } catch (_: Exception) { }
                    if (azanPlayer === player) azanPlayer = null
                    toast("Suara azan tidak dapat diputar. Periksa koneksi internet TV.")
                    true
                }
                prepareAsync()
            }
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
                                if (!view.hasFocus() && continueWatchingCard?.hasFocus() != true && !isFinishing) {
                                    applyWallpaper(root)
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
                card.addView(thumb, LinearLayout.LayoutParams(-1, dp(42)))
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
                val lp = LinearLayout.LayoutParams(dp(104), dp(92))
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
        cardScroll.addView(cardRow, android.widget.FrameLayout.LayoutParams(-2, dp(98)))
        outer.addView(cardScroll, LinearLayout.LayoutParams(-1, dp(98)))
        mediaRow.addView(outer, LinearLayout.LayoutParams(0, -1, 1.85f))
    }

    /**
     * When Continue Watching receives remote focus, use that title's own artwork as
     * a soft, darkened backdrop. A small decoded bitmap is intentionally enlarged to
     * create a blur-like effect that also works on Android 11 (API 30).
     */
    private fun applyContinueWatchingBackdrop(artworkFile: File?) {
        if (artworkFile == null || !artworkFile.isFile || artworkFile.length() <= 0L) {
            applyWallpaper(root)
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

    private fun queryTvApps() = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
    ).filter { it.activityInfo.packageName != packageName }
        .distinctBy { it.activityInfo.packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }

    private fun launchTvApp(packageName: String, activityName: String) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(packageName, activityName))
        } catch (_: Exception) {
            toast("Aplikasi tidak dapat dibuka. Coba jalankan dari menu sistem TV.")
        }
    }

    private fun openAllApps() {
        val apps = queryTvApps()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this).setTitle("All Apps")
            .setItems(labels) { _, which ->
                val info = apps[which].activityInfo
                launchTvApp(info.packageName, info.name)
            }.setNegativeButton("Tutup", null).show()
    }

    private fun openApps() {
        val apps = queryTvApps()
        val selected = getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getStringSet("entertainment_packages", emptySet())?.toSet().orEmpty()
        val favorites = apps.filter { it.activityInfo.packageName in selected }
        val menu = mutableListOf<String>()
        favorites.forEach { menu.add(it.loadLabel(packageManager).toString()) }
        val allIndex = menu.size
        menu.add("Semua aplikasi…")
        val manageIndex = menu.size
        menu.add("Atur daftar Entertainment…")
        if (menu.size == 2 && apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        AlertDialog.Builder(this).setTitle("Entertainment")
            .setItems(menu.toTypedArray()) { _, which ->
                when {
                    which < allIndex -> {
                        val info = favorites[which].activityInfo
                        launchTvApp(info.packageName, info.name)
                    }
                    which == allIndex -> openAllApps()
                    which == manageIndex -> manageEntertainmentApps()
                }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun manageEntertainmentApps() {
        val apps = queryTvApps()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val current = prefs.getStringSet("entertainment_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val checked = apps.map { it.activityInfo.packageName in current }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi Entertainment")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].activityInfo.packageName
                if (isChecked) current.add(pkg) else current.remove(pkg)
            }
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putStringSet("entertainment_packages", current.toSet()).apply()
                toast("Daftar Entertainment disimpan")
            }
            .setNeutralButton("Pilih semua") { _, _ ->
                prefs.edit().putStringSet("entertainment_packages",
                    apps.map { it.activityInfo.packageName }.toSet()).apply()
                toast("Semua aplikasi dipilih untuk Entertainment")
            }
            .setNegativeButton("Batal", null)
            .show()
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
        val soundOn = prefs.getBoolean("ui_sound_enabled", true)
        val azanOn = prefs.getBoolean("azan_enabled", true)
        val wallpaperAuto = prefs.getBoolean("wallpaper_auto", true)
        val entries = arrayOf(
            "Lokasi cuaca & waktu salat",
            "Pilih aplikasi untuk Entertainment",
            "Suara navigasi ala konsol: ${if (soundOn) "AKTIF" else "MATI"}",
            "Azan otomatis: ${if (azanOn) "AKTIF" else "MATI"}",
            "Uji suara azan",
            "Wallpaper berikutnya",
            "Wallpaper otomatis: ${if (wallpaperAuto) "AKTIF" else "MATI"}",
            "Edit nama sapaan",
            "Info GlassTV",
            "Atur dock aplikasi favorit",
            "Google Ambient / Art Gallery"
        )
        AlertDialog.Builder(this).setTitle("Pengaturan GlassTV")
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> showLocationPicker()
                    1 -> manageEntertainmentApps()
                    2 -> {
                        prefs.edit().putBoolean("ui_sound_enabled", !soundOn).apply()
                        toast("Suara navigasi ${if (!soundOn) "diaktifkan" else "dimatikan"}")
                    }
                    3 -> {
                        val enable = !azanOn
                        prefs.edit().putBoolean("azan_enabled", enable).apply()
                        if (!enable) {
                            try { azanPlayer?.stop() } catch (_: Exception) { }
                            try { azanPlayer?.release() } catch (_: Exception) { }
                            azanPlayer = null
                        }
                        toast("Azan otomatis ${if (enable) "diaktifkan" else "dimatikan"}")
                    }
                    4 -> playAzan()
                    5 -> {
                        val next = (prefs.getInt("wallpaper_index", 0) + 1) % wallpaperUrls.size
                        prefs.edit().putInt("wallpaper_index", next).apply()
                        applyWallpaper(root)
                        toast("Wallpaper diganti")
                    }
                    6 -> {
                        prefs.edit().putBoolean("wallpaper_auto", !wallpaperAuto).apply()
                        scheduleWallpaperRotation()
                        toast("Wallpaper otomatis ${if (!wallpaperAuto) "diaktifkan" else "dimatikan"}")
                    }
                    7 -> showGreetingEditor()
                    8 -> AlertDialog.Builder(this).setTitle("GlassTV Launcher")
                        .setMessage(
                            "Dashboard cuaca dan jadwal salat dengan lokasi manual.\n\n" +
                            "• Cuaca: prakiraan BMKG\n" +
                            "• Jadwal salat: AlAdhan, metode 20°/18° dengan penyesuaian 2 menit\n" +
                            "• Wallpaper fotografis berganti otomatis, dapat diatur\n" +
                            "• Efek navigasi lokal, dapat dimatikan\n" +
                            "• Suara azan melalui internet saat dashboard aktif\n\n" +
                            "Data cuaca dan azan memerlukan internet. Launcher tidak meminta izin lokasi perangkat."
                        ).setPositiveButton("OK", null).show()
                    9 -> manageFavoriteDock()
                    10 -> openGoogleAmbientSettings()
                }
            }.setNegativeButton("Tutup", null).show()
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

    private fun manageFavoriteDock() {
        val apps = queryTvApps()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val ordered = favoritePackages().filter { pkg -> apps.any { it.activityInfo.packageName == pkg } }.toMutableList()
        val labels = apps.map { resolve ->
            val pkg = resolve.activityInfo.packageName
            (if (pkg in ordered) "★  " else "") + resolve.loadLabel(packageManager).toString()
        }.toTypedArray()
        val checked = apps.map { it.activityInfo.packageName in ordered }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi dock favorit")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].activityInfo.packageName
                if (isChecked) {
                    if (pkg !in ordered) ordered.add(pkg)
                } else ordered.remove(pkg)
            }
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putString("favorite_dock_order", ordered.joinToString(",")).apply()
                showHome()
                toast("Dock favorit disimpan")
            }
            .setNeutralButton("Kosongkan") { _, _ ->
                prefs.edit().putString("favorite_dock_order", "").apply()
                showHome()
                toast("Dock favorit dikosongkan")
            }
            .setNegativeButton("Batal", null)
            .show()
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

    private fun applyWallpaper(target: LinearLayout) {
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
                    if (!isFinishing && target === root) target.background = LayerDrawable(arrayOf(image, scrim))
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
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

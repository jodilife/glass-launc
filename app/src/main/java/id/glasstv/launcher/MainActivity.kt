package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
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
    private var lastFocusedTitle: String = "Live TV"
    private var refreshOnResume = false
    private val homePreviewPlayers = mutableListOf<ExoPlayer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showHome()
    }

    override fun onPause() {
        refreshOnResume = true
        releaseHomePreviewPlayers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (refreshOnResume) {
            refreshOnResume = false
            showHome()
        }
    }

    private fun showHome() {
        actions.clear()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(26), dp(42), dp(28))
            setBackgroundColor(bg)
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
        brand.addView(label("GLASS", 29, Color.WHITE, true))
        brand.addView(label("TV", 29, accent, true))
        header.addView(brand)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val time = SimpleDateFormat("EEE, dd MMM  •  HH:mm", Locale.getDefault()).format(Date())
        header.addView(label(time, 15, Color.rgb(185, 199, 219), false))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))

        val subtitle = label("YOUR SCREEN. YOUR SPACE.", 12, accent, true).apply {
            setLetterSpacing(0.12f)
            setPadding(0, dp(3), 0, dp(10))
        }
        root.addView(subtitle)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(18), dp(26), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(28, 47, 72), Color.rgb(16, 24, 38), Color.rgb(13, 19, 30))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(100, 126, 190, 255))
            }
            clipToPadding = false
        }
        hero.addView(label("GLASS INTERFACE   /   ANDROID TV", 12, accent, true).apply {
            setLetterSpacing(0.08f)
        })
        hero.addView(label("Selamat datang kembali", 24, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        hero.addView(label(
            "Pilih aplikasi, buka kamera CCTV, atau putar video dari URL.\nPemutaran menggunakan aplikasi eksternal yang kompatibel.",
            13, Color.rgb(202, 214, 231), false
        ).apply {
            maxLines = 3
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })
        addHomeCctvPreview()
        addContinueWatching()

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        section.addView(label("AKSES CEPAT", 12, Color.rgb(160, 178, 201), true))
        section.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        section.addView(label("Pilih dengan remote  •  OK untuk membuka", 11, Color.rgb(133, 151, 174), false))
        root.addView(section, LinearLayout.LayoutParams(-1, dp(22)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
            clipChildren = false
            clipToPadding = false
        }
        addAction(row, "Live TV", "Siaran antena") { openLiveTv() }
        addAction(row, "Entertainment", "Aplikasi TV & musik") { openApps() }
        addAction(row, "Kamera CCTV", "2 kamera • RTSP") { showCameras() }
        addAction(row, "Sumber video", "Buka URL HTTP(S)") { askVideoUrl() }
        addAction(row, "Pengaturan", "Info launcher") { showSettings() }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(78)))
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
    }

    /** Two live RTSP previews are shown directly on the home screen. */
    private fun addHomeCctvPreview() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
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
        header.addView(manage)
        section.addView(header, LinearLayout.LayoutParams(-1, dp(24)))
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
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.rgb(15, 22, 34))
                    setStroke(dp(1), Color.argb(125, 126, 190, 255))
                }
                isFocusable = true
                isClickable = true
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
            panel.addView(playerView, LinearLayout.LayoutParams(-1, dp(92)))
            if (url.isBlank()) {
                playerView.visibility = View.GONE
                panel.addView(label("Belum diatur · buka Atur Kamera", 10, Color.rgb(220, 205, 154), false)
                    .apply { setPadding(0, dp(10), 0, dp(10)) })
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
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(0, 0, if (index == 1) dp(8) else 0, 0)
            row.addView(panel, lp)
        }
        section.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(section, LinearLayout.LayoutParams(-1, -2))
    }

    private fun releaseHomePreviewPlayers() {
        homePreviewPlayers.forEach { player ->
            try { player.release() } catch (_: Exception) { }
        }
        homePreviewPlayers.clear()
    }

    private fun addAction(row: LinearLayout, title: String, desc: String, click: () -> Unit) {
        val cornerRadius = dp(12).toFloat()

        fun cardBackground(focused: Boolean): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(if (focused) Color.argb(235, 38, 58, 88) else Color.argb(190, 22, 32, 48))
                setStroke(if (focused) dp(2) else dp(1),
                    if (focused) accent else Color.argb(130, 126, 190, 255))
            }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(13), dp(10), dp(10), dp(10))
        card.background = cardBackground(false)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.isClickable = true
        card.contentDescription = "$title. $desc"
        card.setOnClickListener { click() }
        card.setOnFocusChangeListener { view, focused ->
            view.setBackground(cardBackground(focused))
            view.setScaleX(if (focused) 1.04f else 1.0f)
            view.setScaleY(if (focused) 1.04f else 1.0f)
            if (focused) lastFocusedTitle = title
        }
        card.setOnKeyListener { view, keyCode, event ->
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
                        view.performClick()
                        true
                    }
                    else -> false
                }
            }
        }

        card.addView(label(title, 15, Color.WHITE, true))
        card.addView(label(desc, 10, Color.rgb(200, 215, 235), false))

        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        lp.setMargins(0, 0, dp(12), 0)
        row.addView(card, lp)
        actions.add(card)
    }


    /**
     * Shows the most recently observed YouTube/SmartTube media session.
     * Without metadata, the card only offers the opt-in settings path.
     */
    private fun addContinueWatching() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val titleKeys = prefs.all.keys.filter { it.startsWith("watch_title_") }
        val latestPackage = titleKeys.mapNotNull { key ->
            val pkg = key.removePrefix("watch_title_")
            val title = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val duration = prefs.getLong("watch_duration_$pkg", 0L)
            val position = prefs.getLong("watch_position_$pkg", 0L)
            val updated = prefs.getLong("watch_updated_$pkg", 0L)
            if (duration < 180_000L || position < 15_000L || updated <= 0L) null
            else Triple(pkg, title, updated)
        }.maxByOrNull { it.third }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        outer.addView(label("LANJUTKAN MENONTON", 11, Color.rgb(160, 178, 201), true).apply {
            setLetterSpacing(0.08f)
            setPadding(0, 0, 0, dp(7))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = continueCardBackground(false)
            setOnFocusChangeListener { view, focused ->
                view.background = continueCardBackground(focused)
                view.scaleX = if (focused) 1.015f else 1f
                view.scaleY = if (focused) 1.015f else 1f
                if (focused) lastFocusedTitle = "LANJUTKAN MENONTON"
            }
        }
        continueWatchingCard = card

        if (latestPackage == null) {
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(label("Belum ada tontonan terdeteksi", 14, Color.WHITE, true))
            copy.addView(label(
                "Aktifkan akses media untuk mencoba membaca progres YouTube atau SmartTube.",
                11, Color.rgb(180, 198, 220), false
            ).apply { setPadding(0, dp(3), 0, 0) })
            card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(label("AKTIFKAN  ›", 11, accent, true))
            card.setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    toast("Buka Pengaturan TV > Akses notifikasi, lalu aktifkan GlassTV Continue Watching.")
                }
            }
        } else {
            val (pkg, title, _) = latestPackage
            val artworkName = prefs.getString("watch_artwork_$pkg", null)
            val artworkFile = artworkName?.let { File(filesDir, it) }
            val thumbnail = ImageView(this).apply {
                // Real video thumbnails are wide (16:9) and should fill the frame.
                // App icons and square/poster art should keep their proportions instead of being cropped.
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, dp(8), 0, dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.rgb(28, 41, 59))
                }
                clipToOutline = true
                contentDescription = "Gambar tontonan: $title"
                var imageLoaded = false
                if (artworkFile != null && artworkFile.isFile) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(artworkFile.absolutePath)
                        if (bitmap != null) {
                            setImageBitmap(bitmap)
                            val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            if (aspectRatio >= 1.25f) {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setPadding(0, 0, 0, 0)
                            } else {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setPadding(0, dp(8), 0, dp(8))
                            }
                            imageLoaded = true
                        }
                    } catch (_: Exception) { }
                }
                if (!imageLoaded) {
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(pkg))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(0, dp(8), 0, dp(8))
                    } catch (_: Exception) {
                        setBackgroundColor(Color.rgb(28, 41, 59))
                    }
                }
            }
            val thumbParams = LinearLayout.LayoutParams(dp(108), dp(62)).apply {
                marginEnd = dp(14)
            }
            card.addView(thumbnail, thumbParams)

            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val appName = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { if (pkg.contains("smarttube", true) || pkg.contains("teamsmart", true)) "SmartTube" else "YouTube" }
            copy.addView(label(title, 14, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            copy.addView(label(appName, 10, Color.rgb(180, 198, 220), false).apply { setPadding(0, dp(2), 0, dp(4)) })
            val duration = prefs.getLong("watch_duration_$pkg", 0L)
            val storedPosition = prefs.getLong("watch_position_$pkg", 0L)
            val updated = prefs.getLong("watch_updated_$pkg", 0L)
            val speed = prefs.getFloat("watch_speed_$pkg", 0f).coerceIn(0f, 2f)
            val estimatedPosition = (storedPosition + ((System.currentTimeMillis() - updated).coerceAtLeast(0L) * speed).toLong())
                .coerceIn(0L, duration)
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                this.progress = ((estimatedPosition.toDouble() / duration.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                progressTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(51, 66, 86))
                isIndeterminate = false
            }
            copy.addView(progress, LinearLayout.LayoutParams(-1, dp(5)))
            copy.addView(label("${formatWatchTime(estimatedPosition)} / ${formatWatchTime(duration)}", 10, Color.rgb(180, 198, 220), false).apply {
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(label("BUKA APLIKASI  ›", 10, accent, true).apply { setPadding(dp(12), 0, 0, 0) })
            card.setOnClickListener { openContinueWatchingApp(pkg) }
        }

        outer.addView(card, LinearLayout.LayoutParams(-1, dp(88)))
        root.addView(outer, LinearLayout.LayoutParams(-1, -2))
    }

    private fun continueCardBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (focused) Color.argb(235, 38, 58, 88) else Color.argb(150, 22, 32, 48))
        setStroke(if (focused) dp(2) else dp(1),
            if (focused) accent else Color.argb(100, 126, 190, 255))
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

    private fun openApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        if (apps.isEmpty()) {
            toast("Tidak ditemukan aplikasi TV lain.")
            return
        }
        AlertDialog.Builder(this).setTitle("Aplikasi")
            .setItems(labels) { _, which ->
                val info = apps[which].activityInfo
                try {
                    startActivity(Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(info.packageName, info.name))
                } catch (_: Exception) {
                    toast("Aplikasi tidak dapat dibuka. Coba jalankan dari menu sistem TV.")
                }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("GlassTV Launcher — prototipe")
            .setMessage(
                "Versi 0.3.0 (Continue Watching preview)\n\n" +
                "• Target: Android TV 11\n" +
                "• Live TV: membuka aplikasi tuner bawaan Xiaomi/MediaTek\n" +
                "• Entertainment: daftar aplikasi TV yang terpasang dan dapat diluncurkan\n" +
                "• CCTV: monitor 2×2 RTSP internal, OK untuk layar penuh\n" +
                "• Video: membuka URL HTTP(S) melalui pemutar yang kompatibel\n\n" +
                "Tampilan CCTV tersedia di halaman kamera; pratinjau video langsung di dashboard belum diaktifkan. Live TV tetap bergantung pada aplikasi tuner sistem/vendor."
            )
            .setPositiveButton("OK", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showHome()
            return true
        }
        return super.onKeyDown(keyCode, event)
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

package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputInfo
import android.media.tv.TvView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.Spanned
import android.text.SpannableString
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
import org.json.JSONObject

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 22)
    private val accent: Int
        get() = when (getSharedPreferences("glass_tv", MODE_PRIVATE).getString("theme_accent", "blue")) {
            "silver" -> Color.rgb(188, 197, 208)
            "white" -> Color.rgb(242, 245, 249)
            "graphite" -> Color.rgb(142, 153, 166)
            "purple" -> Color.rgb(194, 169, 255)
            "mint" -> Color.rgb(126, 222, 196)
            "amber" -> Color.rgb(255, 198, 112)
            "rose" -> Color.rgb(255, 161, 181)
            else -> Color.rgb(126, 190, 255)
        }

    private fun accentAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))

    private fun focusSurface(alpha: Int = 224): Int {
        val darkR = 29
        val darkG = 39
        val darkB = 53
        val blend = 0.34f
        return Color.argb(alpha,
            (darkR * (1f - blend) + Color.red(accent) * blend).toInt(),
            (darkG * (1f - blend) + Color.green(accent) * blend).toInt(),
            (darkB * (1f - blend) + Color.blue(accent) * blend).toInt())
    }

    private data class PreviewRowView(val key: String, val packageName: String, val view: View)
    private val previewRowViews = mutableListOf<PreviewRowView>()
    private val favoriteDockTiles = mutableListOf<View>()
    private val prayerItemViews = mutableListOf<View>()
    private val prayerNameLabels = mutableListOf<TextView>()
    private val idleHandler = Handler(Looper.getMainLooper())
    private var idleMode = false
    private var homeHeaderView: View? = null
    private var idlePreviewSpacer: View? = null
    private var headerBrandView: View? = null
    private var headerTimeLabel: TextView? = null
    private var headerClockLabel: TextView? = null
    private var headerWeatherLabel: TextView? = null
    private var headerPrayerMetaLabel: TextView? = null
    private var headerHijriLabel: TextView? = null
    private var headerNextPrayerLabel: TextView? = null
    private var headerSpacerView: View? = null
    private var headerRightView: LinearLayout? = null
    private var musicDockParent: LinearLayout? = null
    private var musicPlayButtonView: View? = null
    private var minimalistBottomSpacer: View? = null
    private val musicPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("audio_") == true || key?.startsWith("watch_artwork_") == true) runOnUiThread { refreshMusicDock() }
    }
    private var homeExploreHeaderView: View? = null
    private var homeMediaRowView: View? = null
    private var continueWatchingSectionView: View? = null
    private var cctvPreviewSectionView: View? = null
    private var musicDockView: View? = null
    private var dashboardPanelView: View? = null
    private var weatherPanelView: View? = null
    private var prayerPanelView: View? = null
    private var prayerRowView: View? = null
    private var favoriteDockView: View? = null
    private var backgroundAudioRowView: View? = null
    private var actionMenuView: View? = null
    private var videoWallpaperPlayer: ExoPlayer? = null
    private var videoWallpaperView: PlayerView? = null
    private var liveAmbientWallpaperView: LiveAmbientWallpaperView? = null
    private val wallpaperDownloadLock = Any()
    private val wallpaperDownloadSlots = mutableSetOf<String>()
    private var weatherWallpaperLoading = false

    /**
     * Lightweight native night-sky ambient background. It avoids a continuously decoded
     * video stream and is only attached while compact mode has no Live TV priority.
     */
    private inner class LiveAmbientWallpaperView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val phase = (android.os.SystemClock.uptimeMillis() % 36000L) / 36000.0 * Math.PI * 2.0
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(Color.rgb(3, 8, 20), Color.rgb(9, 23, 42), Color.rgb(12, 20, 39)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // A soft atmospheric glow above the horizon.
            paint.shader = android.graphics.RadialGradient(
                w * 0.48f, h * 0.61f, w * 0.72f,
                intArrayOf(Color.argb(52, 38, 111, 128), Color.argb(18, 26, 70, 103), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Stable star positions with a gentle, low-frequency twinkle.
            for (i in 0 until 26) {
                val x = ((i * 37 + 13) % 97) / 97f * w
                val y = ((i * 53 + 7) % 61) / 61f * h * 0.66f
                val twinkle = (0.45 + 0.55 * kotlin.math.sin(phase * (0.35 + (i % 4) * 0.12) + i)).toFloat()
                    .coerceIn(0.08f, 1f)
                paint.color = Color.argb((65 + twinkle * 150).toInt(), 218, 231, 245)
                canvas.drawCircle(x, y, if (i % 5 == 0) dp(1.7).toFloat() else dp(1).toFloat(), paint)
            }

            // Crescent moon.
            val moonX = w * 0.79f
            val moonY = h * 0.24f
            val moonR = (h * 0.075f).coerceAtLeast(dp(16).toFloat())
            paint.color = Color.rgb(213, 224, 235)
            canvas.drawCircle(moonX, moonY, moonR, paint)
            paint.color = Color.rgb(9, 20, 37)
            canvas.drawCircle(moonX + moonR * 0.38f, moonY - moonR * 0.18f, moonR * 0.91f, paint)

            // Slow aurora ribbons; one simple path and a second softer layer.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (h * 0.045f).coerceAtLeast(dp(12).toFloat())
            paint.shader = LinearGradient(
                0f, h * 0.28f, w, h * 0.66f,
                intArrayOf(Color.argb(0, 45, 130, 152), Color.argb(92, 58, 180, 163),
                    Color.argb(58, 91, 133, 196), Color.argb(0, 45, 130, 152)),
                null, Shader.TileMode.CLAMP
            )
            val drift = kotlin.math.sin(phase).toFloat()
            val aurora = Path().apply {
                moveTo(-w * 0.08f, h * (0.61f + drift * 0.025f))
                cubicTo(w * 0.22f, h * (0.31f - drift * 0.025f),
                    w * 0.55f, h * (0.72f + drift * 0.035f),
                    w * 1.08f, h * (0.43f - drift * 0.02f))
            }
            canvas.drawPath(aurora, paint)
            paint.strokeWidth = (h * 0.022f).coerceAtLeast(dp(6).toFloat())
            paint.alpha = 125
            val aurora2 = Path().apply {
                moveTo(-w * 0.05f, h * 0.69f)
                cubicTo(w * 0.28f, h * (0.48f + drift * 0.02f),
                    w * 0.67f, h * (0.78f - drift * 0.025f),
                    w * 1.05f, h * 0.57f)
            }
            canvas.drawPath(aurora2, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT

            // Keep the lower edge dark so the launcher information remains legible.
            paint.shader = LinearGradient(
                0f, h * 0.68f, 0f, h,
                intArrayOf(Color.TRANSPARENT, Color.argb(195, 2, 6, 14)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.68f, w, h, paint)
            paint.shader = null
            postInvalidateDelayed(120L)
        }
    }
    private val idleTimeout = object : Runnable {
        override fun run() { enterIdleMode() }
    }
    private val wallpaperVideoRequestCode = 7142
    private fun idleTimeoutSeconds(): Int {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        return prefs.getInt("idle_timeout_seconds", 30)
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleTimeout)
        val seconds = idleTimeoutSeconds()
        if (seconds > 0) idleHandler.postDelayed(idleTimeout, seconds * 1000L)
    }

    private fun idleTimeoutLabel(seconds: Int): String = when (seconds) {
        0 -> "MATI"
        15 -> "15 DETIK"
        30 -> "30 DETIK"
        45 -> "45 DETIK"
        60 -> "1 MENIT"
        120 -> "2 MENIT"
        300 -> "5 MENIT"
        else -> "$seconds DETIK"
    }

    private fun showIdleTimeoutPicker() {
        val values = intArrayOf(0, 15, 30, 45, 60, 120, 300)
        val options = arrayOf("Mati", "15 detik", "30 detik", "45 detik", "1 menit", "2 menit", "5 menit")
        val selectedSeconds = idleTimeoutSeconds()
        val checked = values.indexOf(selectedSeconds).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Tampilan ringkas otomatis")
            .setSingleChoiceItems(options, checked) { picker, which ->
                val seconds = values[which]
                getSharedPreferences("glass_tv", MODE_PRIVATE).edit()
                    .putInt("idle_timeout_seconds", seconds)
                    .putBoolean("idle_compact_mode", seconds > 0)
                    .apply()
                if (seconds == 0) {
                    idleHandler.removeCallbacks(idleTimeout)
                    exitIdleMode()
                } else resetIdleTimer()
                picker.dismiss()
                showSettings()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
    private lateinit var root: LinearLayout
    private lateinit var wallpaperTarget: FrameLayout
    private val actions = mutableListOf<View>()
    private var continueWatchingCard: View? = null
    private var continueBackdropArtwork: File? = null
    private var previewBackdropCard: View? = null
    private var liveTvBackgroundView: TvView? = null
    private var liveTvBackgroundScrim: View? = null
    private var liveTvBackgroundInputId: String? = null
    private var liveTvBackgroundChannelUri: Uri? = null
    private var liveTvChannelOverlay: View? = null
    private var liveTvChannelRows: List<Pair<Long, String>> = emptyList()
    private var liveTvChannelSelectedIndex = -1
    private val liveTvChannelHandler = Handler(Looper.getMainLooper())
    private val liveTvChannelCommit = Runnable { commitLiveTvChannelSelection() }
    private val tvListingsPermissionRequestCode = 8301
    private var lastFocusedTitle: String = "Live TV"
    private var refreshOnResume = false
    private val homePreviewPlayers = mutableListOf<ExoPlayer>()
    private val homePreviewPlayerViews = mutableListOf<PlayerView>()
    private val homePreviewStartHandler = Handler(Looper.getMainLooper())
    private val homePreviewStartTasks = mutableListOf<Runnable>()
    private val dashboardHandler = Handler(Looper.getMainLooper())
    private val wallpaperHandler = Handler(Looper.getMainLooper())
    private val wallpaperPhotoSource = "Bing Photo of the Day"
    private val wallpaperCycle = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
            if (prefs.getString("wallpaper_video_uri", null) != null ||
                !prefs.getBoolean("wallpaper_auto", true)) return
            val source = prefs.getString("wallpaper_source", "bing")
            val edit = prefs.edit().putLong("wallpaper_last_refresh", System.currentTimeMillis())
            if (source == "bing") edit.putInt("wallpaper_bing_index", (prefs.getInt("wallpaper_bing_index", 0) + 1).mod(8))
            if (source == "gradient") edit.putInt("wallpaper_index", prefs.getInt("wallpaper_index", 0) + 1)
            edit.apply()
            applyWallpaper(wallpaperTarget, forceRefresh = true)
            val hours = prefs.getInt("wallpaper_interval_hours", 3).coerceIn(1, 24)
            wallpaperHandler.postDelayed(this, hours * 60L * 60L * 1000L)
        }
    }
    private val prayerTimeLabels = mutableListOf<TextView>()
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
    private val headerClockBlink = object : Runnable {
        override fun run() {
            if (!idleMode) return
            val now = Date()
            val zoneId = dashboardSnapshot?.timeZone ?: DashboardServices.selectedCity(this@MainActivity).timeZone
            val zone = java.util.TimeZone.getTimeZone(zoneId)
            val raw = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }.format(now)
            val pieces = raw.split(":")
            val zoneLabel = when (zoneId) {
                "Asia/Jakarta" -> "WIB"
                "Asia/Makassar" -> "WITA"
                "Asia/Jayapura" -> "WIT"
                else -> SimpleDateFormat("z", Locale("id", "ID")).apply { timeZone = zone }.format(now)
            }
            if (pieces.size == 2) {
                headerClockLabel?.text = "${pieces[0]}:${pieces[1]} $zoneLabel"
            }
            dashboardHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val idlePrefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (!idlePrefs.getBoolean("idle_timeout_30_migration", false)) {
            idlePrefs.edit().putInt("idle_timeout_seconds", 30)
                .putBoolean("idle_timeout_30_migration", true).apply()
        }
        if (!idlePrefs.getBoolean("minimalist_home_defaults_v1", false)) {
            idlePrefs.edit()
                .putBoolean("minimalist_show_continue", false)
                .putBoolean("minimalist_show_cctv", false)
                .putBoolean("minimalist_show_explore", false)
                .putBoolean("minimalist_show_favorites", false)
                .putBoolean("minimalist_show_music", false)
                .putString("minimalist_panel_position", "bottom_full")
                .putBoolean("preview_compact_configured", true)
                .putStringSet("preview_compact_keep_keys", emptySet())
                .putBoolean("minimalist_home_defaults_v1", true)
                .apply()
        }

        // v2 defaults: keep the information dock at the bottom and enable tuner wallpaper
        // on first install/update only. Respect an explicit user choice to disable Live TV.
        if (!idlePrefs.getBoolean("minimalist_home_defaults_v2", false)) {
            val migration = idlePrefs.edit()
            val savedPosition = idlePrefs.getString("minimalist_panel_position", null)
            if (savedPosition == null || savedPosition == "top_right") {
                migration.putString("minimalist_panel_position", "bottom_full")
            }
            if (!idlePrefs.contains("live_tv_idle_background")) {
                migration.putBoolean("live_tv_idle_background", true)
            }
            migration.putBoolean("minimalist_home_defaults_v2", true).apply()
        }

        // v3: keep Explore TV available in minimalist mode, but start with external
        // recommendation rows hidden until the user explicitly configures them.
        if (!idlePrefs.getBoolean("minimalist_home_defaults_v3", false)) {
            idlePrefs.edit()
                .putBoolean("minimalist_show_explore", true)
                .putBoolean("minimalist_home_defaults_v3", true)
                .apply()
        }
        UiSoundEffects.initialize(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showHome()
    }

    override fun onPause() {
        refreshOnResume = true
        closeLiveTvChannelOverlay()
        releaseHomePreviewPlayers()
        releaseVideoWallpaperPlayer()
        releaseLiveAmbientWallpaper()
        stopLiveTvBackground()
        wallpaperHandler.removeCallbacks(wallpaperCycle)
        wallpaperHandler.removeCallbacks(dayNightSwitch)
        idleHandler.removeCallbacks(idleTimeout)
        getSharedPreferences("glass_tv", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(musicPrefsListener)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("glass_tv", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(musicPrefsListener)
        if (refreshOnResume) {
            refreshOnResume = false
            showHome()
        } else if (!dashboardLoopStarted) {
            startDashboardUpdates()
            if (::wallpaperTarget.isInitialized) setupVideoWallpaper(wallpaperTarget)
        } else if (::wallpaperTarget.isInitialized) {
            setupVideoWallpaper(wallpaperTarget)
        }
        scheduleWallpaperRotation()
        resetIdleTimer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (liveTvChannelOverlay != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveLiveTvChannelSelection(1)
                        return true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveLiveTvChannelSelection(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        liveTvChannelHandler.removeCallbacks(liveTvChannelCommit)
                        commitLiveTvChannelSelection()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        closeLiveTvChannelOverlay()
                        return true
                    }
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    resetIdleTimer()
                    if (!idleMode) enterIdleMode()
                    showLiveTvChannelOverlay(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    resetIdleTimer()
                    if (!idleMode) enterIdleMode()
                    showLiveTvChannelOverlay(-1)
                    return true
                }
            }
            if (idleMode) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                        resetIdleTimer()
                        return super.dispatchKeyEvent(event)
                    }
                    else -> exitIdleMode()
                }
            }
            resetIdleTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun loadLiveTvChannelRows(): List<Pair<Long, String>> {
        return try {
            data class StoredChannel(
                val id: Long,
                val inputId: String,
                val serviceType: String,
                val number: String,
                val name: String
            )
            val allRows = mutableListOf<StoredChannel>()
            contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID,
                    TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_DISPLAY_NAME),
                null, null, TvContract.Channels.COLUMN_DISPLAY_NUMBER + " ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(TvContract.Channels._ID)
                val inputIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                val typeIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_TYPE)
                val numberIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                val nameIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idIndex < 0 || inputIndex < 0 || cursor.isNull(inputIndex)) continue
                    val type = if (typeIndex >= 0) cursor.getString(typeIndex).orEmpty() else ""
                    // Some TV vendors store broadcast channels with a vendor-specific service type.
                    // Exclude audio-only entries, but don't discard all non-standard A/V rows.
                    if (type == TvContract.Channels.SERVICE_TYPE_AUDIO) continue
                    allRows.add(StoredChannel(
                        cursor.getLong(idIndex),
                        cursor.getString(inputIndex),
                        type,
                        if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else "",
                        if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    ))
                }
            }
            if (allRows.isEmpty()) {
                android.util.Log.w("GlassTVLiveTv", "CHANNEL_QUERY_EMPTY: Android TV returned no stored channel rows")
                return emptyList()
            }

            val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
            val tunerIds = manager?.tvInputList
                ?.filter { it.type == TvInputInfo.TYPE_TUNER }
                ?.map { it.id }
                .orEmpty()
            val grouped = allRows.filter { tunerIds.isEmpty() || it.inputId in tunerIds }
                .groupBy { it.inputId }
            val activeId = liveTvBackgroundInputId
            val chosenInputId = when {
                activeId != null && grouped[activeId].orEmpty().isNotEmpty() -> activeId
                else -> grouped.entries.maxByOrNull { entry ->
                    entry.value.count { it.serviceType == TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO } * 1000 +
                        entry.value.size
                }?.key
            }
            val chosenRows = grouped[chosenInputId].orEmpty()
            if (chosenInputId != null && chosenInputId != liveTvBackgroundInputId) {
                android.util.Log.w("GlassTVLiveTv", "CHANNEL_INPUT_FALLBACK old=$liveTvBackgroundInputId new=$chosenInputId count=${chosenRows.size}")
                liveTvBackgroundInputId = chosenInputId
                val savedId = getSharedPreferences("glass_tv", MODE_PRIVATE).getLong("live_tv_channel_id", -1L)
                val selected = chosenRows.firstOrNull { it.id == savedId }
                    ?: chosenRows.firstOrNull { it.serviceType == TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO }
                    ?: chosenRows.firstOrNull()
                if (selected != null) {
                    val uri = TvContract.buildChannelUri(selected.id)
                    liveTvBackgroundChannelUri = uri
                    try { liveTvBackgroundView?.tune(chosenInputId, uri) }
                    catch (error: Exception) {
                        android.util.Log.e("GlassTVLiveTv", "Fallback tuner retune failed", error)
                    }
                }
            }
            chosenRows
                .sortedWith(compareBy<StoredChannel>({ it.number }, { it.name }))
                .distinctBy { it.id }
                .map { row ->
                    row.id to listOf(row.number, row.name).filter { it.isNotBlank() }
                        .joinToString("  ·  ").ifBlank { "Kanal ${row.id}" }
                }
        } catch (error: Exception) {
            android.util.Log.e("GlassTVLiveTv", "Unable to load stored TV channels", error)
            emptyList()
        }
    }

    private fun promptNativeLiveTvScan() {
        AlertDialog.Builder(this)
            .setTitle("DAFTAR KANAL BELUM TERBACA")
            .setMessage("GlassTV belum berhasil membaca daftar kanal yang tersimpan. Kanal di TV tidak akan dihapus. Buka Live TV bawaan untuk memastikan siaran masih tersedia, lalu kembali ke GlassTV. Tidak perlu melakukan pemindaian ulang jika kanal masih tampil di Live TV.")
            .setPositiveButton("Buka Live TV") { _, _ ->
                val launchIntent = packageManager.getLaunchIntentForPackage("com.mitv.livetv")
                if (launchIntent == null) {
                    toast("Aplikasi Live TV bawaan tidak ditemukan.")
                } else {
                    refreshOnResume = true
                    try {
                        startActivity(launchIntent)
                    } catch (error: Exception) {
                        android.util.Log.e("GlassTVLiveTv", "Unable to open native Live TV", error)
                        toast("Live TV bawaan tidak dapat dibuka.")
                    }
                }
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun showLiveTvChannelOverlay(direction: Int) {
        if (!hasTvListingsPermission()) {
            showTvListingsPermissionDialog()
            return
        }
        if (!::wallpaperTarget.isInitialized || liveTvBackgroundView == null || liveTvBackgroundInputId == null) {
            toast("Live TV belum siap. Tunggu tuner tersambung, lalu tekan CH lagi.")
            return
        }
        liveTvChannelRows = loadLiveTvChannelRows()
        if (liveTvChannelRows.isEmpty()) {
            promptNativeLiveTvScan()
            return
        }
        val currentId = liveTvBackgroundChannelUri?.lastPathSegment?.toLongOrNull()
        val currentIndex = liveTvChannelRows.indexOfFirst { it.first == currentId }
        if (liveTvChannelOverlay == null) {
            liveTvChannelSelectedIndex = currentIndex.coerceAtLeast(0)
            buildLiveTvChannelOverlay()
            android.util.Log.i("GlassTVLiveTv", "CHANNEL_OVERLAY_OPEN count=${liveTvChannelRows.size} current=$currentIndex")
            return
        }
        moveLiveTvChannelSelection(direction)
    }

    private fun buildLiveTvChannelOverlay() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(232, 9, 15, 25))
                setStroke(dp(1), accentAlpha(145))
            }
            elevation = dp(18).toFloat()
        }
        panel.addView(label("LIVE TV  /  PILIH KANAL", 10, accent, true))
        val hint = label("CH pertama membuka daftar · CH berikutnya memilih", 10, Color.rgb(172, 188, 208), false)
        hint.setPadding(0, dp(4), 0, dp(10))
        panel.addView(hint)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rows)
        val footer = label("OK  PILIH SEKARANG     •     DIAM 1,8 DETIK  OTOMATIS", 9,
            Color.rgb(190, 204, 221), false)
        footer.setPadding(0, dp(10), 0, 0)
        panel.addView(footer)
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(36, 0, 0, 0))
            isFocusable = false
            addView(panel, FrameLayout.LayoutParams(dp(390), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(38)
            })
        }
        wrapper.tag = rows
        wallpaperTarget.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        liveTvChannelOverlay = wrapper
        renderLiveTvChannelOverlay()
    }

    private fun renderLiveTvChannelOverlay() {
        val wrapper = liveTvChannelOverlay as? FrameLayout ?: return
        val rows = wrapper.tag as? LinearLayout ?: return
        rows.removeAllViews()
        val first = (liveTvChannelSelectedIndex - 2).coerceAtLeast(0)
        val last = (first + 5).coerceAtMost(liveTvChannelRows.lastIndex)
        for (index in first..last) {
            val (id, name) = liveTvChannelRows[index]
            val selected = index == liveTvChannelSelectedIndex
            val row = TextView(this).apply {
                text = (if (selected) "●  " else "   ") + (name.ifBlank { "Kanal ${index + 1}" })
                textSize = 13f
                setTextColor(if (selected) Color.WHITE else Color.rgb(190, 202, 219))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(if (selected) accentAlpha(70) else Color.TRANSPARENT)
                    if (selected) setStroke(dp(1), accentAlpha(180))
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        }
    }

    private fun moveLiveTvChannelSelection(direction: Int) {
        if (liveTvChannelRows.isEmpty()) return
        liveTvChannelSelectedIndex = (liveTvChannelSelectedIndex + direction + liveTvChannelRows.size) % liveTvChannelRows.size
        renderLiveTvChannelOverlay()
        liveTvChannelHandler.removeCallbacks(liveTvChannelCommit)
        liveTvChannelHandler.postDelayed(liveTvChannelCommit, 1800L)
        resetIdleTimer()
    }

    private fun commitLiveTvChannelSelection() {
        val index = liveTvChannelSelectedIndex
        val channel = liveTvChannelRows.getOrNull(index) ?: return
        val inputId = liveTvBackgroundInputId ?: return
        val uri = TvContract.buildChannelUri(channel.first)
        val tv = liveTvBackgroundView
        if (tv == null) {
            android.util.Log.w("GlassTVLiveTv", "Channel selection failed: TvView is unavailable")
            return
        }
        try {
            tv.tune(inputId, uri)
        } catch (error: Exception) {
            android.util.Log.e("GlassTVLiveTv", "Channel selection tune request failed id=${channel.first}", error)
            return
        }
        liveTvBackgroundChannelUri = uri
        getSharedPreferences("glass_tv", MODE_PRIVATE).edit().putLong("live_tv_channel_id", channel.first).apply()
        android.util.Log.i("GlassTVLiveTv", "CHANNEL_CHANGED number=${channel.second} id=${channel.first}")
        closeLiveTvChannelOverlay()
    }

    private fun closeLiveTvChannelOverlay() {
        liveTvChannelHandler.removeCallbacks(liveTvChannelCommit)
        (liveTvChannelOverlay?.parent as? ViewGroup)?.removeView(liveTvChannelOverlay)
        liveTvChannelOverlay = null
        liveTvChannelRows = emptyList()
        liveTvChannelSelectedIndex = -1
    }

    private fun showHome() {
        releaseHomePreviewPlayers()
        releaseVideoWallpaperPlayer()
        releaseLiveAmbientWallpaper()
        idleMode = false
        idleHandler.removeCallbacks(idleTimeout)
        actions.clear()
        previewRowViews.clear()
        favoriteDockTiles.clear()
        prayerItemViews.clear()
        prayerNameLabels.clear()
        homeHeaderView = null
        continueWatchingSectionView = null
        cctvPreviewSectionView = null
        musicDockView = null
        headerBrandView = null
        headerTimeLabel = null
        headerClockLabel = null
        headerWeatherLabel = null
        headerPrayerMetaLabel = null
        headerNextPrayerLabel = null
        headerSpacerView = null
        headerRightView = null
        musicDockParent = null
        musicPlayButtonView = null
        minimalistBottomSpacer = null
        homeExploreHeaderView = null
        homeMediaRowView = null
        dashboardPanelView = null
        weatherPanelView = null
        prayerPanelView = null
        prayerRowView = null
        favoriteDockView = null
        backgroundAudioRowView = null
        actionMenuView = null
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
        val videoUri = getSharedPreferences("glass_tv", MODE_PRIVATE).getString("wallpaper_video_uri", null)
        if (videoUri != null) {
            wallpaperTarget.setBackgroundColor(Color.rgb(5, 8, 14))
            setupVideoWallpaper(wallpaperTarget)
        }
        homeScroll.addView(root, FrameLayout.LayoutParams(-1, -1))
        wallpaperTarget.addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
        // Keep the launcher identity pinned to the viewport instead of letting it scroll
        // away with the rows when the user navigates vertically with the remote.
        val fixedBrand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isClickable = false
            addView(label("GLASS", 19, Color.WHITE, true))
            addView(label("TV", 19, accent, true))
        }
        wallpaperTarget.addView(fixedBrand, FrameLayout.LayoutParams(-2, dp(30), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(32)
            topMargin = dp(16)
        })
        createLiveTvBackgroundLayer(wallpaperTarget)
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
        headerBrandView = brand
        brand.visibility = View.GONE

        header.addView(brand)
        headerSpacerView = View(this)
        header.addView(headerSpacerView, LinearLayout.LayoutParams(0, 1, 1f))
        val headerRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val selectedTimeZoneId = DashboardServices.selectedCity(this@MainActivity).timeZone
        val dateText = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID")).apply {
            this.timeZone = java.util.TimeZone.getTimeZone(selectedTimeZoneId)
        }.format(Date())
        headerTimeLabel = label(dateText, 10, Color.rgb(185, 199, 219), false).apply {
            gravity = Gravity.END
            maxLines = 1
        }
        headerClockLabel = label("--:-- WIB", 30, Color.WHITE, false).apply {
            gravity = Gravity.END
            maxLines = 1
            includeFontPadding = false
            letterSpacing = -0.035f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        headerWeatherLabel = label("Cuaca sedang dimuat…", 10, Color.rgb(220, 229, 241), false).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerPrayerMetaLabel = label("Pondok Kelapa", 11, Color.rgb(205, 216, 232), true).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerHijriLabel = label("Kalender Hijriyah", 10, Color.rgb(185, 199, 219), false).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerNextPrayerLabel = label("Subuh --:-- · Zuhur --:-- · Asar --:-- · Maghrib --:-- · Isya --:--", 10, accent, true).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerClockLabel?.visibility = View.GONE
        headerWeatherLabel?.visibility = View.GONE
        headerPrayerMetaLabel?.visibility = View.GONE
        headerHijriLabel?.visibility = View.GONE
        headerNextPrayerLabel?.visibility = View.GONE
        headerRight.addView(headerTimeLabel)
        headerRight.addView(headerClockLabel)
        headerRight.addView(headerWeatherLabel)
        headerRight.addView(headerPrayerMetaLabel)
        headerRight.addView(headerHijriLabel)
        headerRight.addView(headerNextPrayerLabel)
        headerRightView = headerRight
        header.addView(headerRight, LinearLayout.LayoutParams(dp(440), -2))
        homeHeaderView = header
        root.addView(header, LinearLayout.LayoutParams(-1, dp(42)))

        addDashboardPanel()

        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        homeMediaRowView = mediaRow
        root.addView(mediaRow, LinearLayout.LayoutParams(-1, dp(158)))
        addContinueWatching(mediaRow)
        addHomeCctvPreview(mediaRow)
        addFavoriteDock(root)
        addBackgroundAudioRow(root)
        idlePreviewSpacer = View(this).apply { visibility = View.GONE }
        root.addView(idlePreviewSpacer, LinearLayout.LayoutParams(-1, dp(150)))
        addPreviewChannelRows(root)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        section.addView(label("JELAJAHI TV", 11, Color.rgb(168, 188, 214), true))
        section.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        section.addView(label("Gunakan tombol arah dan OK", 10, Color.rgb(112, 131, 155), false))
        homeExploreHeaderView = section
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
        actionMenuView = row
        root.addView(row, LinearLayout.LayoutParams(-1, dp(76)))
        applyMinimalistSectionVisibility()
        setContentView(wallpaperTarget)
        root.post {
            if (!isFinishing && hasWindowFocus()) {
                enterIdleMode()
            } else {
                root.postDelayed({ if (!isFinishing && hasWindowFocus()) enterIdleMode() }, 500L)
            }
        }
        dashboardSnapshot = DashboardServices.cached(this)
        renderDashboardSnapshot(dashboardSnapshot, null)
        resetIdleTimer()
        startDashboardUpdates()
        root.postDelayed({ if (!isFinishing) maybePromptTvListingsPermission() }, 650L)
    }

    private fun addDashboardPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(10), dp(7))
            background = dashboardCardBackground()
            clipChildren = false
            clipToPadding = false
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        dashboardGreetingLabel = label("Selamat Pagi", 13, Color.WHITE, true)
        details.addView(dashboardGreetingLabel)
        dashboardDateLabel = label("--", 10, Color.rgb(174, 192, 216), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(dashboardDateLabel)
        dashboardLocationLabel = label("📍 Jakarta Timur", 11, accent, true).apply {
            setPadding(dp(5), dp(3), dp(5), dp(3))
            isFocusable = true
            isClickable = true
            contentDescription = "Lokasi cuaca dan waktu salat"
            setOnClickListener { showLocationPicker() }
            setOnFocusChangeListener { view, focused ->
                (view as TextView).setTextColor(if (focused) Color.WHITE else accent)
                view.background = if (focused) GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(Color.argb(120, 48, 77, 111))
                    setStroke(dp(1), accentAlpha(210))
                } else null
                if (focused) playNavigationSound()
            }
            setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    playSelectSound(); view.performClick(); true
                } else false
            }
        }
        details.addView(dashboardLocationLabel)
        dashboardWeatherLabel = label("Memuat cuaca BMKG…", 11, Color.rgb(232, 237, 246), false).apply {
            setPadding(0, dp(2), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1.0f)
        }
        details.addView(dashboardWeatherLabel)
        left.addView(details, LinearLayout.LayoutParams(0, -1, 1.2f).apply { rightMargin = dp(5) })
        val clockColumn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(1), 0)
            clipChildren = false
            clipToPadding = false
        }
        dashboardClockLabel = label("--:-- WIB", 42, Color.WHITE, false).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            letterSpacing = -0.045f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), Color.argb(115, 0, 0, 0))
        }
        clockColumn.addView(dashboardClockLabel, LinearLayout.LayoutParams(-1, -2))
        left.addView(clockColumn, LinearLayout.LayoutParams(0, -1, 1.5f))
        weatherPanelView = left
        panel.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply {
            rightMargin = dp(6)
        })

        val prayerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(2), dp(12), dp(9))
            background = dashboardCardBackground()
            clipChildren = false
            clipToPadding = false
        }
        val prayerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        dashboardNextPrayerLabel = label("Waktu Sholat · Kalender Hijriyah", 10, Color.rgb(224, 233, 247), true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(6), 0)
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
            tag = "azan_quick_button"
            setOnFocusChangeListener { view, focused ->
                if (focused) playNavigationSound()
                view.elevation = if (focused) dp(7).toFloat() else dp(1).toFloat()
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (focused) focusSurface() else Color.argb(150, 24, 40, 59))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(150))
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
        prayerHeader.translationY = -dp(8).toFloat()
        updateAzanQuickButton()
        val prayerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prayerTimeLabels.clear()
        prayerNameLabels.clear()
        prayerItemViews.clear()
        listOf("Subuh", "Syuruk", "Zuhur", "Asar", "Maghrib", "Isya").forEach { name ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(4), dp(3), dp(4))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.TRANSPARENT) }
            }
            val nameLabel = label(name, 10, Color.rgb(165, 184, 208), false).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }
            prayerNameLabels.add(nameLabel)
            item.addView(nameLabel)
            val timeLabel = label("--:--", 13, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
            prayerTimeLabels.add(timeLabel)
            prayerItemViews.add(item)
            item.addView(timeLabel)
            prayerRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        prayerPanel.addView(prayerRow, LinearLayout.LayoutParams(-1, -2))
        prayerRowView = prayerRow
        prayerPanelView = prayerPanel
        panel.addView(prayerPanel, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(6) })
        dashboardPanelView = panel
        root.addView(panel, LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(7) })
    }

    private fun dashboardCardBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(218, 12, 18, 28), Color.argb(194, 10, 15, 24))
    ).apply {
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), accentAlpha(82))
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
        val zoneId = dashboardSnapshot?.timeZone ?: DashboardServices.selectedCity(this).timeZone
        val zone = java.util.TimeZone.getTimeZone(zoneId)
        dashboardGreetingLabel?.text = currentGreeting()
        dashboardDateLabel?.text = SimpleDateFormat("EEE, dd MMM yyyy", Locale("id", "ID")).apply {
            timeZone = zone
        }.format(now)
        dashboardClockLabel?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = zone
        }.format(now)
        val zoneLabel = when (zoneId) {
            "Asia/Jakarta" -> "WIB"
            "Asia/Makassar" -> "WITA"
            "Asia/Jayapura" -> "WIT"
            else -> SimpleDateFormat("z", Locale("id", "ID")).apply {
                timeZone = zone
            }.format(now)
        }
        val clockValue = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = zone
        }.format(now)
        val clockText = SpannableString("$clockValue $zoneLabel")
        val zoneStart = clockValue.length + 1
        clockText.setSpan(RelativeSizeSpan(0.34f), zoneStart, clockText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        clockText.setSpan(ForegroundColorSpan(accent), zoneStart, clockText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        clockText.setSpan(StyleSpan(Typeface.BOLD), zoneStart, clockText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        dashboardClockLabel?.text = clockText
        headerTimeLabel?.text = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID")).apply {
            timeZone = zone
        }.format(now)
        headerClockLabel?.text = "$clockValue $zoneLabel"
        if (!idleMode) {
            headerTimeLabel?.text = SimpleDateFormat("EEEE, dd MMM  •  HH:mm", Locale("id", "ID")).apply {
                timeZone = zone
            }.format(now)
        }
        updateHeaderPrayerInfo()
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
                headerWeatherLabel?.text = "Cuaca belum tersedia"
                dashboardNextPrayerLabel?.text = "Waktu Sholat ${DashboardServices.selectedCity(this).name} - Kalender Hijriyah"
                updateHeaderPrayerInfo()
            }
        }
    }

    private fun hijriDate(date: Date, timeZoneId: String = "Asia/Jakarta"): String {
        if (android.os.Build.VERSION.SDK_INT < 24) return "Kalender Hijriyah"
        return try {
            val cal = android.icu.util.IslamicCalendar().apply {
                timeZone = android.icu.util.TimeZone.getTimeZone(timeZoneId)
                time = date
            }
            val months = arrayOf("Muharam", "Safar", "Rabiulawal", "Rabiulakhir", "Jumadilawal", "Jumadilakhir",
                "Rajab", "Syakban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah")
            val day = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)
            val month = months[cal.get(android.icu.util.Calendar.MONTH).coerceIn(0, 11)]
            val year = cal.get(android.icu.util.Calendar.YEAR)
            "$day $month $year H"
        } catch (_: Exception) { "Kalender Hijriyah" }
    }

    private fun updateHeaderPrayerInfo() {
        val selected = DashboardServices.selectedCity(this)
        val city = dashboardSnapshot?.city ?: selected.name
        val zone = dashboardSnapshot?.timeZone ?: selected.timeZone
        headerPrayerMetaLabel?.text = city
        headerHijriLabel?.text = hijriDate(Date(), zone)
        val snapshot = dashboardSnapshot
        if (snapshot == null) {
            headerNextPrayerLabel?.text = "JADWAL SALAT  ·  Subuh --:--  ·  Zuhur --:--  ·  Asar --:--  ·  Maghrib --:--  ·  Isya --:--"
            return
        }
        val prayerIndices = listOf(0, 2, 3, 4, 5)
        val nextPrayerName = DashboardServices.nextPrayer(
            snapshot, Calendar.getInstance(java.util.TimeZone.getTimeZone(zone))
        ).first
        val activePrayerIndex = prayerIndices.firstOrNull { index ->
            snapshot.prayerNames.getOrNull(index) == nextPrayerName
        } ?: prayerIndices.first()
        val items = prayerIndices.map { index ->
            "${snapshot.prayerNames.getOrNull(index) ?: "Salat"} ${snapshot.prayerTimes.getOrNull(index) ?: "--:--"}"
        }
        val prefix = "BERIKUTNYA  "
        val body = items.joinToString("   ·   ")
        val prayerText = SpannableString(prefix + body)
        prayerText.setSpan(
            ForegroundColorSpan(accent),
            0, prefix.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        prayerText.setSpan(
            StyleSpan(Typeface.BOLD),
            0, prefix.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        var offset = prefix.length
        prayerIndices.forEachIndexed { position, index ->
            val item = items[position]
            if (index == activePrayerIndex) {
                prayerText.setSpan(
                    BackgroundColorSpan(accentAlpha(92)),
                    offset, offset + item.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                prayerText.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    offset, offset + item.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                prayerText.setSpan(
                    StyleSpan(Typeface.BOLD),
                    offset, offset + item.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            offset += item.length + if (position < prayerIndices.lastIndex) 7 else 0
        }
        headerNextPrayerLabel?.apply {
            text = prayerText
            setPadding(dp(5), dp(3), dp(5), dp(3))
        }
    }

    private fun renderDashboardSnapshot(snapshot: DashboardServices.Snapshot?, error: String?) {
        if (snapshot == null) return
        dashboardLocationLabel?.text = "📍 ${snapshot.city}"
        val shortCondition = snapshot.condition.take(24).trimEnd()
        val weatherIcon = when {
            listOf("petir", "badai", "thunder", "storm").any { snapshot.condition.contains(it, true) } -> "⛈"
            listOf("hujan", "gerimis", "rain", "drizzle").any { snapshot.condition.contains(it, true) } -> "🌧"
            listOf("kabut", "fog", "mist").any { snapshot.condition.contains(it, true) } -> "🌫"
            listOf("mendung", "berawan", "awan", "cloud", "overcast").any { snapshot.condition.contains(it, true) } -> "☁"
            else -> "☀"
        }
        val weatherText = "$weatherIcon  ${snapshot.temperature}  •  ${shortCondition}" + if (error != null) " · tersimpan" else ""
        dashboardWeatherLabel?.text = weatherText
        headerWeatherLabel?.text = weatherText
        snapshot.prayerTimes.forEachIndexed { index, time -> prayerTimeLabels.getOrNull(index)?.text = time }
        val next = DashboardServices.nextPrayer(snapshot)
        val hijri = hijriDate(Date(), snapshot.timeZone)
        dashboardNextPrayerLabel?.text = "Waktu Sholat ${snapshot.city} - $hijri"
        val nextIndex = snapshot.prayerNames.indexOf(next.first).takeIf { it >= 0 } ?: 0
        prayerItemViews.forEachIndexed { index, item ->
            val active = index == nextIndex
            item.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (active) accentAlpha(44) else Color.TRANSPARENT)
                if (active) setStroke(dp(1), accentAlpha(190))
            }
            prayerNameLabels.getOrNull(index)?.setTextColor(if (active) accent else Color.rgb(165, 184, 208))
            prayerTimeLabels.getOrNull(index)?.setTextColor(if (active) Color.WHITE else Color.rgb(225, 232, 242))
        }
        updateHeaderPrayerInfo()
        refreshWeatherWallpaper(snapshot)
    }

    private fun safeDialogWidth(maxWidthDp: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return minOf(dp(maxWidthDp), (screenWidth * 0.82f).toInt())
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
                setStroke(dp(1), accentAlpha(150))
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
                setStroke(dp(1), accentAlpha(180))
            }
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                if (focused) playNavigationSound()
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                view.elevation = if (focused) dp(4).toFloat() else 0f
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (focused) Color.rgb(74, 126, 180) else Color.rgb(35, 57, 83))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(180))
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
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(14))
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
                        setStroke(dp(1), accentAlpha(65))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.elevation = if (focused) dp(4).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(9).toFloat()
                            setColor(if (focused) focusSurface() else Color.argb(120, 30, 43, 62))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(65))
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
        dialog.window?.setLayout(safeDialogWidth(600), -2)
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
        UiSoundEffects.playNavigation(this)
    }

    private fun playSelectSound() {
        UiSoundEffects.playSelect(this)
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
        // Keep the Home preview intentionally compact; the selected grid is used on the full CCTV page.
        val homeCameraCount = 2
        for (index in 1..homeCameraCount) {
            val name = prefs.getString("camera_name_$index", "Kamera $index")
                ?.takeIf { it.isNotBlank() } ?: "Kamera $index"
            val url = prefs.getString("camera_$index", null)?.trim().orEmpty()
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(142, 15, 22, 34))
                    setStroke(dp(1), accentAlpha(105))
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
                            if (focused) accent else accentAlpha(105))
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
            // Use SurfaceView on Android TV for the hardware video path; previews are
            // hidden while the tuner is active so the surfaces do not compete.
            val playerView = PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.rgb(5, 8, 13))
                setBackgroundColor(Color.rgb(5, 8, 13))
            }
            homePreviewPlayerViews.add(playerView)
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
                    val startPreview = Runnable {
                        if (homePreviewPlayers.contains(player) && !isFinishing && !idleMode) {
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                    homePreviewStartTasks.add(startPreview)
                    homePreviewStartHandler.postDelayed(startPreview, (index - 1) * 1000L)
                } catch (_: Exception) {
                    playerView.visibility = View.GONE
                    panel.addView(label("Pratinjau tidak tersedia", 10, Color.rgb(220, 160, 150), false))
                }
            } else {
                playerView.visibility = View.GONE
                panel.addView(label("URL RTSP tidak valid", 10, Color.rgb(220, 160, 150), false))
            }
            val lp = LinearLayout.LayoutParams(0, dp(98), 1f)
            lp.setMargins(0, 0, if (index < homeCameraCount) dp(5) else 0, 0)
            row.addView(panel, lp)
        }
        section.addView(row, LinearLayout.LayoutParams(-1, dp(98)))
        cctvPreviewSectionView = section
        mediaRow.addView(section, LinearLayout.LayoutParams(0, -1, 1.05f))
    }

    private fun releaseHomePreviewPlayers() {
        homePreviewStartTasks.forEach { homePreviewStartHandler.removeCallbacks(it) }
        homePreviewStartTasks.clear()
        homePreviewPlayerViews.clear()
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
                    if (focused) accent else accentAlpha(90)
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
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            val favorite = favoriteDockTiles.firstOrNull()
                            if (favorite != null) {
                                favorite.requestFocus()
                                true
                            } else false
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
            accentDrawable.setColor(accent)
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
                            previewBackdropCard = null
                            playNavigationSound()
                            lastFocusedTitle = "LANJUTKAN MENONTON"
                            applyContinueWatchingBackdrop(artworkFile, view)
                        } else {
                            view.post {
                                if (!view.hasFocus() && continueWatchingCard?.hasFocus() != true &&
                                    previewBackdropCard?.hasFocus() != true && !isFinishing) {
                                    root.setBackgroundColor(Color.TRANSPARENT)
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
                            KeyEvent.KEYCODE_DPAD_UP -> { dashboardLocationLabel?.requestFocus(); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { favoriteDockTiles.firstOrNull()?.requestFocus(); true }
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
                val lp = LinearLayout.LayoutParams(dp(126), dp(108))
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
        cardScroll.addView(cardRow, android.widget.FrameLayout.LayoutParams(-2, dp(112)))
        outer.addView(cardScroll, LinearLayout.LayoutParams(-1, dp(112)))
        continueWatchingSectionView = outer
        mediaRow.addView(outer, LinearLayout.LayoutParams(0, -1, 1.75f))
    }

    private fun refreshMusicDock() {
        val parent = musicDockParent ?: return
        val oldIndex = musicDockView?.let { parent.indexOfChild(it) } ?: parent.childCount
        musicDockView?.let { parent.removeView(it) }
        musicDockView = null
        backgroundAudioRowView = null
        addBackgroundAudioRow(parent)
        musicDockView?.let { dock ->
            val newIndex = parent.indexOfChild(dock)
            if (oldIndex >= 0 && oldIndex < newIndex) {
                parent.removeView(dock)
                parent.addView(dock, oldIndex)
            }
        }
        applyMinimalistSectionVisibility()
    }

    private fun addBackgroundAudioRow(container: LinearLayout) {
        musicDockParent = container
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val candidates = prefs.all.keys.filter { it.startsWith("audio_title_") }.mapNotNull { key ->
            val pkg = key.removePrefix("audio_title_")
            val title = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val updated = prefs.getLong("audio_updated_$pkg", 0L)
            val state = prefs.getInt("audio_state_$pkg", 0)
            if (updated <= 0L || System.currentTimeMillis() - updated > 6 * 60 * 60 * 1000L ||
                state !in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)) null
            else Triple(pkg, title, updated)
        }.sortedByDescending { it.third }
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(3)) }
        section.addView(label("DOCK MUSIK", 10, Color.rgb(150, 171, 199), true), LinearLayout.LayoutParams(-1, dp(18)))
        val item = candidates.firstOrNull()
        if (item == null) {
            val waiting = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(Color.argb(115, 18, 28, 43))
                    setStroke(dp(1), accentAlpha(65))
                }
            }
            waiting.addView(label("♫", 22, accent, true), LinearLayout.LayoutParams(dp(38), dp(44)))
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(label("Belum ada musik diputar", 12, Color.WHITE, true))
            textCol.addView(label("Putar musik di Meld atau aplikasi lain untuk menampilkan kontrol di sini", 10,
                Color.rgb(176, 193, 215), false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            waiting.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            section.addView(waiting, LinearLayout.LayoutParams(-1, dp(60)))
        } else {
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
                    setStroke(dp(1), accentAlpha(100))
                }
                isFocusable = true
                isClickable = true
                contentDescription = "Sedang diputar: $title, $artist"
                setOnFocusChangeListener { view, focused ->
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (focused) focusSurface(230) else Color.argb(155, 18, 28, 43))
                        setStroke(dp(if (focused) 2 else 1), if (focused) accent else accentAlpha(100))
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
                if (artwork != null) try { setImageBitmap(BitmapFactory.decodeFile(artwork.absolutePath)) } catch (_: Exception) { }
                if (drawable == null) try {
                    setImageDrawable(packageManager.getApplicationIcon(pkg))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                } catch (_: Exception) { }
            }
            row.addView(image, LinearLayout.LayoutParams(dp(52), dp(52)))
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            textCol.addView(label("SEDANG DIPUTAR", 9, accent, true))
            textCol.addView(label(title, 13, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            textCol.addView(label(if (album.isBlank()) artist else "$artist  •  $album", 10, Color.rgb(176, 193, 215), false)
                .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val previousButton = mediaControlButton("⏮", "Sebelumnya $title") { sendMediaCommand(pkg, "previous") }
            val playButton = mediaControlButton("▶Ⅱ", "Putar atau jeda $title") {
                sendMediaCommand(pkg, "toggle")
            }.apply { textSize = 17f; contentDescription = "Putar atau jeda $title" }
            val nextButton = mediaControlButton("⏭", "Berikutnya $title") { sendMediaCommand(pkg, "next") }
            previousButton.id = View.generateViewId()
            playButton.id = View.generateViewId()
            nextButton.id = View.generateViewId()
            previousButton.nextFocusRightId = playButton.id
            playButton.nextFocusLeftId = previousButton.id
            playButton.nextFocusRightId = nextButton.id
            nextButton.nextFocusLeftId = playButton.id
            listOf(previousButton, playButton, nextButton).forEach { button ->
                button.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            val target = favoriteDockTiles.lastOrNull() ?: continueWatchingCard
                            if (target != null) { target.requestFocus(); true } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val target = actions.firstOrNull()
                            if (target != null) { target.requestFocus(); true } else false
                        }
                        else -> false
                    }
                }
            }
            controls.addView(previousButton, LinearLayout.LayoutParams(dp(42), dp(38)).apply { rightMargin = dp(8) })
            controls.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(42)).apply { rightMargin = dp(8) })
            controls.addView(nextButton, LinearLayout.LayoutParams(dp(42), dp(38)))
            musicPlayButtonView = playButton
            row.addView(controls)
            row.isFocusable = false
            row.isFocusableInTouchMode = false
            row.isClickable = false
            row.setOnClickListener(null)
            backgroundAudioRowView = row
            section.addView(row, LinearLayout.LayoutParams(-1, dp(72)))
        }
        musicDockView = section
        container.addView(section, LinearLayout.LayoutParams(-1, dp(if (item == null) 86 else 96)))
    }

    private fun minimalistPanelPositionLabel(): String =
        when (getSharedPreferences("glass_tv", MODE_PRIVATE).getString("minimalist_panel_position", "bottom_full")) {
            "bottom_right" -> "Bawah · pojok kanan"
            "bottom_center" -> "Bawah · panel vertikal tengah"
            "bottom_full" -> "Bawah · satu baris melebar"
            else -> "Atas · pojok kanan"
        }

    private fun showMinimalistPanelPositionPicker() {
        val values = arrayOf("top_right", "bottom_right", "bottom_center", "bottom_full")
        val labels = arrayOf("Atas · pojok kanan", "Bawah · pojok kanan", "Bawah · panel vertikal tengah", "Bawah · satu baris melebar")
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val current = values.indexOf(prefs.getString("minimalist_panel_position", "bottom_full")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("POSISI PANEL MINIMALIS")
            .setSingleChoiceItems(labels, current) { picker, which ->
                prefs.edit().putString("minimalist_panel_position", values[which]).apply()
                picker.dismiss()
                if (idleMode) applyMinimalistHeaderPosition()
                showSettings("Tampilan")
            }.setNegativeButton("Batal", null).show()
    }

    private fun applyMinimalistHeaderPosition() {
        if (!idleMode || !::root.isInitialized) return
        val header = homeHeaderView as? LinearLayout ?: return
        val position = getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getString("minimalist_panel_position", "bottom_full") ?: "top_right"
        val centered = position == "bottom_center"
        val fullWidth = position == "bottom_full"
        val atTop = position == "top_right"

        // root is a vertical LinearLayout inside the Home ScrollView. FrameLayout.LayoutParams
        // are ignored there, so use a weighted spacer to anchor bottom positions to the viewport.
        root.removeView(header)
        minimalistBottomSpacer?.let { root.removeView(it) }
        minimalistBottomSpacer = null

        header.orientation = if (centered) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        header.gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
        // The full-width mode is a transparent text strip, not a row of cards.
        header.background = if (centered) GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(168, 10, 17, 29))
            setStroke(dp(1), accentAlpha(100))
        } else null
        header.setPadding(if (centered) dp(20) else 0, if (centered) dp(12) else 0,
            if (centered) dp(20) else 0, if (centered) dp(12) else 0)
        headerSpacerView?.visibility = if (centered || fullWidth) View.GONE else View.VISIBLE
        headerRightView?.let { right ->
            right.orientation = if (fullWidth) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            right.gravity = when {
                centered -> Gravity.CENTER
                fullWidth -> Gravity.CENTER_VERTICAL
                else -> Gravity.END or Gravity.CENTER_VERTICAL
            }
            right.layoutParams = LinearLayout.LayoutParams(
                when { centered -> dp(760); fullWidth -> -1; else -> dp(440) }, -2
            )
            val stripItems = listOf(headerTimeLabel, headerPrayerMetaLabel, headerWeatherLabel,
                headerClockLabel, headerHijriLabel, headerNextPrayerLabel)
            right.removeAllViews()
            stripItems.forEach { text -> if (text != null) right.addView(text) }
            stripItems.forEachIndexed { index, text ->
                text?.gravity = when {
                    centered -> Gravity.CENTER
                    fullWidth -> Gravity.CENTER_VERTICAL
                    else -> Gravity.END
                }
                if (fullWidth && text != null) {
                    text.textSize = when {
                        text === headerClockLabel -> 22f
                        text === headerNextPrayerLabel -> 10f
                        else -> 10f
                    }
                    text.includeFontPadding = false
                    text.maxLines = 1
                    text.setPadding(dp(6), 0, dp(6), 0)
                    text.layoutParams = LinearLayout.LayoutParams(0, -2,
                        floatArrayOf(1.15f, 1.0f, 1.2f, 0.95f, 1.3f, 4.6f)[index])
                } else if (text != null) {
                    text.textSize = when (text) { headerClockLabel -> 30f; headerNextPrayerLabel -> 12f; else -> 10f }
                    text.setPadding(0, 0, 0, 0)
                    text.layoutParams = LinearLayout.LayoutParams(-2, -2)
                }
            }
        }

        val height = when { centered -> dp(132); fullWidth -> dp(66); else -> dp(104) }
        if (atTop) {
            root.addView(header, 0, LinearLayout.LayoutParams(-1, height))
        } else {
            val spacer = View(this).apply { isFocusable = false }
            minimalistBottomSpacer = spacer
            root.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
            // Header spans the row so its internal spacer can align the compact panel to the right.
            root.addView(header, LinearLayout.LayoutParams(-1, height))
        }
        header.requestLayout()
    }

    /**
     * When Continue Watching receives remote focus, use that title's own artwork as
     * a soft, darkened backdrop. A small decoded bitmap is intentionally enlarged to
     * create a blur-like effect that also works on Android 11 (API 30).
     */
    /**
     * Selected artwork becomes the Home background. A smoked gradient keeps dashboard text readable.
     */
    private fun applyContinueWatchingBackdrop(artworkFile: File?, focusOwner: View) {
        if (!focusOwner.hasFocus()) return
        previewBackdropCard = null
        if (artworkFile == null || !artworkFile.isFile || artworkFile.length() <= 0L) {
            root.setBackgroundColor(Color.TRANSPARENT)
            applyWallpaper(wallpaperTarget)
            return
        }
        Thread({
            try {
                val bitmap = BitmapFactory.decodeFile(artworkFile.absolutePath) ?: return@Thread
                runOnUiThread {
                    if (!isFinishing && focusOwner.hasFocus() && continueWatchingCard === focusOwner) {
                        root.setBackgroundColor(Color.TRANSPARENT)
                        wallpaperTarget.background = darkArtworkBackdrop(bitmap)
                    } else bitmap.recycle()
                }
            } catch (_: Exception) { }
        }, "glasstv-watch-backdrop").start()
    }

    private fun applyPreviewProgramBackdrop(source: String?, focusOwner: View) {
        previewBackdropCard = focusOwner
        if (source.isNullOrBlank()) {
            root.setBackgroundColor(Color.TRANSPARENT)
            applyWallpaper(wallpaperTarget)
            return
        }
        Thread({
            var loaded: Bitmap? = null
            try {
                val uri = Uri.parse(source)
                loaded = when (uri.scheme?.lowercase()) {
                    "content", "file", "android.resource" ->
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    "http", "https" -> {
                        val connection = URL(source).openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 4500
                            connection.readTimeout = 6500
                            connection.setRequestProperty("User-Agent", "GlassTVLauncher/0.6")
                            connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        } finally { connection.disconnect() }
                    }
                    else -> null
                }
            } catch (_: Exception) { }
            if (loaded == null) {
                runOnUiThread {
                    if (!isFinishing && focusOwner.hasFocus() && previewBackdropCard === focusOwner) {
                        root.setBackgroundColor(Color.TRANSPARENT)
                        applyWallpaper(wallpaperTarget)
                    }
                }
                return@Thread
            }
            val maxDim = maxOf(loaded.width, loaded.height)
            val bitmap = if (maxDim > 1440) {
                val ratio = 1440f / maxDim
                val resized = Bitmap.createScaledBitmap(
                    loaded, (loaded.width * ratio).toInt().coerceAtLeast(1),
                    (loaded.height * ratio).toInt().coerceAtLeast(1), true
                )
                if (resized !== loaded) loaded.recycle()
                resized
            } else loaded
            runOnUiThread {
                if (!isFinishing && focusOwner.hasFocus() && previewBackdropCard === focusOwner) {
                    root.setBackgroundColor(Color.TRANSPARENT)
                    wallpaperTarget.background = darkArtworkBackdrop(bitmap)
                } else bitmap.recycle()
            }
        }, "glasstv-preview-backdrop").start()
    }

    private fun darkArtworkBackdrop(bitmap: Bitmap): LayerDrawable {
        val image = BitmapDrawable(resources, bitmap).apply { gravity = Gravity.FILL }
        val scrim = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(100, 3, 6, 13),
                Color.argb(143, 3, 5, 11),
                Color.argb(184, 2, 4, 9)
            )
        )
        return LayerDrawable(arrayOf(image, scrim))
    }

    private fun continueCardBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (focused) Color.argb(190, 28, 39, 54) else Color.argb(92, 17, 23, 32))
        setStroke(if (focused) dp(2) else dp(1),
            if (focused) accent else accentAlpha(82))
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
        val launchIntents = mutableListOf<Intent>()
        try { packageManager.getLeanbackLaunchIntentForPackage(packageName)?.let { launchIntents.add(it) } } catch (_: Exception) { }
        try { packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntents.add(it) } } catch (_: Exception) { }
        launchIntents.add(Intent(Intent.ACTION_MAIN).setClassName(packageName, activityName))
        launchIntents.add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(packageName, activityName))
        launchIntents.add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, activityName))
        for (intent in launchIntents.distinctBy { it.component?.flattenToShortString() ?: it.toUri(0) }) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        toast("Aplikasi tidak bisa dibuka. Periksa apakah aplikasi terpasang dan aktif.")
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
                setStroke(dp(1), accentAlpha(180))
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
                    setStroke(dp(1), accentAlpha(65))
                }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { view, focused ->
                    if (focused) playNavigationSound()
                    view.elevation = if (focused) dp(7).toFloat() else 0f
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (focused) focusSurface() else Color.argb(80, 24, 39, 58))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(65))
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
            setPadding(dp(12), dp(10), dp(12), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        content.addView(label("Pilih kartu lalu tekan OK pada remote.", 11, Color.rgb(169, 190, 216), false)
            .apply { setPadding(dp(4), 0, dp(4), dp(10)) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(16))
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(14))
        }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, LinearLayout.LayoutParams(-1,
            minOf(dp(440), (resources.displayMetrics.heightPixels * 0.54f).toInt())))
        val allCards = mutableListOf<Triple<String, android.graphics.drawable.Drawable?, (() -> Unit)>>()
        val appPackagesByName = mutableMapOf<String, String>()
        apps.forEach { app ->
            val info = app.activityInfo
            val appName = app.loadLabel(packageManager).toString()
            appPackagesByName[appName] = info.packageName
            allCards.add(Triple(appName, try { app.loadIcon(packageManager) } catch (_: Exception) { null }, {
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
                        setStroke(dp(1), accentAlpha(75))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.elevation = if (focused) dp(5).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(if (focused) focusSurface() else Color.argb(118, 23, 34, 51))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(75))
                        }
                    }
                    setOnClickListener { playSelectSound(); click() }
                    var appLongPressHandled = false
                    val appPackage = appPackagesByName[name]
                    val appLongPressAction = Runnable {
                        if (appPackage != null) {
                            appLongPressHandled = true
                            showAppActions(appPackage, name)
                        }
                    }
                    if (appPackage != null) {
                        setOnLongClickListener {
                            appLongPressHandled = true
                            showAppActions(appPackage, name)
                            true
                        }
                    }
                    setOnKeyListener { view, keyCode, event ->
                        val okKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        if (!okKey) return@setOnKeyListener false
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (event.repeatCount == 0) {
                                appLongPressHandled = false
                                view.removeCallbacks(appLongPressAction)
                                view.postDelayed(appLongPressAction, 600L)
                            }
                            true
                        } else if (event.action == KeyEvent.ACTION_UP) {
                            view.removeCallbacks(appLongPressAction)
                            if (!appLongPressHandled) view.performClick()
                            true
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
            safeDialogWidth(780),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun showAppActions(packageName: String, appName: String) {
        val options = arrayOf("Buka aplikasi", "Info aplikasi", "Uninstall")
        AlertDialog.Builder(this)
            .setTitle(appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                            ?: packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            try { startActivity(launchIntent) }
                            catch (_: Exception) { toast("Aplikasi tidak dapat dibuka.") }
                        } else toast("Aplikasi ini tidak menyediakan menu untuk dibuka.")
                    }
                    1 -> {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")))
                        } catch (_: Exception) {
                            toast("Halaman Info Aplikasi tidak tersedia pada TV ini.")
                        }
                    }
                    2 -> {
                        try {
                            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                        } catch (_: Exception) {
                            toast("Uninstall tidak diizinkan untuk aplikasi ini. Coba melalui Info Aplikasi.")
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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
            setPadding(dp(12), dp(8), dp(12), dp(10))
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
                        setColor(if (isSelected) focusSurface(190) else Color.argb(115, 25, 36, 53))
                        setStroke(dp(1), if (isSelected) accentAlpha(175) else accentAlpha(55))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.elevation = if (focused) dp(5).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(11).toFloat()
                            setColor(if (focused) focusSurface() else Color.argb(115, 25, 36, 53))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(55))
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
            safeDialogWidth(650),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun accentName(): String = when (getSharedPreferences("glass_tv", MODE_PRIVATE).getString("theme_accent", "blue")) {
        "silver" -> "Abu-abu perak"
        "white" -> "Putih"
        "graphite" -> "Grafit gelap"
        "purple" -> "Ungu lembut"
        "mint" -> "Hijau mint"
        "amber" -> "Amber hangat"
        "rose" -> "Rose"
        else -> "Biru langit"
    }

    private fun showAccentPicker() {
        val options = listOf(
            "blue" to "Biru langit",
            "silver" to "Abu-abu perak",
            "white" to "Putih",
            "graphite" to "Grafit gelap",
            "purple" to "Ungu lembut",
            "mint" to "Hijau mint",
            "amber" to "Amber hangat",
            "rose" to "Rose"
        )
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selectedKey = prefs.getString("theme_accent", "blue")
        val selectedIndex = options.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0)
        val picker = AlertDialog.Builder(this)
            .setTitle("Pilih warna aksen")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selectedIndex) { choiceDialog, which ->
                val (key, label) = options[which]
                prefs.edit().putString("theme_accent", key).apply()
                choiceDialog.dismiss()
                showHome()
                showSettings()
                toast("Warna aksen: $label")
            }
            .setNegativeButton("Batal", null)
            .create()
        picker.show()
        picker.window?.setBackgroundDrawableResource(android.R.color.transparent)
        picker.window?.setLayout(safeDialogWidth(500), -2)
    }

    private fun currentGreeting(): String {
        val zoneId = DashboardServices.selectedCity(this).timeZone
        val hour = Calendar.getInstance(java.util.TimeZone.getTimeZone(zoneId)).get(Calendar.HOUR_OF_DAY)
        val salutation = when {
            hour < 11 -> "Selamat pagi"
            hour < 15 -> "Selamat siang"
            hour < 18 -> "Selamat sore"
            else -> "Selamat malam"
        }
        val name = getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getString("greeting_name", "").orEmpty().trim()
        val titleCaseSalutation = when (salutation) {
            "Selamat pagi" -> "Selamat Pagi"
            "Selamat siang" -> "Selamat Siang"
            "Selamat sore" -> "Selamat Sore"
            else -> "Selamat Malam"
        }
        return if (name.isBlank()) titleCaseSalutation else "$titleCaseSalutation, $name"
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

    private fun showSettings(category: String? = null) {
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
            .setTitle(if (category == null) "GLASSTV  /  PENGATURAN" else "PENGATURAN  /  ${category.uppercase(Locale.ROOT)}")
            .setView(scroll)
            .setNegativeButton(if (category == null) "Tutup" else "Kembali") { _, _ ->
                if (category != null) showSettings()
            }
            .create()
        fun settingCategory(title: String): String = when (title) {
            "Warna aksen", "Sumber wallpaper", "Ganti gambar setiap",
            "Redupkan wallpaper", "Pilih gambar sendiri", "Rotasi wallpaper", "Wallpaper berikutnya",
            "Wallpaper video", "Gunakan wallpaper foto", "Google Ambient / Art Gallery",
            "Prioritas background tanpa Live TV",
            "Tampilan saat tidak digunakan", "Posisi panel Minimalis", "Edit nama sapaan" -> "Tampilan"
            "Lokasi cuaca & waktu salat", "Info GlassTV" -> "Cuaca & Waktu Salat"
            "Azan otomatis", "Uji suara azan" -> "Waktu Salat"
            "Live TV sebagai background", "Pilih kanal Live TV" -> "Live TV"
            "Input TV (HDMI / AV)" -> "Live TV"
            "Tata letak CCTV" -> "Kamera CCTV"
            "Aplikasi Entertainment", "Rekomendasi & riwayat Android TV", "Atur rekomendasi & riwayat",
            "Urutan rekomendasi & riwayat", "Dock aplikasi favorit", "Dock pemutar musik",
            "Minimalis: Lanjutkan Menonton", "Minimalis: CCTV", "Minimalis: Jelajahi TV",
            "Minimalis: Dock aplikasi favorit", "Minimalis: Dock musik" -> "Aplikasi & Hiburan"
            "Gaya suara konsol", "Suara navigasi" -> "Suara"
            "Pengaturan Android / Device Preferences", "Bersihkan cache aplikasi",
            "Kelola aplikasi & hentikan" -> "Sistem & Cadangan"
            else -> "Sistem & Cadangan"
        }
        if (category == null) {
            val categories = listOf(
                Triple("Tampilan", "Wallpaper, warna aksen, mode minimalis, dan layar istirahat", "palette"),
                Triple("Cuaca & Waktu Salat", "Lokasi, kondisi cuaca, jam, dan sumber informasi", "cloud-sun"),
                Triple("Waktu Salat", "Jadwal salat dan pengaturan suara azan", "moon-star"),
                Triple("Live TV", "Background siaran, antena, HDMI, AV, dan kanal", "tv"),
                Triple("Kamera CCTV", "Jumlah kamera dan tata letak preview", "cctv"),
                Triple("Aplikasi & Hiburan", "Aplikasi favorit, rekomendasi, dan Lanjutkan Menonton", "play-square"),
                Triple("Suara", "Efek navigasi dan gaya suara remote", "volume-2"),
                Triple("Sistem & Cadangan", "Pengaturan Android, pemeliharaan, dan informasi", "settings")
            )
            categories.forEach { (name, detail, _) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.argb(145, 25, 36, 53))
                        setStroke(dp(1), accentAlpha(75))
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(if (focused) Color.argb(230, 46, 70, 101) else Color.argb(145, 25, 36, 53))
                            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(75))
                        }
                        view.elevation = if (focused) dp(7).toFloat() else 0f
                        if (focused) playNavigationSound()
                    }
                    setOnClickListener { playSelectSound(); dialog.dismiss(); showSettings(name) }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            view.performClick(); true
                        } else false
                    }
                }
                val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                textColumn.addView(label(name, 14, Color.WHITE, true))
                textColumn.addView(label(detail, 10, Color.rgb(170, 189, 213), false).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label("›", 24, accent, false))
                panel.addView(row, LinearLayout.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(8) })
            }
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(safeDialogWidth(700), (resources.displayMetrics.heightPixels * 0.82f).toInt())
            panel.getChildAt(0)?.requestFocus()
            return
        }
        fun settingRow(title: String, detail: String, state: String? = null, action: () -> Unit) {
            if (settingCategory(title) != category) return
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(135, 25, 36, 53))
                    setStroke(dp(1), accentAlpha(70))
                }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { view, focused ->
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (focused) Color.argb(225, 46, 70, 101) else Color.argb(135, 25, 36, 53))
                        setStroke(dp(if (focused) 2 else 1), if (focused) accent else accentAlpha(70))
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
                        setColor(accentAlpha(80))
                    }
                })
            } else row.addView(label("›", 23, accent, false))
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        }
        fun reopen(message: String) {
            dialog.dismiss()
            toast(message)
            showSettings(category)
        }
        settingRow("Pengaturan Android / Device Preferences", "Buka pengaturan sistem TV: jaringan, timer, daya, gambar, suara, dan lainnya") {
            dialog.dismiss()
            openAndroidSystemSettings()
        }
        settingRow("Lokasi cuaca & waktu salat", "Cari kota di Indonesia atau pilih lokasi tersimpan", DashboardServices.selectedCity(this).name) {
            dialog.dismiss(); showLocationPicker()
        }
        settingRow("Aplikasi Entertainment", "Pilih aplikasi yang muncul di bagian hiburan") {
            dialog.dismiss(); manageEntertainmentApps()
        }
        val listingsAllowed = hasTvListingsPermission()
        settingRow("Rekomendasi & riwayat Android TV",
            "Izinkan Read all TV listings agar rekomendasi dan Watch Next dapat dibaca",
            if (listingsAllowed) "AKTIF" else "IZINKAN") {
            dialog.dismiss()
            showTvListingsPermissionDialog()
        }
        settingRow("Atur rekomendasi & riwayat",
            "Ubah urutan, sembunyikan baris, dan pilih yang tetap tampil pada mode minimalis") {
            dialog.dismiss()
            managePreviewRows()
        }
        settingRow("Urutan rekomendasi & riwayat",
            "Pilih apakah rekomendasi atau riwayat tampil lebih dahulu") {
            dialog.dismiss()
            showPreviewRowOrderPicker()
        }
        settingRow("Dock aplikasi favorit", "Atur ikon aplikasi favorit dan urutannya") {
            dialog.dismiss(); manageFavoriteDock()
        }
        settingRow("Dock pemutar musik", "Kontrol otomatis dari aplikasi musik yang sedang aktif", "OTOMATIS") {
            dialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle("DOCK PEMUTAR MUSIK")
                .setMessage("Dock ini bukan daftar pintasan aplikasi. GlassTV mengambil sesi media yang sedang aktif (judul, artis, artwork, putar/jeda, sebelumnya, berikutnya), termasuk Meld jika aplikasinya menyediakan kontrol MediaSession. Pastikan akses notifikasi GlassTV aktif.")
                .setPositiveButton("Mengerti", null)
                .show()
        }
        fun minimalistToggle(key: String, title: String, detail: String) {
            val enabled = prefs.getBoolean(key, true)
            settingRow(title, detail, if (enabled) "TAMPIL" else "SEMBUNYI") {
                prefs.edit().putBoolean(key, !enabled).apply()
                applyMinimalistSectionVisibility()
                reopen(title + if (!enabled) " ditampilkan pada mode minimalis" else " disembunyikan pada mode minimalis")
            }
        }
        minimalistToggle("minimalist_show_continue", "Minimalis: Lanjutkan Menonton", "Tampilkan atau sembunyikan baris tontonan saat mode minimalis")
        minimalistToggle("minimalist_show_cctv", "Minimalis: CCTV", "Tampilkan atau sembunyikan pratinjau kamera saat mode minimalis")
        minimalistToggle("minimalist_show_explore", "Minimalis: Jelajahi TV", "Tampilkan atau sembunyikan bagian Jelajahi TV dan pintasan aplikasi")
        minimalistToggle("minimalist_show_favorites", "Minimalis: Dock aplikasi favorit", "Tampilkan atau sembunyikan dock aplikasi favorit")
        minimalistToggle("minimalist_show_music", "Minimalis: Dock musik", "Tampilkan atau sembunyikan panel kontrol media yang sedang aktif")
        settingRow("Warna aksen", "Ubah sorotan rekomendasi dan pemutar musik", accentName()) {
            dialog.dismiss(); showAccentPicker()
        }
        settingRow("Tata letak CCTV", "Atur jumlah kamera dan susunan baris/kolom", CctvLayouts.selected(this).label) {
            dialog.dismiss(); showCctvLayoutPicker()
        }
        settingRow("Gaya suara konsol", "Bunyi navigasi bergaya konsol", UiSoundEffects.profileLabel(UiSoundEffects.activeProfile(this))) {
            dialog.dismiss(); showSoundProfilePicker()
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
        settingRow("Bersihkan cache aplikasi", "Bersihkan cache GlassTV dan minta Android trim cache aplikasi lain") {
            dialog.dismiss()
            requestClearAppCaches()
        }
        settingRow("Kelola aplikasi & hentikan", "Pilih aplikasi lalu gunakan Info aplikasi untuk Cache atau Paksa berhenti") {
            dialog.dismiss()
            showAppMaintenanceDialog()
        }
        settingRow("Wallpaper video", "Pilih video lokal sebagai latar bergerak tanpa suara") {
            dialog.dismiss()
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            try { startActivityForResult(pick, wallpaperVideoRequestCode) }
            catch (_: Exception) { toast("Pemilih video tidak tersedia di TV ini.") }
        }
        settingRow("Gunakan wallpaper foto", "Kembali ke wallpaper foto otomatis") {
            getSharedPreferences("glass_tv", MODE_PRIVATE).edit().remove("wallpaper_video_uri").apply()
            releaseVideoWallpaperPlayer()
            applyWallpaper(wallpaperTarget)
            scheduleWallpaperRotation()
            dialog.dismiss()
            toast("Wallpaper foto diaktifkan")
        }
        val liveTvBackgroundEnabled = prefs.getBoolean("live_tv_idle_background", false)
        settingRow("Live TV sebagai background", "Jika aktif, tuner selalu menjadi prioritas tertinggi pada mode ringkas", if (liveTvBackgroundEnabled) "PRIORITAS 1" else "MATI") {
            prefs.edit().putBoolean("live_tv_idle_background", !liveTvBackgroundEnabled).apply()
            if (idleMode) applyIdleBackgroundPriority()
            reopen("Live TV background ${if (!liveTvBackgroundEnabled) "diaktifkan" else "dimatikan"}")
        }
        settingRow("Prioritas background tanpa Live TV", "Dipakai saat mode ringkas jika Live TV dimatikan", idleBackgroundModeLabel()) {
            dialog.dismiss()
            showIdleBackgroundModePicker()
        }
        settingRow("Input TV (HDMI / AV)", "Pilih TV Digital / Antena, HDMI 1, HDMI 2, atau AV", selectedLiveTvInputLabel()) {
            dialog.dismiss()
            showLiveTvInputPicker()
        }
        settingRow("Pilih kanal Live TV", "Pilih siaran yang akan ditampilkan sebagai background", selectedLiveTvChannelLabel()) {
            dialog.dismiss()
            showLiveTvChannelPicker()
        }
        settingRow("Tampilan saat tidak digunakan", "Atur waktu otomatis menjadi ringkas", idleTimeoutLabel(idleTimeoutSeconds())) {
            dialog.dismiss()
            showIdleTimeoutPicker()
        }
        settingRow("Posisi panel Minimalis", "Atur letak tanggal, jam, cuaca, dan waktu salat", minimalistPanelPositionLabel()) {
            dialog.dismiss()
            showMinimalistPanelPositionPicker()
        }
        settingRow("Sumber wallpaper", "Bing, gradient, gambar sendiri, atau jadwal siang/malam", wallpaperSourceLabel()) {
            dialog.dismiss()
            showWallpaperSourcePicker()
        }
        settingRow("Wallpaper siang", "Pilih manual (opsional); jika kosong, foto diunduh otomatis") {
            dialog.dismiss()
            pickDayNightWallpaper(false)
        }
        settingRow("Wallpaper malam", "Pilih manual (opsional); jika kosong, foto malam diunduh otomatis") {
            dialog.dismiss()
            pickDayNightWallpaper(true)
        }
        settingRow("Ganti gambar setiap", "Interval rotasi wallpaper", "${prefs.getInt("wallpaper_interval_hours", 3)} jam") {
            dialog.dismiss()
            showWallpaperIntervalPicker()
        }
        settingRow("Redupkan wallpaper", "Atur kontras agar teks tetap terbaca", wallpaperDimLabel()) {
            dialog.dismiss()
            showWallpaperDimPicker()
        }
        settingRow("Pilih gambar sendiri", "Gunakan foto dari penyimpanan TV") {
            dialog.dismiss()
            pickCustomWallpaper()
        }
        val wallpaperAuto = prefs.getBoolean("wallpaper_auto", true)
        settingRow("Rotasi wallpaper", "Ganti gambar otomatis sesuai interval", if (wallpaperAuto) "AKTIF" else "MATI") {
            prefs.edit().putBoolean("wallpaper_auto", !wallpaperAuto).apply()
            scheduleWallpaperRotation()
            reopen("Rotasi wallpaper ${if (!wallpaperAuto) "diaktifkan" else "dimatikan"}")
        }
        settingRow("Wallpaper berikutnya", "Pindah ke gambar lain dari sumber terpilih") {
            val source = prefs.getString("wallpaper_source", "bing")
            when (source) {
                "art" -> prefs.edit().putInt("wallpaper_art_index", prefs.getInt("wallpaper_art_index", 0) + 1).apply()
                "bing" -> prefs.edit().putInt("wallpaper_bing_index", prefs.getInt("wallpaper_bing_index", 0) + 1).apply()
                "gradient" -> prefs.edit().putInt("wallpaper_index", prefs.getInt("wallpaper_index", 0) + 1).apply()
            }
            applyWallpaper(wallpaperTarget, forceRefresh = true)
            scheduleWallpaperRotation()
            dialog.dismiss()
            toast(if (source == "custom") "Pilih sumber wallpaper online atau gradient untuk mengganti gambar." else "Memuat wallpaper berikutnya")
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
        dialog.window?.setLayout(safeDialogWidth(700), (resources.displayMetrics.heightPixels * 0.82f).toInt())
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
                    setStroke(dp(1), accentAlpha(90))
                }
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                contentDescription = "Favorit ${resolve.loadLabel(packageManager)}"
                setOnClickListener {
                    playSelectSound()
                    launchTvApp(info.packageName, info.name)
                }
                setOnKeyListener { view, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val index = favoriteDockTiles.indexOf(view)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            view.performClick()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index > 0) favoriteDockTiles[index - 1].requestFocus()
                            else return@setOnKeyListener false
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index >= 0 && index < favoriteDockTiles.lastIndex) favoriteDockTiles[index + 1].requestFocus()
                            else return@setOnKeyListener false
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            continueWatchingCard?.requestFocus() ?: return@setOnKeyListener false
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val target = musicPlayButtonView?.takeIf { it.isFocusable }
                                ?: actions.firstOrNull()
                                ?: return@setOnKeyListener false
                            target.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
                setOnFocusChangeListener { view, focused ->
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (focused) focusSurface() else Color.argb(120, 27, 35, 48))
                        setStroke(dp(if (focused) 2 else 1), accentAlpha(if (focused) 220 else 115))
                    }
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.elevation = if (focused) dp(5).toFloat() else 0f
                    if (focused) {
                        lastFocusedTitle = "FAVORIT"
                        playNavigationSound()
                    }
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
            favoriteDockTiles.add(tile)
        }
        val dockScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(dock, ViewGroup.LayoutParams(-2, dp(48)))
        }
        section.addView(dockScroll, LinearLayout.LayoutParams(-1, dp(48)))
        favoriteDockView = section
        container.addView(section, LinearLayout.LayoutParams(-1, dp(68)))
    }


    private fun applyMinimalistSectionVisibility() {
        if (!::root.isInitialized) return
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        fun show(key: String) = !idleMode || prefs.getBoolean(key, true)
        continueWatchingSectionView?.visibility = if (show("minimalist_show_continue")) View.VISIBLE else View.GONE
        cctvPreviewSectionView?.visibility = if (show("minimalist_show_cctv")) View.VISIBLE else View.GONE
        favoriteDockView?.visibility = if (show("minimalist_show_favorites")) View.VISIBLE else View.GONE
        musicDockView?.visibility = if (show("minimalist_show_music")) View.VISIBLE else View.GONE
        backgroundAudioRowView?.visibility = if (show("minimalist_show_music")) View.VISIBLE else View.GONE
        homeExploreHeaderView?.visibility = if (show("minimalist_show_explore")) View.VISIBLE else View.GONE
        actionMenuView?.visibility = if (show("minimalist_show_explore")) View.VISIBLE else View.GONE
        val anyMedia = continueWatchingSectionView?.visibility == View.VISIBLE ||
            cctvPreviewSectionView?.visibility == View.VISIBLE
        homeMediaRowView?.visibility = if (anyMedia) View.VISIBLE else View.GONE
    }

    private fun activeMediaController(packageName: String): MediaController? = try {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(android.content.ComponentName(this, PlaybackListenerService::class.java))
            .firstOrNull { it.packageName == packageName }
    } catch (_: Exception) { null }

    private fun sendMediaCommand(packageName: String, command: String) {
        val controller = activeMediaController(packageName)
        if (controller == null) {
            toast("Kontrol media belum tersedia. Pastikan aplikasinya sedang memutar media.")
            return
        }
        val controls = controller.transportControls
        val actions = controller.playbackState?.actions ?: 0L
        when (command) {
            "toggle" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "next" -> if ((actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) controls.skipToNext()
                else toast("Aplikasi ini tidak menyediakan tombol Berikutnya.")
            "previous" -> if ((actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) controls.skipToPrevious()
                else toast("Aplikasi ini tidak menyediakan tombol Sebelumnya.")
        }
    }

    private fun mediaControlButton(glyph: String, description: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            contentDescription = description
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(7), 0, dp(7), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(150, 48, 67, 91))
                setStroke(dp(1), accentAlpha(110))
            }
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (focused) focusSurface(235) else Color.argb(150, 48, 67, 91))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(110))
                }
                if (focused) playNavigationSound()
            }
            setOnClickListener { playSelectSound(); onClick() }
            setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                     keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    view.performClick(); true
                } else false
            }
        }

    private fun addMusicDock(container: LinearLayout) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selected = prefs.getStringSet("music_dock_packages", emptySet())?.toSet() ?: emptySet()
        if (selected.isEmpty()) return
        val apps = queryTvApps().filter { it.activityInfo.packageName in selected }
        if (apps.isEmpty()) return
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(3))
        }
        section.addView(label("MUSIK", 10, Color.rgb(150, 171, 199), true))
        val dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        apps.forEach { resolve ->
            val info = resolve.activityInfo
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(12), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(120, 27, 39, 57))
                    setStroke(dp(1), accentAlpha(90))
                }
                isFocusable = true
                isClickable = true
                contentDescription = "Pemutar musik " + resolve.loadLabel(packageManager)
                setOnClickListener { playSelectSound(); launchTvApp(info.packageName, info.name) }
                setOnFocusChangeListener { view, focused ->
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (focused) focusSurface() else Color.argb(120, 27, 39, 57))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else accentAlpha(90))
                    }
                    if (focused) playNavigationSound()
                }
            }
            val icon = ImageView(this).apply {
                try { setImageDrawable(resolve.loadIcon(packageManager)) } catch (_: Exception) { }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            tile.addView(label(resolve.loadLabel(packageManager).toString(), 11, Color.WHITE, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(7), 0, 0, 0)
            })
            dock.addView(tile, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(dock, ViewGroup.LayoutParams(-2, dp(48)))
        }
        section.addView(scroll, LinearLayout.LayoutParams(-1, dp(48)))
        musicDockView = section
        container.addView(section, LinearLayout.LayoutParams(-1, dp(66)))
    }

    private fun manageMusicDock() {
        val apps = queryTvApps()
        if (apps.isEmpty()) { toast("Tidak ditemukan aplikasi TV lain."); return }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selected = prefs.getStringSet("music_dock_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val packages = apps.map { it.activityInfo.packageName }
        val checked = BooleanArray(apps.size) { packages[it] in selected }
        AlertDialog.Builder(this)
            .setTitle("PILIH APLIKASI DOCK MUSIK")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected.add(packages[which]) else selected.remove(packages[which])
            }
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putStringSet("music_dock_packages", selected.toSet()).apply()
                showHome()
                showSettings("Aplikasi & Hiburan")
                toast("Dock musik disimpan")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun previewRowKey(channel: TvPreviewChannels.Channel): String =
        "row_" + Integer.toHexString((channel.packageName + "|" + channel.title).hashCode())

    private fun isSmartTubePreview(packageName: String): Boolean {
        val value = packageName.lowercase(Locale.ROOT)
        return value.contains("smarttube") || value.contains("smartyoutubetv2") || value.contains("teamsmart")
    }

    private fun previewHiddenKeys(): Set<String> =
        getSharedPreferences("glass_tv", MODE_PRIVATE)
            .getStringSet("preview_hidden_keys", emptySet())?.toSet() ?: emptySet()

    private fun previewCompactKeepKeys(): Set<String> {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (!prefs.getBoolean("preview_compact_configured", false)) return emptySet()
        return prefs.getStringSet("preview_compact_keep_keys", emptySet())?.toSet() ?: emptySet()
    }

    private fun previewRowShouldShow(key: String, packageName: String): Boolean {
        if (key in previewHiddenKeys()) return false
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (!idleMode) {
            // Recommendations from YouTube and other TV apps are opt-in on the Home screen.
            // Existing users who already saved row visibility keep that choice.
            val homeRowsConfigured = prefs.getBoolean(
                "preview_home_rows_configured",
                prefs.contains("preview_hidden_keys")
            )
            return homeRowsConfigured
        }
        return if (prefs.getBoolean("preview_compact_configured", false)) {
            key in previewCompactKeepKeys()
        } else {
            isSmartTubePreview(packageName)
        }
    }

    private fun applyPreviewRowVisibility() {
        previewRowViews.forEach { row ->
            row.view.visibility = if (previewRowShouldShow(row.key, row.packageName)) View.VISIBLE else View.GONE
        }
        val hasVisibleCompactRows = idleMode && previewRowViews.any { it.view.visibility == View.VISIBLE }
        idlePreviewSpacer?.visibility = if (hasVisibleCompactRows) View.VISIBLE else View.GONE
    }

    private fun addPreviewChannelRows(container: LinearLayout) {
        val channels = TvPreviewChannels.read(this)
        if (channels.isEmpty()) return
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val savedOrder = prefs.getString("preview_channel_order", "").orEmpty()
            .split(",").filter { it.isNotBlank() }
        val historyFirst = prefs.getString("preview_row_order", "recommendations_first") == "history_first"
        val orderedChannels = channels.withIndex().sortedBy { indexed ->
            val key = previewRowKey(indexed.value)
            val savedPosition = savedOrder.indexOf(key)
            if (savedOrder.isNotEmpty()) {
                if (savedPosition >= 0) savedPosition else savedOrder.size + indexed.index
            } else {
                val history = indexed.value.category == "history"
                (if (historyFirst == history) 0 else 1000) + indexed.index
            }
        }.map { it.value }
        orderedChannels.forEach { channel ->
            val rowKey = previewRowKey(channel)
            if (rowKey in previewHiddenKeys()) return@forEach
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5), 0, dp(6))
                clipChildren = false
                clipToPadding = false
            }
            section.addView(label(channel.title.uppercase(Locale.getDefault()), 10, Color.rgb(210, 218, 228), true))
            val horizontal = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
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
                        setStroke(dp(1), accentAlpha(78))
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    contentDescription = program.title
                    setOnClickListener {
                        playSelectSound()
                        val intent = TvPreviewChannels.launchIntent(this@MainActivity, channel, program)
                        if (intent != null) {
                            try { startActivity(intent) } catch (_: Exception) { toast("Video tidak dapat dibuka.") }
                        } else toast("Aplikasi pemilik rekomendasi tidak dapat dibuka.")
                    }
                    setOnFocusChangeListener { view, focused ->
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(if (focused) focusSurface(205) else Color.argb(118, 22, 32, 48))
                            setStroke(dp(if (focused) 2 else 1), accentAlpha(if (focused) 210 else 78))
                        }
                        if (focused) {
                            playNavigationSound()
                            previewBackdropCard = view
                            applyPreviewProgramBackdrop(program.artwork, view)
                        } else {
                            view.post {
                                if (previewBackdropCard === view && !view.hasFocus()) {
                                    previewBackdropCard = null
                                    if (continueWatchingCard?.hasFocus() != true && !isFinishing) {
                                        root.setBackgroundColor(Color.TRANSPARENT)
                                        applyWallpaper(wallpaperTarget)
                                    }
                                }
                            }
                        }
                    }
                    setOnKeyListener { view, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            view.performClick(); true
                        } else false
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
            section.visibility = if (previewRowShouldShow(rowKey, channel.packageName)) View.VISIBLE else View.GONE
            previewRowViews.add(PreviewRowView(rowKey, channel.packageName, section))
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
        var focusPackage = ordered.firstOrNull() ?: apps.firstOrNull()?.activityInfo?.packageName
        val rowViews = mutableMapOf<String, View>()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(16))
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
            rowViews.clear()
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
                        setColor(if (order >= 0) focusSurface(190) else Color.argb(115, 25, 36, 53))
                        setStroke(dp(1), if (order >= 0) accentAlpha(175) else accentAlpha(55))
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setOnFocusChangeListener { view, focused ->
                        if (focused) playNavigationSound()
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.elevation = if (focused) dp(5).toFloat() else 0f
                        view.background = GradientDrawable().apply {
                            cornerRadius = dp(11).toFloat()
                            setColor(
                                if (focused) focusSurface()
                                else if (pkg in ordered) focusSurface(190)
                                else Color.argb(115, 25, 36, 53)
                            )
                            setStroke(
                                dp(if (focused) 3 else 1),
                                if (focused) Color.rgb(235, 246, 255)
                                else if (pkg in ordered) accentAlpha(210)
                                else accentAlpha(70)
                            )
                        }
                    }
                    setOnClickListener {
                        playSelectSound()
                        if (order >= 0) ordered.remove(pkg) else ordered.add(pkg)
                        focusPackage = pkg
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
                rowViews[pkg] = row
            }
            list.post {
                val target = focusPackage?.let { rowViews[it] } ?: rowViews.values.firstOrNull()
                if (dialog.isShowing && target != null) {
                    scroll.smoothScrollTo(0, target.top)
                    target.requestFocus()
                }
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
        dialog.window?.setLayout(safeDialogWidth(650), (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    private fun showCctvLayoutPicker() {
        val options = CctvLayouts.options
        val selected = CctvLayouts.selected(this)
        val checked = options.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Tata letak CCTV")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checked) { picker, which ->
                val option = options[which]
                CctvLayouts.save(this, option)
                picker.dismiss()
                showHome()
                toast("Tata letak CCTV: ${option.label}")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSoundProfilePicker() {
        val profiles = listOf("ps5", "switch", "xbox", "off")
        val labels = arrayOf("PlayStation 5 — lembut dan futuristis", "Nintendo Switch — klik lebih tajam",
            "Xbox — nada lebih hangat", "Mati — tanpa suara")
        val current = profiles.indexOf(UiSoundEffects.activeProfile(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Gaya suara konsol")
            .setSingleChoiceItems(labels, current) { picker, which ->
                val profile = profiles[which]
                UiSoundEffects.setProfile(this, profile)
                picker.dismiss()
                if (profile != "off") UiSoundEffects.playSelect(this)
                showSettings()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun managePreviewRows() {
        if (!hasTvListingsPermission()) {
            showTvListingsPermissionDialog()
            return
        }
        val channels = TvPreviewChannels.read(this)
        if (channels.isEmpty()) {
            toast("Belum ada kanal rekomendasi atau Watch Next yang diterbitkan aplikasi TV.")
            return
        }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val savedOrder = prefs.getString("preview_channel_order", "").orEmpty()
            .split(",").filter { it.isNotBlank() }
        val ordered = channels.withIndex().sortedBy { indexed ->
            val position = savedOrder.indexOf(previewRowKey(indexed.value))
            if (position >= 0) position else savedOrder.size + indexed.index
        }.map { it.value }.toMutableList()
        val hidden = prefs.getStringSet("preview_hidden_keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        val keepInCompact = if (prefs.getBoolean("preview_compact_configured", false)) {
            prefs.getStringSet("preview_compact_keep_keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        } else {
            channels.filter { isSmartTubePreview(it.packageName) }
                .map { previewRowKey(it) }.toMutableSet()
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        val editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(13, 20, 31))
                setStroke(dp(1), accentAlpha(85))
            }
        }
        editor.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun footerButton(title: String, prominent: Boolean, action: () -> Unit): TextView {
            return TextView(this).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(dp(8), dp(11), dp(8), dp(11))
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(if (prominent) accentAlpha(185) else Color.argb(170, 35, 47, 64))
                    setStroke(dp(1), if (prominent) accentAlpha(235) else accentAlpha(90))
                }
                setOnFocusChangeListener { v, focused ->
                    v.elevation = if (focused) dp(5).toFloat() else 0f
                    v.background = GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setColor(if (focused) focusSurface(245) else if (prominent) accentAlpha(185) else Color.argb(170, 35, 47, 64))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else if (prominent) accentAlpha(235) else accentAlpha(90))
                    }
                    if (focused) playNavigationSound()
                }
                setOnClickListener { playSelectSound(); action() }
                setOnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        v.performClick()
                        true
                    } else false
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ATUR REKOMENDASI & RIWAYAT")
            .setMessage("Atur urutan baris, tampil/sembunyi di Home, dan tampilan mode minimalis. Perubahan hanya berlaku setelah menekan SIMPAN.")
            .setView(editor)
            .create()
        val saveButton = footerButton("SIMPAN", true) {
            prefs.edit()
                .putString("preview_channel_order", ordered.joinToString(",") { previewRowKey(it) })
                .putStringSet("preview_hidden_keys", hidden.toSet())
                .putStringSet("preview_compact_keep_keys", keepInCompact.toSet())
                .putBoolean("preview_home_rows_configured", true)
                .putBoolean("preview_compact_configured", true)
                .apply()
            dialog.dismiss()
            showHome()
            showSettings()
            toast("Susunan rekomendasi dan riwayat disimpan")
        }
        val cancelButton = footerButton("BATAL", false) { dialog.dismiss() }
        var refreshEditor: (() -> Unit)? = null
        val resetButton = footerButton("RESET", false) {
            hidden.clear()
            keepInCompact.clear()
            channels.filter { isSmartTubePreview(it.packageName) }
                .forEach { keepInCompact.add(previewRowKey(it)) }
            ordered.clear()
            ordered.addAll(channels)
            refreshEditor?.invoke()
            toast("Pengaturan sementara dikembalikan ke awal")
        }
        footer.addView(saveButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        footer.addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        footer.addView(resetButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        editor.addView(footer, LinearLayout.LayoutParams(-1, dp(52)))

        fun renderRows() {
            list.removeAllViews()
            list.addView(label("Pilih fungsi dengan kiri/kanan, OK untuk mengubah. Gunakan ▲ ▼ untuk mengurutkan.", 11,
                Color.rgb(170, 189, 213), false).apply { setPadding(dp(3), 0, dp(3), dp(9)) })
            ordered.forEachIndexed { index, channel ->
                val key = previewRowKey(channel)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(Color.argb(125, 20, 30, 46))
                        setStroke(dp(1), accentAlpha(68))
                    }
                }
                card.addView(label("${index + 1}. ${channel.title}", 13, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                card.addView(label(channel.packageName.ifBlank { "Sumber: Android TV Watch Next" }, 9,
                    Color.rgb(155, 178, 205), false).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, dp(6))
                })
                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                fun chip(text: String, active: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
                    val view = TextView(this).apply {
                        this.text = text
                        textSize = 9.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(dp(7), dp(7), dp(7), dp(7))
                        setTextColor(if (active) Color.WHITE else Color.rgb(188, 203, 222))
                        alpha = if (enabled) 1f else 0.35f
                        isFocusable = enabled
                        isClickable = enabled
                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(if (active) accentAlpha(140) else Color.argb(110, 34, 47, 66))
                            setStroke(dp(1), if (active) accentAlpha(210) else accentAlpha(70))
                        }
                        setOnFocusChangeListener { v, focused ->
                            v.elevation = if (focused) dp(5).toFloat() else 0f
                            v.background = GradientDrawable().apply {
                                cornerRadius = dp(8).toFloat()
                                setColor(if (focused) focusSurface(235) else if (active) accentAlpha(140) else Color.argb(110, 34, 47, 66))
                                setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else if (active) accentAlpha(210) else accentAlpha(70))
                            }
                            if (focused) playNavigationSound()
                        }
                        setOnClickListener { playSelectSound(); action() }
                        setOnKeyListener { v, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN &&
                                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                                v.performClick(); true
                            } else false
                        }
                    }
                    actionsRow.addView(view, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                        rightMargin = dp(4)
                    })
                }
                chip(if (key in hidden) "SEMBUNYI" else "HOME: TAMPIL", active = key !in hidden) {
                    if (key in hidden) hidden.remove(key) else hidden.add(key)
                    renderRows()
                }
                chip(if (key in keepInCompact) "MINIMALIS: TAMPIL" else "MINIMALIS: SEMBUNYI",
                    active = key in keepInCompact) {
                    if (key in keepInCompact) keepInCompact.remove(key) else keepInCompact.add(key)
                    renderRows()
                }
                chip("▲", enabled = index > 0) {
                    java.util.Collections.swap(ordered, index, index - 1)
                    renderRows()
                }
                chip("▼", enabled = index < ordered.lastIndex) {
                    java.util.Collections.swap(ordered, index, index + 1)
                    renderRows()
                }
                card.addView(actionsRow)
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
        refreshEditor = { renderRows() }
        renderRows()
        dialog.show()
        dialog.window?.setLayout(safeDialogWidth(780),
            (resources.displayMetrics.heightPixels * 0.84f).toInt())
        saveButton.post { saveButton.requestFocus() }
    }

    private fun showPreviewRowOrderPicker() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selected = prefs.getString("preview_row_order", "recommendations_first")
        val options = arrayOf("Rekomendasi lebih dahulu", "Riwayat & Lanjutkan Menonton lebih dahulu")
        AlertDialog.Builder(this)
            .setTitle("Urutan baris Home")
            .setSingleChoiceItems(options, if (selected == "history_first") 1 else 0) { picker, which ->
                val value = if (which == 1) "history_first" else "recommendations_first"
                // A global order choice must clear a previous per-row custom order,
                // otherwise preview_channel_order would silently override this setting.
                prefs.edit()
                    .putString("preview_row_order", value)
                    .remove("preview_channel_order")
                    .apply()
                picker.dismiss()
                showHome()
                showSettings()
                toast(if (value == "history_first")
                    "Riwayat ditampilkan lebih dahulu; urutan manual direset"
                else
                    "Rekomendasi ditampilkan lebih dahulu; urutan manual direset")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun hasTvListingsPermission(): Boolean =
        checkSelfPermission("android.permission.READ_TV_LISTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun maybePromptTvListingsPermission() {
        if (hasTvListingsPermission()) return
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (prefs.getBoolean("tv_listings_permission_prompt_shown", false)) return
        prefs.edit().putBoolean("tv_listings_permission_prompt_shown", true).apply()
        showTvListingsPermissionDialog()
    }

    private fun showTvListingsPermissionDialog() {
        if (hasTvListingsPermission()) {
            toast("Izin rekomendasi & riwayat Android TV sudah aktif.")
            showHome()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Aktifkan rekomendasi & riwayat")
            .setMessage("GlassTV membutuhkan izin Read all TV listings untuk membaca rekomendasi dan Watch Next yang diterbitkan aplikasi TV. Izin ini tidak menghapus data tontonan.")
            .setPositiveButton("Minta izin") { _, _ ->
                requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), tvListingsPermissionRequestCode)
            }
            .setNeutralButton("Buka info aplikasi") { _, _ -> openApplicationDetails(packageName) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != tvListingsPermissionRequestCode) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Izin TV listings aktif. Rekomendasi dan riwayat sedang dimuat.")
        } else {
            toast("Izin TV listings belum aktif. Aktifkan dari Pengaturan GlassTV.")
        }
        showHome()
    }

    private fun requestClearAppCaches() {
        AlertDialog.Builder(this)
            .setTitle("Bersihkan cache aplikasi")
            .setMessage("GlassTV akan membersihkan cache miliknya sekarang. Untuk cache aplikasi lain, Android TV perlu mengizinkan akses debugging satu kali. Perintah hanya pm trim-caches, tidak menghapus login atau data aplikasi. Jika debugging tidak tersedia, hanya cache GlassTV yang dibersihkan.")
            .setPositiveButton("Bersihkan sekarang") { _, _ ->
                CacheCleaner.cleanNow(this) { bytesFreed, beforePercent, afterPercent, systemTrimmed ->
                    val mb = (bytesFreed / (1024L * 1024L)).coerceAtLeast(0L)
                    val message = if (systemTrimmed) {
                        "Cache TV dibersihkan. Ruang pulih sekitar " + mb + " MB; pemakaian " +
                            beforePercent + "% → " + afterPercent + "%."
                    } else {
                        "Cache GlassTV dibersihkan (" + mb + " MB). Cache aplikasi lain belum di-trim; periksa izin Network/USB debugging."
                    }
                    toast(message)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAppMaintenanceDialog() {
        val apps = queryTvApps().distinctBy { it.activityInfo.packageName }
        if (apps.isEmpty()) {
            toast("Tidak ada aplikasi TV yang bisa dikelola.")
            return
        }
        val entries = apps.map { resolve ->
            "${resolve.loadLabel(packageManager)}  ·  ${resolve.activityInfo.packageName}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi")
            .setMessage("Buka halaman Info aplikasi Android untuk melihat cache atau menekan Paksa berhenti. Android tidak mengizinkan launcher biasa menghentikan paksa aplikasi lain secara diam-diam.")
            .setItems(entries) { _, which -> openApplicationDetails(apps[which].activityInfo.packageName) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun openApplicationDetails(appPackage: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$appPackage")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
            catch (_: Exception) { toast("Pengaturan aplikasi tidak tersedia pada TV ini.") }
        }
    }

    private fun openAndroidSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            try {
                val launch = packageManager.getLaunchIntentForPackage("com.android.tv.settings")
                if (launch != null) startActivity(launch)
                else toast("Pengaturan Android TV tidak dapat dibuka pada perangkat ini.")
            } catch (_: Exception) {
                toast("Pengaturan Android TV tidak dapat dibuka pada perangkat ini.")
            }
        }
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
        idleHandler.removeCallbacksAndMessages(null)
        releaseVideoWallpaperPlayer()
        try { toneGenerator?.release() } catch (_: Exception) { }
        toneGenerator = null
        UiSoundEffects.release()
        try { azanPlayer?.release() } catch (_: Exception) { }
        azanPlayer = null
        super.onDestroy()
    }

    private fun selectedLiveTvInputLabel(): String {
        val selected = getSharedPreferences("glass_tv", MODE_PRIVATE).getString("live_tv_input_id", "auto")
        if (selected.isNullOrBlank() || selected == "auto") return "Otomatis"
        val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager ?: return "Input tersimpan"
        val info = manager.tvInputList.firstOrNull { it.id == selected } ?: return "Input tidak tersedia"
        return try { info.loadLabel(this).toString() } catch (_: Exception) { info.id }
    }

    private fun selectedLiveTvChannelLabel(): String {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val id = prefs.getLong("live_tv_channel_id", -1L)
        if (id < 0L) return "Otomatis / kanal pertama"
        return try {
            contentResolver.query(
                TvContract.buildChannelUri(id),
                arrayOf(TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_DISPLAY_NUMBER),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    listOf(number, name).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Kanal tersimpan" }
                } else "Otomatis / kanal pertama"
            } ?: "Otomatis / kanal pertama"
        } catch (_: Exception) { "Kanal tersimpan" }
    }

    private fun showLiveTvChannelPicker() {
        if (!hasTvListingsPermission()) {
            showTvListingsPermissionDialog()
            return
        }
        val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
        if (manager == null) {
            toast("Layanan input TV tidak tersedia.")
            return
        }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selectedInput = prefs.getString("live_tv_input_id", "auto") ?: "auto"
        val selectedChannelId = prefs.getLong("live_tv_channel_id", -1L)

        data class ChannelRow(
            val id: Long,
            val inputId: String,
            val number: String,
            val name: String,
            val serviceType: String
        )
        val allChannels = try {
            contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_INPUT_ID,
                    TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_SERVICE_TYPE
                ),
                null, null, TvContract.Channels.COLUMN_DISPLAY_NUMBER + " ASC"
            )?.use { cursor ->
                val rows = mutableListOf<ChannelRow>()
                val idIndex = cursor.getColumnIndex(TvContract.Channels._ID)
                val inputIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                val nameIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                val typeIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_TYPE)
                while (cursor.moveToNext()) {
                    if (idIndex < 0 || inputIndex < 0 || cursor.isNull(inputIndex)) continue
                    val service = if (typeIndex >= 0) cursor.getString(typeIndex).orEmpty() else ""
                    if (service != TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO) continue
                    rows.add(
                        ChannelRow(
                            cursor.getLong(idIndex),
                            cursor.getString(inputIndex),
                            if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else "",
                            if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                            service
                        )
                    )
                }
                rows
            }.orEmpty()
        } catch (_: Exception) { emptyList() }

        val tunerInfos = manager.tvInputList.filter { it.type == TvInputInfo.TYPE_TUNER }
        val activeInputId = when {
            selectedInput != "auto" -> {
                val info = manager.tvInputList.firstOrNull { it.id == selectedInput }
                if (info?.isPassthroughInput == true) {
                    toast("HDMI / AV tidak memiliki daftar kanal tuner.")
                    return
                }
                info?.id
            }
            else -> {
                val savedOwner = allChannels.firstOrNull { it.id == selectedChannelId }?.inputId
                val ordered = tunerInfos.sortedBy { info ->
                    val pkg = info.serviceInfo.packageName.lowercase(Locale.ROOT)
                    when {
                        pkg.contains("tuner") -> 0
                        pkg.contains("dtv") -> 1
                        pkg.contains("mitv") || pkg.contains("livetv") -> 2
                        else -> 3
                    }
                }
                ordered.firstOrNull { it.id == savedOwner }?.id
                    ?: ordered.firstOrNull { info ->
                        allChannels.any { it.inputId == info.id }
                    }?.id
            }
        }
        if (activeInputId == null) {
            toast("Tidak ditemukan input tuner dengan daftar siaran.")
            return
        }
        val channels = allChannels
            .filter { it.inputId == activeInputId }
            .distinctBy {
                val number = it.number.trim().lowercase(Locale.ROOT)
                val name = it.name.trim().lowercase(Locale.ROOT)
                if (number.isNotBlank()) number else name
            }
            .sortedWith(compareBy<ChannelRow>({ it.number }, { it.name }))
        if (channels.isEmpty()) {
            promptNativeLiveTvScan()
            return
        }
        val labels = listOf("Otomatis (kanal pertama)") + channels.map { row ->
            listOf(row.number, row.name).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "Kanal TV" }
        }
        val selectedIndex = channels.indexOfFirst { it.id == selectedChannelId }
            .let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle("PILIH KANAL LIVE TV")
            .setMessage("Sumber tuner: " + (manager.tvInputList.firstOrNull { it.id == activeInputId }?.let {
                try { it.loadLabel(this).toString() } catch (_: Exception) { it.id }
            } ?: activeInputId))
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { picker, which ->
                val selectedId = if (which == 0) -1L else channels[which - 1].id
                prefs.edit()
                    .putString("live_tv_input_id", activeInputId)
                    .putLong("live_tv_channel_id", selectedId)
                    .apply()
                picker.dismiss()
                if (idleMode) {
                    stopLiveTvBackground()
                    startLiveTvBackground()
                }
                showSettings("Live TV")
                toast(if (which == 0) "Kanal otomatis dipilih" else "Kanal: ${labels[which]}")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showLiveTvInputPicker() {
        val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
        if (manager == null) {
            toast("Layanan input TV tidak tersedia pada perangkat ini.")
            return
        }
        val candidates = manager.tvInputList.filter { it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER }
        if (candidates.isEmpty()) {
            toast("Android TV tidak melaporkan input tuner atau HDMI.")
            return
        }
        fun inputDisplayName(info: TvInputInfo): String {
            val raw = try { info.loadLabel(this).toString() } catch (_: Exception) { info.id }
            val identity = (raw + " " + info.id).lowercase(Locale.ROOT)
            if (!info.isPassthroughInput) return "TV Digital / Antena"
            return when {
                Regex("hdmi\\s*1|hdmi1|hw2").containsMatchIn(identity) -> "HDMI 1"
                Regex("hdmi\\s*2|hdmi2|hw3").containsMatchIn(identity) -> "HDMI 2"
                Regex("\\bav\\b|composite|cvbs|hw1").containsMatchIn(identity) -> "AV"
                else -> raw.ifBlank { "Input eksternal" }
            }
        }
        val orderedCandidates = candidates.sortedBy { info ->
            when (inputDisplayName(info)) {
                "TV Digital / Antena" -> 0
                "HDMI 1" -> 1
                "HDMI 2" -> 2
                "AV" -> 3
                else -> 4
            }
        }
        val ids = listOf("auto") + orderedCandidates.map { it.id }
        val labels = listOf("Otomatis (pilih kanal tuner)") + orderedCandidates.map { inputDisplayName(it) }
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selected = prefs.getString("live_tv_input_id", "auto") ?: "auto"
        AlertDialog.Builder(this)
            .setTitle("SUMBER INPUT LIVE TV")
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(selected).coerceAtLeast(0)) { picker, which ->
                prefs.edit().putString("live_tv_input_id", ids[which]).apply()
                picker.dismiss()
                stopLiveTvBackground()
                if (idleMode) startLiveTvBackground()
                showSettings()
                toast("Sumber Live TV: ${labels[which]}")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun createLiveTvBackgroundLayer(target: FrameLayout) {
        if (!getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("live_tv_idle_background", false)) return
        val tv = TvView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            alpha = 1f
            // Keep the tuner surface in the media plane, behind the launcher's glass UI.
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                setZOrderMediaOverlay(false)
            }
            setCallback(object : TvView.TvInputCallback() {
                override fun onConnectionFailed(inputId: String?) {
                    android.util.Log.e("GlassTVLiveTv", "CONNECTION_FAILED input=$inputId")
                    runOnUiThread {
                        stopLiveTvBackground()
                        toast("Tuner gagal tersambung. Periksa input Live TV.")
                    }
                }
                override fun onVideoAvailable(inputId: String?) {
                    android.util.Log.i("GlassTVLiveTv", "VIDEO_AVAILABLE input=$inputId channel=$liveTvBackgroundChannelUri")
                    runOnUiThread { liveTvBackgroundScrim?.setBackgroundColor(Color.argb(28, 3, 7, 13)) }
                }
                override fun onVideoUnavailable(inputId: String?, reason: Int) {
                    android.util.Log.w("GlassTVLiveTv", "VIDEO_UNAVAILABLE input=$inputId reason=$reason channel=$liveTvBackgroundChannelUri")
                    runOnUiThread { liveTvBackgroundScrim?.setBackgroundColor(Color.argb(72, 3, 7, 13)) }
                }
            })
        }
        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(72, 3, 7, 13))
            isFocusable = false
        }
        tv.setStreamVolume(1f)
        target.addView(tv, 0, FrameLayout.LayoutParams(-1, -1))
        target.addView(scrim, 1, FrameLayout.LayoutParams(-1, -1))
        liveTvBackgroundView = tv
        liveTvBackgroundScrim = scrim
    }

    private fun startLiveTvBackground() {
        if (!getSharedPreferences("glass_tv", MODE_PRIVATE).getBoolean("live_tv_idle_background", false) ||
            !idleMode || isFinishing || !::wallpaperTarget.isInitialized) return
        val tv = liveTvBackgroundView ?: run {
            createLiveTvBackgroundLayer(wallpaperTarget)
            liveTvBackgroundView
        } ?: return
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val selectedInputId = prefs.getString("live_tv_input_id", "auto") ?: "auto"
        try {
            val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager ?: run {
                toast("Layanan tuner Android TV tidak tersedia.")
                return
            }
            val inputs = manager.tvInputList
            val inputInfo = if (selectedInputId != "auto") {
                inputs.firstOrNull { it.id == selectedInputId }
            } else {
                null
            }
            if (selectedInputId != "auto" && inputInfo == null) {
                toast("Input TV yang dipilih tidak tersedia. Periksa kembali sumber input.")
                return
            }
            if (inputInfo?.isPassthroughInput == true) {
                val passthroughUri = TvContract.buildChannelUriForPassthroughInput(inputInfo.id)
                liveTvBackgroundInputId = inputInfo.id
                liveTvBackgroundChannelUri = passthroughUri
                tv.tune(inputInfo.id, passthroughUri)
                wallpaperTarget.setBackgroundColor(Color.rgb(4, 7, 12))
                return
            }
            data class TunedChannel(val id: Long, val inputId: String, val serviceType: String)
            val channels = contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID,
                    TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.COLUMN_DISPLAY_NUMBER),
                null, null, TvContract.Channels.COLUMN_DISPLAY_NUMBER + " ASC"
            )?.use { cursor ->
                val found = mutableListOf<TunedChannel>()
                while (cursor.moveToNext()) {
                    val idIndex = cursor.getColumnIndex(TvContract.Channels._ID)
                    val inputIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                    val typeIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_TYPE)
                    if (idIndex >= 0 && inputIndex >= 0 && !cursor.isNull(inputIndex)) {
                        found.add(TunedChannel(cursor.getLong(idIndex), cursor.getString(inputIndex),
                            if (typeIndex >= 0) cursor.getString(typeIndex).orEmpty() else ""))
                    }
                }
                found
            }.orEmpty()
            val tunerInfos = if (inputInfo != null) listOf(inputInfo) else {
                inputs.filter { it.type == TvInputInfo.TYPE_TUNER }.sortedBy { info ->
                    val pkg = info.serviceInfo.packageName.lowercase(Locale.ROOT)
                    when {
                        pkg.contains("tuner") -> 0
                        pkg.contains("dtv") -> 1
                        pkg.contains("mitv") || pkg.contains("livetv") -> 2
                        else -> 3
                    }
                }
            }
            val savedChannelId = prefs.getLong("live_tv_channel_id", -1L)
            val selectedTuner = tunerInfos.firstOrNull { info ->
                channels.any { it.inputId == info.id && it.id == savedChannelId }
            } ?: tunerInfos.firstOrNull { info ->
                channels.any { it.inputId == info.id && it.serviceType == TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO }
            } ?: tunerInfos.firstOrNull { info -> channels.any { it.inputId == info.id } }
                ?: run {
                    toast("Kanal belum cocok dengan input tuner yang tersedia. Buka Live TV bawaan, pindai kanal, lalu coba lagi.")
                    return
                }
            val inputChannels = channels.filter { it.inputId == selectedTuner.id }
            val savedChannel = inputChannels.firstOrNull { it.id == savedChannelId }
            val channel = savedChannel?.takeIf { it.serviceType != TvContract.Channels.SERVICE_TYPE_AUDIO }
                ?: inputChannels.firstOrNull { it.serviceType == TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO }
                ?: inputChannels.firstOrNull()
                ?: return
            if (savedChannel?.serviceType == TvContract.Channels.SERVICE_TYPE_AUDIO) {
                android.util.Log.w("GlassTVLiveTv", "Saved channel $savedChannelId is audio-only; switching to A/V channel " + channel.id)
            }
            val channelUri = TvContract.buildChannelUri(channel.id)
            liveTvBackgroundInputId = selectedTuner.id
            liveTvBackgroundChannelUri = channelUri
            android.util.Log.i("GlassTVLiveTv", "TUNING input=" + selectedTuner.id + " channel=" + channel.id + " serviceType=" + channel.serviceType)
            tv.tune(selectedTuner.id, channelUri)
            wallpaperTarget.setBackgroundColor(Color.rgb(4, 7, 12))
        } catch (_: SecurityException) {
            toast("Izin membaca kanal TV belum diberikan. Aktifkan akses TV listings untuk GlassTV.")
        } catch (_: Exception) {
            stopLiveTvBackground()
            toast("Live TV background gagal dimulai. Tombol Live TV tetap bisa digunakan.")
        }
    }

    private fun stopLiveTvBackground() {
        try { liveTvBackgroundView?.reset() } catch (_: Exception) { }
        (liveTvBackgroundView?.parent as? ViewGroup)?.removeView(liveTvBackgroundView)
        (liveTvBackgroundScrim?.parent as? ViewGroup)?.removeView(liveTvBackgroundScrim)
        liveTvBackgroundView = null
        liveTvBackgroundScrim = null
        liveTvBackgroundInputId = null
        liveTvBackgroundChannelUri = null
        // Do not recreate a wallpaper video while the tuner is being switched/restarted in idle mode.
        // Restore it only after leaving idle mode.
        if (!idleMode && ::wallpaperTarget.isInitialized && !isFinishing) {
            applyWallpaper(wallpaperTarget)
            setupVideoWallpaper(wallpaperTarget)
        }
    }

    private fun enterIdleMode() {
        if (idleMode || isFinishing || !::root.isInitialized || !hasWindowFocus()) return
        idleMode = true
        applyMinimalistSectionVisibility()
        applyMinimalistHeaderPosition()
        // A true minimalist home: only manually retained recommendation rows and the compact
        // clock/weather/prayer stack remain visible. Hide the full dashboard and media/CCTV row.
        dashboardPanelView?.visibility = View.GONE
        applyPreviewRowVisibility()
        headerBrandView?.visibility = View.GONE
        headerTimeLabel?.text = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID")).apply {
            timeZone = java.util.TimeZone.getTimeZone(dashboardSnapshot?.timeZone ?: DashboardServices.selectedCity(this@MainActivity).timeZone)
        }.format(Date())
        headerClockLabel?.visibility = View.VISIBLE
        dashboardHandler.removeCallbacks(headerClockBlink)
        dashboardHandler.post(headerClockBlink)
        headerWeatherLabel?.visibility = View.VISIBLE
        headerPrayerMetaLabel?.visibility = View.VISIBLE
        headerHijriLabel?.visibility = View.VISIBLE
        headerNextPrayerLabel?.visibility = View.VISIBLE
        homeHeaderView?.let { view ->
            val position = getSharedPreferences("glass_tv", MODE_PRIVATE)
                .getString("minimalist_panel_position", "bottom_full")
            view.layoutParams = view.layoutParams.apply {
                height = dp(if (position == "bottom_center") 132 else if (position == "bottom_full") 66 else 104)
            }
            view.requestLayout()
        }
        // Stop and detach Home CCTV surfaces before the selected compact background takes over.
        homePreviewPlayerViews.forEach { it.visibility = View.GONE }
        homePreviewPlayers.forEach { it.pause() }
        root.requestFocus()
        applyIdleBackgroundPriority()
    }

    private fun exitIdleMode() {
        if (!idleMode) return
        idleMode = false
        homeHeaderView?.let { headerView ->
            val header = headerView as? LinearLayout ?: return@let
            root.removeView(header)
            header.orientation = LinearLayout.HORIZONTAL
            header.gravity = Gravity.CENTER_VERTICAL
            header.background = null
            header.setPadding(0, 0, 0, 0)
            headerSpacerView?.visibility = View.VISIBLE
            headerRightView?.let { right ->
                right.orientation = LinearLayout.VERTICAL
                right.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                right.layoutParams = LinearLayout.LayoutParams(dp(440), -2)
            }
            listOf(headerTimeLabel, headerClockLabel, headerWeatherLabel, headerPrayerMetaLabel, headerHijriLabel, headerNextPrayerLabel).forEach {
                it?.gravity = Gravity.END
                if (it != null) {
                    it.textSize = when (it) { headerClockLabel -> 30f; headerNextPrayerLabel -> 12f; else -> 10f }
                    it.setPadding(0, 0, 0, 0)
                }
            }
            minimalistBottomSpacer?.let { root.removeView(it) }
            minimalistBottomSpacer = null
            root.addView(header, 0, LinearLayout.LayoutParams(-1, dp(42)))
        }
        applyMinimalistSectionVisibility()
        dashboardPanelView?.visibility = View.VISIBLE
        applyPreviewRowVisibility()
        idlePreviewSpacer?.visibility = View.GONE
        dashboardHandler.removeCallbacks(headerClockBlink)
        headerBrandView?.visibility = View.VISIBLE
        headerClockLabel?.visibility = View.GONE
        headerWeatherLabel?.visibility = View.GONE
        headerPrayerMetaLabel?.visibility = View.GONE
        headerHijriLabel?.visibility = View.GONE
        headerNextPrayerLabel?.visibility = View.GONE
        homeHeaderView?.let { view ->
            view.layoutParams = view.layoutParams.apply { height = dp(42) }
            view.requestLayout()
        }
        homeMediaRowView?.animate()?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(220L)?.start()
        releaseLiveAmbientWallpaper()
        stopLiveTvBackground()
        homePreviewPlayerViews.forEach { it.visibility = View.VISIBLE }
        homePreviewPlayers.forEach { player ->
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        }
        dashboardSnapshot?.let { renderDashboardSnapshot(it, null) }
        val target = actions.firstOrNull { it.contentDescription?.toString()?.startsWith(lastFocusedTitle) == true }
            ?: continueWatchingCard ?: favoriteDockTiles.firstOrNull() ?: actions.firstOrNull()
        target?.requestFocus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7143 && resultCode == RESULT_OK) {
            val imageUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            getSharedPreferences("glass_tv", MODE_PRIVATE).edit()
                .putString("wallpaper_custom_uri", imageUri.toString())
                .putString("wallpaper_source", "custom")
                .putBoolean("wallpaper_auto", false)
                .apply()
            showHome()
            toast("Gambar sendiri dijadikan wallpaper")
            return
        }
        if ((requestCode == 7144 || requestCode == 7145) && resultCode == RESULT_OK) {
            val imageUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            val key = if (requestCode == 7145) "wallpaper_night_uri" else "wallpaper_day_uri"
            getSharedPreferences("glass_tv", MODE_PRIVATE).edit()
                .putString(key, imageUri.toString())
                .putString("wallpaper_source", "daynight")
                .putBoolean("wallpaper_auto", true)
                .apply()
            showHome()
            toast(if (requestCode == 7145) "Wallpaper malam disimpan" else "Wallpaper siang disimpan")
            return
        }
        if (requestCode != wallpaperVideoRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        getSharedPreferences("glass_tv", MODE_PRIVATE).edit()
            .putString("wallpaper_video_uri", uri.toString())
            .putBoolean("wallpaper_auto", false)
            .apply()
        showHome()
        toast("Wallpaper video diaktifkan tanpa suara")
    }

    private fun setupLiveAmbientWallpaper(target: FrameLayout) {
        val current = liveAmbientWallpaperView
        if (current != null && current.parent === target) return
        releaseLiveAmbientWallpaper()
        releaseVideoWallpaperPlayer()
        val ambient = LiveAmbientWallpaperView(this)
        target.addView(ambient, 0, FrameLayout.LayoutParams(-1, -1))
        liveAmbientWallpaperView = ambient
    }

    private fun releaseLiveAmbientWallpaper() {
        (liveAmbientWallpaperView?.parent as? ViewGroup)?.removeView(liveAmbientWallpaperView)
        liveAmbientWallpaperView = null
    }

    private fun idleBackgroundModeLabel(): String {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (prefs.getBoolean("live_tv_idle_background", false)) return "LIVE TV UTAMA"
        return if (prefs.getString("idle_background_mode", "ambient") == "wallpaper") "WALLPAPER" else "LIVE AMBIENT"
    }

    private fun showIdleBackgroundModePicker() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val values = arrayOf("ambient", "wallpaper")
        val labels = arrayOf(
            "Live Ambient  ·  langit malam, bintang, dan aurora bergerak",
            "Wallpaper  ·  foto otomatis atau video lokal"
        )
        val current = values.indexOf(prefs.getString("idle_background_mode", "ambient")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("BACKGROUND SAAT LIVE TV MATI")
            .setSingleChoiceItems(labels, current) { picker, which ->
                prefs.edit().putString("idle_background_mode", values[which]).apply()
                picker.dismiss()
                if (idleMode) applyIdleBackgroundPriority()
                showSettings("Tampilan")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Priority order for compact mode:
     * 1) Live TV tuner, when enabled.
     * 2) User-selected Live Ambient or photo/video wallpaper otherwise.
     */
    private fun applyIdleBackgroundPriority() {
        if (!idleMode || isFinishing || !::wallpaperTarget.isInitialized) return
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (prefs.getBoolean("live_tv_idle_background", false)) {
            releaseLiveAmbientWallpaper()
            releaseVideoWallpaperPlayer()
            startLiveTvBackground()
            return
        }

        stopLiveTvBackground()
        if (prefs.getString("idle_background_mode", "ambient") == "ambient") {
            releaseVideoWallpaperPlayer()
            setupLiveAmbientWallpaper(wallpaperTarget)
        } else {
            releaseLiveAmbientWallpaper()
            applyWallpaper(wallpaperTarget)
            setupVideoWallpaper(wallpaperTarget)
        }
    }

    private fun setupVideoWallpaper(target: FrameLayout) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val weatherMode = prefs.getString("wallpaper_source", "bing") == "weather_motionbgs"
        val rawUri = if (weatherMode) prefs.getString("wallpaper_weather_video_path", null)?.let { Uri.fromFile(File(it)) }
            else prefs.getString("wallpaper_video_uri", null)?.let { Uri.parse(it) } ?: return
        if (rawUri == null) return
        if (videoWallpaperPlayer != null && videoWallpaperView?.parent === target) return
        releaseVideoWallpaperPlayer()
        try {
            val view = PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.rgb(5, 8, 14))
                setBackgroundColor(Color.rgb(5, 8, 14))
                alpha = 0.92f
                isFocusable = false
                isClickable = false
            }
            val player = ExoPlayer.Builder(this).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(rawUri))
                prepare()
                playWhenReady = true
            }
            view.player = player
            target.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            videoWallpaperView = view
            videoWallpaperPlayer = player
            target.setBackgroundColor(Color.rgb(5, 8, 14))
        } catch (_: Exception) {
            releaseVideoWallpaperPlayer()
            toast("Video wallpaper tidak dapat dibuka. Pilih video MP4 lain.")
        }
    }

    private fun releaseVideoWallpaperPlayer() {
        try { videoWallpaperView?.player = null } catch (_: Exception) { }
        try { videoWallpaperPlayer?.release() } catch (_: Exception) { }
        videoWallpaperPlayer = null
        (videoWallpaperView?.parent as? ViewGroup)?.removeView(videoWallpaperView)
        videoWallpaperView = null
    }

    private fun refreshWeatherWallpaper(snapshot: DashboardServices.Snapshot, force: Boolean = false) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (prefs.getString("wallpaper_source", "bing") != "weather_motionbgs") return
        val hour = Calendar.getInstance(java.util.TimeZone.getTimeZone(snapshot.timeZone)).get(Calendar.HOUR_OF_DAY)
        val category = MotionBgsWeatherWallpaper.categoryFor(snapshot.condition, hour)
        val file = File(cacheDir, "glasstv_motionbgs_weather.mp4")
        val savedCategory = prefs.getString("wallpaper_weather_category", "")
        val lastRefresh = prefs.getLong("wallpaper_weather_refresh", 0L)
        if (!force && file.exists() && file.length() > 100_000L && savedCategory == category &&
            System.currentTimeMillis() - lastRefresh < 6L * 60L * 60L * 1000L) {
            if (!idleMode && videoWallpaperPlayer == null && ::wallpaperTarget.isInitialized) setupVideoWallpaper(wallpaperTarget)
            return
        }
        if (weatherWallpaperLoading) return
        weatherWallpaperLoading = true
        MotionBgsWeatherWallpaper.download(this, category) { result, error ->
            weatherWallpaperLoading = false
            if (result == null) {
                android.util.Log.w("GlassTVWallpaper", "MotionBGS download failed: " + error)
                toast("MotionBGS gagal dimuat: ${error ?: "sumber tidak merespons"}")
                return@download
            }
            prefs.edit().putString("wallpaper_weather_video_path", result.file.absolutePath)
                .putString("wallpaper_weather_category", result.category)
                .putLong("wallpaper_weather_refresh", System.currentTimeMillis()).apply()
            android.util.Log.i("GlassTVWallpaper", "Weather wallpaper loaded: " + result.title + " [" + result.category + "]")
            if (!isFinishing && !idleMode && ::wallpaperTarget.isInitialized &&
                prefs.getString("wallpaper_source", "bing") == "weather_motionbgs") {
                releaseVideoWallpaperPlayer()
                setupVideoWallpaper(wallpaperTarget)
                toast("Wallpaper cuaca diperbarui: " + result.title)
            }
        }
    }

    private fun loadAutomaticDayNightWallpaper(nightSlot: Boolean, display: ((Bitmap) -> Unit)?) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val manualKey = if (nightSlot) "wallpaper_night_uri" else "wallpaper_day_uri"
        if (!prefs.getString(manualKey, null).isNullOrBlank()) return

        val slot = if (nightSlot) "night" else "day"
        val cacheFile = File(cacheDir, "glasstv_wallpaper_${slot}.jpg")
        val refreshKey = "wallpaper_auto_${slot}_downloaded_at"
        fun showCached() {
            if (!cacheFile.exists() || cacheFile.length() < 12_000L) return
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath, options)
                if (bitmap != null) display?.invoke(bitmap)
            } catch (error: Exception) {
                android.util.Log.w("GlassTVWallpaper", "Cached ${slot} wallpaper could not be decoded", error)
            }
        }

        showCached()
        val lastDownload = prefs.getLong(refreshKey, 0L)
        val needsDownload = !cacheFile.exists() || cacheFile.length() < 12_000L ||
            System.currentTimeMillis() - lastDownload > 20L * 60L * 60L * 1000L
        if (!needsDownload) return

        val downloadKey = slot
        val shouldStart = synchronized(wallpaperDownloadLock) {
            if (downloadKey in wallpaperDownloadSlots) false
            else {
                wallpaperDownloadSlots.add(downloadKey)
                true
            }
        }
        if (!shouldStart) return

        Thread({
            var connection: HttpURLConnection? = null
            val pendingFile = File(cacheDir, "glasstv_wallpaper_${slot}_pending.tmp")
            try {
                val index = if (nightSlot) 1 else 0
                connection = URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=${index}&n=1&mkt=en-US")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                connection.setRequestProperty("Accept", "application/json")
                val apiCode = connection.responseCode
                if (apiCode !in 200..299) throw IllegalStateException("Bing API HTTP ${apiCode}")
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                connection = null

                val imagePath = JSONObject(json).optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
                if (imagePath.isBlank()) throw IllegalStateException("Bing tidak mengirim URL wallpaper ${slot}.")
                val imageUrl = if (imagePath.startsWith("http")) imagePath else "https://www.bing.com${imagePath}"
                connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 25000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                val imageCode = connection.responseCode
                if (imageCode !in 200..299) throw IllegalStateException("Unduhan gambar HTTP ${imageCode}")
                pendingFile.delete()
                connection.inputStream.use { input -> pendingFile.outputStream().use { output -> input.copyTo(output) } }
                if (pendingFile.length() < 12_000L) throw IllegalStateException("File wallpaper ${slot} terlalu kecil.")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(pendingFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Respons Bing bukan gambar yang valid.")
                if (cacheFile.exists()) cacheFile.delete()
                if (!pendingFile.renameTo(cacheFile)) pendingFile.copyTo(cacheFile, overwrite = true)
                pendingFile.delete()
                prefs.edit().putLong(refreshKey, System.currentTimeMillis()).apply()

                if (display != null) {
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(cacheFile.absolutePath, options)?.let { bitmap ->
                        display.invoke(bitmap)
                    }
                }
                android.util.Log.i("GlassTVWallpaper", "Automatic ${slot} wallpaper downloaded (${cacheFile.length()} bytes)")
            } catch (error: Exception) {
                pendingFile.delete()
                android.util.Log.w("GlassTVWallpaper", "Automatic ${slot} wallpaper download failed: ${error.message}", error)
                if (display != null) runOnUiThread {
                    if (!isFinishing) toast("Wallpaper ${slot} belum dapat diunduh; gambar tersimpan tetap digunakan.")
                }
            } finally {
                connection?.disconnect()
                synchronized(wallpaperDownloadLock) { wallpaperDownloadSlots.remove(downloadKey) }
            }
        }, "glasstv-wallpaper-${slot}").start()
    }

    private fun wallpaperSourceLabel(): String = when (
        getSharedPreferences("glass_tv", MODE_PRIVATE).getString("wallpaper_source", "bing")
    ) {
        "bing" -> "Photo of the Day · Bing"
        "gradient" -> "Gradient"
        "custom" -> "Gambar sendiri"
        "daynight" -> "Otomatis · Unduh Bing Siang / Malam"
        else -> "Photo of the Day · Bing"
    }

    private fun pickDayNightWallpaper(night: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, if (night) 7145 else 7144) }
        catch (_: Exception) { toast("Pemilih gambar tidak tersedia pada TV ini.") }
    }

    private fun wallpaperDimLabel(): String = when (
        getSharedPreferences("glass_tv", MODE_PRIVATE).getString("wallpaper_dim", "medium")
    ) {
        "light" -> "Light"
        "strong" -> "Strong"
        else -> "Medium"
    }

    private fun showWallpaperSourcePicker() {
        val values = arrayOf("bing", "gradient", "custom", "daynight")
        val labels = arrayOf("Photo of the Day · Bing (berganti otomatis)", "Gradient · tanpa internet", "Gambar sendiri (termasuk foto Pexels)", "Otomatis Siang / Malam · unduh foto Bing dan simpan offline")
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        val current = values.indexOf(prefs.getString("wallpaper_source", "photos")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("SUMBER WALLPAPER")
            .setSingleChoiceItems(labels, current) { picker, which ->
                prefs.edit().putString("wallpaper_source", values[which]).apply()
                if (values[which] == "bing") prefs.edit().remove("wallpaper_video_uri").putBoolean("wallpaper_auto", true).apply()
                picker.dismiss()
                releaseVideoWallpaperPlayer()
                if (values[which] == "custom" && prefs.getString("wallpaper_custom_uri", null) == null) {
                    pickCustomWallpaper()
                } else {
                    applyWallpaper(wallpaperTarget, forceRefresh = true)
                    scheduleWallpaperRotation()
                    showSettings()
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun showWallpaperIntervalPicker() {
        val values = intArrayOf(1, 3, 6, 24)
        val labels = arrayOf("Setiap 1 jam", "Setiap 3 jam", "Setiap 6 jam", "Setiap 24 jam")
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        AlertDialog.Builder(this).setTitle("INTERVAL WALLPAPER")
            .setSingleChoiceItems(labels, values.indexOf(prefs.getInt("wallpaper_interval_hours", 3)).coerceAtLeast(0)) { picker, which ->
                prefs.edit().putInt("wallpaper_interval_hours", values[which]).apply()
                picker.dismiss()
                scheduleWallpaperRotation()
                showSettings()
            }.setNegativeButton("Batal", null).show()
    }

    private fun showWallpaperDimPicker() {
        val values = arrayOf("light", "medium", "strong")
        val labels = arrayOf("Light", "Medium", "Strong")
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        AlertDialog.Builder(this).setTitle("REDUPKAN WALLPAPER")
            .setSingleChoiceItems(labels, values.indexOf(prefs.getString("wallpaper_dim", "medium")).coerceAtLeast(0)) { picker, which ->
                prefs.edit().putString("wallpaper_dim", values[which]).apply()
                picker.dismiss()
                applyWallpaper(wallpaperTarget)
                showSettings()
            }.setNegativeButton("Batal", null).show()
    }

    private fun pickCustomWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, 7143) }
        catch (_: Exception) { toast("Pemilih gambar tidak tersedia pada TV ini.") }
    }

    private val dayNightSwitch = object : Runnable {
        override fun run() {
            if (::wallpaperTarget.isInitialized &&
                getSharedPreferences("glass_tv", MODE_PRIVATE).getString("wallpaper_source", "bing") == "daynight") {
                applyWallpaper(wallpaperTarget, forceRefresh = true)
                scheduleWallpaperRotation()
            }
        }
    }

    private fun scheduleWallpaperRotation() {
        wallpaperHandler.removeCallbacks(wallpaperCycle)
        wallpaperHandler.removeCallbacks(dayNightSwitch)
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        if (prefs.getString("wallpaper_source", "bing") == "daynight") {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                val hour = now.get(Calendar.HOUR_OF_DAY)
                set(Calendar.HOUR_OF_DAY, if (hour in 6..17) 18 else 6)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
            wallpaperHandler.postDelayed(dayNightSwitch, (next.timeInMillis - now.timeInMillis).coerceAtLeast(1000L))
            return
        }
        if (prefs.getString("wallpaper_video_uri", null) == null &&
            prefs.getBoolean("wallpaper_auto", true) &&
            prefs.getString("wallpaper_source", "bing") != "custom") {
            val hours = prefs.getInt("wallpaper_interval_hours", 3).coerceIn(1, 24)
            wallpaperHandler.postDelayed(wallpaperCycle, hours * 60L * 60L * 1000L)
        }
    }

    private fun applyWallpaper(target: FrameLayout, forceRefresh: Boolean = false) {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        var source = prefs.getString("wallpaper_source", "bing") ?: "bing"
        if (source == "weather_motionbgs" || source == "art" || source == "photos") {
            // Migrate retired wallpaper sources back to the official Bing daily image feed.
            prefs.edit().putString("wallpaper_source", "bing").remove("wallpaper_weather_video_path").apply()
            source = "bing"
        }
        if (prefs.getString("wallpaper_video_uri", null) != null) {
            target.setBackgroundColor(Color.rgb(5, 8, 14))
            return
        }
        if (target === wallpaperTarget && ::root.isInitialized && continueWatchingCard?.hasFocus() != true) {
            root.setBackgroundColor(Color.TRANSPARENT)
        }
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val nightSlot = source == "daynight" && (currentHour >= 18 || currentHour < 6)
        val dimAlpha = when (prefs.getString("wallpaper_dim", "medium")) {
            "light" -> if (nightSlot) 104 else 42
            "strong" -> 166
            else -> if (nightSlot) 142 else 82
        }
        fun display(bitmap: Bitmap) {
            runOnUiThread {
                if (isFinishing || target !== wallpaperTarget) return@runOnUiThread
                val image = BitmapDrawable(resources, bitmap).apply { gravity = Gravity.FILL }
                val scrim = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb((dimAlpha * 0.65f).toInt(), 4, 8, 16),
                        Color.argb(dimAlpha, 5, 9, 17), Color.argb((dimAlpha * 1.35f).toInt().coerceAtMost(210), 4, 7, 13))
                )
                target.background = LayerDrawable(arrayOf(image, scrim))
            }
        }
        if (source == "gradient") {
            val palettes = arrayOf(
                intArrayOf(Color.rgb(14, 36, 58), Color.rgb(38, 25, 66), Color.rgb(7, 12, 23)),
                intArrayOf(Color.rgb(12, 54, 58), Color.rgb(21, 34, 54), Color.rgb(6, 11, 20)),
                intArrayOf(Color.rgb(64, 35, 35), Color.rgb(34, 31, 58), Color.rgb(7, 12, 23)),
                intArrayOf(Color.rgb(30, 42, 60), Color.rgb(15, 24, 39), Color.rgb(5, 9, 16))
            )
            val index = prefs.getInt("wallpaper_index", 0).mod(palettes.size)
            val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, palettes[index]).apply {
                setDither(true)
            }
            val gradientDim = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb((dimAlpha * 0.65f).toInt(), 4, 8, 16),
                    Color.argb(dimAlpha, 5, 9, 17), Color.argb((dimAlpha * 1.35f).toInt().coerceAtMost(210), 4, 7, 13))
            )
            target.background = LayerDrawable(arrayOf(gradient, gradientDim))
            return
        }
        if (source == "daynight") {
            val selectedUri = prefs.getString(if (nightSlot) "wallpaper_night_uri" else "wallpaper_day_uri", null)
            if (selectedUri != null) {
                Thread({
                    try {
                        val bitmap = contentResolver.openInputStream(Uri.parse(selectedUri))?.use { BitmapFactory.decodeStream(it) }
                        if (bitmap != null) display(bitmap)
                    } catch (error: Exception) {
                        android.util.Log.w("GlassTVWallpaper", "Day/night image could not be decoded", error)
                    }
                }, "glasstv-daynight-wallpaper").start()
                return
            }
            // With no manual image selected, download two independent Bing images and cache
            // them locally for offline use: today's image for daytime, yesterday's for night.
            loadAutomaticDayNightWallpaper(nightSlot, display)
            loadAutomaticDayNightWallpaper(!nightSlot, null)
            return
        }
        if (source == "custom") {
            val raw = prefs.getString("wallpaper_custom_uri", null) ?: run {
                target.setBackgroundColor(Color.rgb(7, 12, 20))
                return
            }
            Thread({
                try {
                    val bitmap = contentResolver.openInputStream(Uri.parse(raw))?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) display(bitmap)
                } catch (_: Exception) { }
            }, "glasstv-custom-wallpaper").start()
            return
        }
        val file = File(cacheDir, "glasstv_wallpaper_active.jpg")
        fun showCached() {
            if (!file.exists() || file.length() < 12_000L) return
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, options)?.let { display(it) }
            } catch (_: Exception) { }
        }
        if (!forceRefresh && file.exists() && file.length() > 12_000L) {
            showCached()
            return
        }
        if (!file.exists() || file.length() < 12_000L) {
            val fallback = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 31, 49), Color.rgb(27, 27, 48), Color.rgb(5, 9, 17))
            )
            val fallbackScrim = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb((dimAlpha * 0.65f).toInt(), 4, 8, 16),
                    Color.argb(dimAlpha, 5, 9, 17), Color.argb((dimAlpha * 1.35f).toInt().coerceAtMost(210), 4, 7, 13))
            )
            target.background = LayerDrawable(arrayOf(fallback, fallbackScrim))
        }
        // Keep the last known-good wallpaper visible until a replacement is fully downloaded and decoded.
        val pendingFile = File(cacheDir, "glasstv_wallpaper_pending.tmp")
        Thread({
            var connection: HttpURLConnection? = null
            try {
                val index = prefs.getInt("wallpaper_bing_index", 0).mod(8)
                connection = URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$index&n=1&mkt=en-US").openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                connection.setRequestProperty("Accept", "application/json")
                val apiCode = connection.responseCode
                if (apiCode !in 200..299) throw IllegalStateException("Bing API HTTP $apiCode")
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                connection = null
                val imagePath = JSONObject(json).optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
                if (imagePath.isBlank()) throw IllegalStateException("Bing tidak mengirim URL foto harian.")
                val imageUrl = if (imagePath.startsWith("http")) imagePath else "https://www.bing.com$imagePath"
                connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 25000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                val imageCode = connection.responseCode
                if (imageCode !in 200..299) throw IllegalStateException("Unduhan gambar HTTP $imageCode")
                pendingFile.delete()
                connection.inputStream.use { input -> pendingFile.outputStream().use { output -> input.copyTo(output) } }
                if (pendingFile.length() < 12_000L) throw IllegalStateException("File wallpaper terlalu kecil.")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(pendingFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IllegalStateException("Respons sumber bukan gambar yang valid.")
                }
                if (file.exists()) file.delete()
                if (!pendingFile.renameTo(file)) pendingFile.copyTo(file, overwrite = true)
                pendingFile.delete()
                showCached()
            } catch (error: Exception) {
                pendingFile.delete()
                android.util.Log.e("GlassTVWallpaper", "Wallpaper source \"$source\" failed: ${error.message}", error)
                runOnUiThread { toast("Wallpaper $source gagal: ${error.message ?: "sumber tidak merespons"}") }
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
                setStroke(dp(1), accentAlpha(190))
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
                setColor(accentAlpha(130))
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

package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class CctvActivity : Activity() {
    private val backgroundColor = Color.rgb(9, 13, 22)
    private val accent = Color.rgb(126, 190, 255)
    private val cameraNames = mutableListOf<String>()
    private val cameraUrls = mutableListOf<String>()
    private val cells = mutableListOf<FrameLayout>()
    private val playerViews = mutableListOf<PlayerView>()
    private val nameLabels = mutableListOf<TextView>()
    private val statusLabels = mutableListOf<TextView>()
    private val players: MutableList<ExoPlayer?> = MutableList(2) { null }

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var grid: GridLayout
    private lateinit var actionButton: TextView
    private var fullscreenIndex = -1
    private var activityStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        for (number in 1..4) {
            cameraNames.add(
                prefs.getString("camera_name_$number", null)
                    ?.takeIf { it.isNotBlank() } ?: "Kamera $number"
            )
            cameraUrls.add(prefs.getString("camera_$number", null)?.trim().orEmpty())
        }
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        for (index in 0..1) startPlayer(index)
    }

    override fun onStop() {
        activityStarted = false
        for (index in 0..1) releasePlayer(index)
        super.onStop()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(18))
            setBackgroundColor(backgroundColor)
            clipChildren = false
            clipToPadding = false
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("GLASS TV  /  CCTV", 24, Color.WHITE, true),
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )
        actionButton = TextView(this).apply {
            text = "ATUR KAMERA"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = actionBackground(false)
            setOnFocusChangeListener { view, focused ->
                view.background = actionBackground(focused)
            }
            setOnClickListener {
                if (fullscreenIndex >= 0) returnToGrid() else showCameraManager()
            }
        }
        header.addView(actionButton, LinearLayout.LayoutParams(-2, dp(42)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))

        root.addView(
            label(
                "2 kamera • RTSP langsung • OK untuk layar penuh • BACK untuk kembali",
                12, Color.rgb(166, 185, 210), false
            ).apply { setPadding(0, dp(2), 0, dp(8)) },
            LinearLayout.LayoutParams(-1, -2)
        )

        content = FrameLayout(this)
        grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 1
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setBackgroundColor(backgroundColor)
        }

        for (index in 0..1) {
            val cell = createCell(index)
            cells.add(cell)
            grid.addView(cell, gridParams(index))
        }
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.post { cells.firstOrNull()?.requestFocus() }
    }

    private fun createCell(index: Int): FrameLayout {
        val cell = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(Color.rgb(15, 22, 34), 14)
            foreground = focusBorder(false)
            contentDescription = "${cameraNames[index]}, kamera ${index + 1}"
            setOnFocusChangeListener { view, focused ->
                view.foreground = focusBorder(focused)
                if (focused) view.contentDescription = "${cameraNames[index]}, tekan OK untuk layar penuh"
            }
            setOnClickListener {
                if (fullscreenIndex == index) {
                    returnToGrid()
                } else if (cameraUrls[index].isBlank()) {
                    showCameraEditor(index)
                } else {
                    enterFullscreen(index)
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO)
                ) {
                    showCameraEditor(index)
                    true
                } else {
                    false
                }
            }
        }

        val playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.rgb(5, 8, 13))
            setBackgroundColor(Color.rgb(5, 8, 13))
            keepScreenOn = true
        }
        playerViews.add(playerView)
        cell.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val name = TextView(this).apply {
            text = cameraNames[index]
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.argb(215, 6, 11, 18), 9)
        }
        nameLabels.add(name)
        cell.addView(
            name,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(10), dp(10), dp(10), 0)
            }
        )

        val status = TextView(this).apply {
            text = if (cameraUrls[index].isBlank()) "BELUM DIATUR  •  OK UNTUK SETUP" else "MENYIAPKAN STREAM…"
            textSize = 11f
            setTextColor(if (cameraUrls[index].isBlank()) Color.rgb(220, 205, 154) else accent)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.argb(220, 6, 11, 18), 8)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusLabels.add(status)
        cell.addView(
            status,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(10), 0, dp(10), dp(10))
            }
        )
        return cell
    }

    private fun gridParams(index: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(
            GridLayout.spec(index / 2, 1, 1f),
            GridLayout.spec(index % 2, 1, 1f)
        ).apply {
            width = 0
            height = 0
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

    private fun startPlayer(index: Int) {
        if (!activityStarted || players[index] != null) return
        val url = cameraUrls[index]
        if (url.isBlank()) {
            statusLabels[index].text = "BELUM DIATUR  •  OK UNTUK SETUP"
            statusLabels[index].setTextColor(Color.rgb(220, 205, 154))
            return
        }
        if (!url.startsWith("rtsp://", true) && !url.startsWith("rtsps://", true)) {
            statusLabels[index].text = "URL BUKAN RTSP"
            statusLabels[index].setTextColor(Color.rgb(255, 170, 150))
            return
        }

        try {
            val player = ExoPlayer.Builder(this).build()
            players[index] = player
            player.volume = 0f
            playerViews[index].player = player
            statusLabels[index].text = "MENGHUBUNGKAN…"
            statusLabels[index].setTextColor(accent)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (players[index] !== player) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            statusLabels[index].text = "MENGHUBUNGKAN…"
                            statusLabels[index].setTextColor(accent)
                        }
                        Player.STATE_READY -> {
                            statusLabels[index].text = "SIARAN LANGSUNG"
                            statusLabels[index].setTextColor(Color.rgb(142, 230, 185))
                        }
                        Player.STATE_ENDED -> {
                            statusLabels[index].text = "STREAM SELESAI"
                            statusLabels[index].setTextColor(Color.rgb(220, 205, 154))
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (players[index] !== player) return
                    statusLabels[index].text = "GAGAL  •  CEK URL / CODEC / JARINGAN"
                    statusLabels[index].setTextColor(Color.rgb(255, 170, 150))
                }
            })
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        } catch (_: Exception) {
            releasePlayer(index)
            statusLabels[index].text = "GAGAL MEMBUKA STREAM"
            statusLabels[index].setTextColor(Color.rgb(255, 170, 150))
        }
    }

    private fun releasePlayer(index: Int) {
        playerViews.getOrNull(index)?.player = null
        players[index]?.release()
        players[index] = null
    }

    private fun enterFullscreen(index: Int) {
        if (fullscreenIndex >= 0) returnToGrid()
        grid.removeView(cells[index])
        content.removeView(grid)
        content.addView(cells[index], FrameLayout.LayoutParams(-1, -1))
        fullscreenIndex = index
        actionButton.text = "KEMBALI KE 2×2"
        cells[index].requestFocus()
    }

    private fun returnToGrid() {
        if (fullscreenIndex < 0) return
        val index = fullscreenIndex
        content.removeView(cells[index])
        grid.addView(cells[index], gridParams(index))
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        fullscreenIndex = -1
        actionButton.text = "ATUR KAMERA"
        cells[index].requestFocus()
    }

    private fun showCameraManager() {
        val entries = (0..3).map { index ->
            "${cameraNames[index]}  •  " +
                if (cameraUrls[index].isBlank()) "Belum diatur" else "RTSP tersimpan"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Atur Kamera CCTV")
            .setItems(entries) { _, which -> showCameraEditor(which) }
            .setNegativeButton("Kembali", null)
            .show()
    }

    private fun showCameraEditor(index: Int) {
        val currentUrl = cameraUrls[index]
        val parsed = try { android.net.Uri.parse(currentUrl) } catch (_: Exception) { null }
        val userInfo = try {
            parsed?.encodedUserInfo?.let { android.net.Uri.decode(it) }.orEmpty()
        } catch (_: Exception) { "" }
        val credentials = userInfo.split(":", limit = 2)
        val currentUser = credentials.getOrNull(0).orEmpty()
        val currentPass = credentials.getOrNull(1).orEmpty()
        val currentHost = parsed?.host.orEmpty()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(4))
        }

        fun addField(title: String, hintText: String, initial: String, password: Boolean = false): EditText {
            form.addView(label(title, 12, Color.rgb(185, 199, 219), true).apply {
                setPadding(0, dp(8), 0, dp(2))
            })
            val field = EditText(this).apply {
                hint = hintText
                setSingleLine(true)
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                setText(initial)
                setSelection(text.length)
            }
            form.addView(field, LinearLayout.LayoutParams(-1, -2))
            return field
        }

        form.addView(label("Nama kamera", 12, Color.rgb(185, 199, 219), true))
        val nameInput = EditText(this).apply {
            hint = "Contoh: Pintu depan"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(cameraNames[index])
            setSelection(text.length)
        }
        form.addView(nameInput, LinearLayout.LayoutParams(-1, -2))

        val userInput = addField("Username RTSP", "user", currentUser)
        val passInput = addField("Password RTSP", "pass", currentPass, password = true)
        val ipInput = addField("IP kamera", "192.168.1.9", currentHost)

        form.addView(label("Format otomatis: rtsp://user:pass@IP:8554/Streaming/Channels/102", 10,
            Color.rgb(145, 169, 199), false).apply { setPadding(0, dp(8), 0, 0) })

        AlertDialog.Builder(this)
            .setTitle("Kamera ${index + 1}")
            .setView(form)
            .setPositiveButton("Simpan") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { "Kamera ${index + 1}" }
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                val host = ipInput.text.toString().trim()
                if (user.isBlank() || pass.isBlank() || host.isBlank()) {
                    toast("Isi username, password, dan IP kamera.")
                    return@setPositiveButton
                }
                if (host.contains("/") || host.contains("@") || host.contains(" ")) {
                    toast("IP/host tidak valid. Masukkan IP saja, tanpa rtsp:// atau path.")
                    return@setPositiveButton
                }
                val url = "rtsp://${android.net.Uri.encode(user)}:${android.net.Uri.encode(pass)}@${host}:8554/Streaming/Channels/102"
                saveCamera(index, name, url)
            }
            .setNeutralButton("Hapus URL") { _, _ ->
                saveCamera(index, nameInput.text.toString().trim().ifBlank { "Kamera ${index + 1}" }, "")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun saveCamera(index: Int, name: String, url: String) {
        cameraNames[index] = name
        cameraUrls[index] = url
        getSharedPreferences("glass_tv", MODE_PRIVATE).edit()
            .putString("camera_name_${index + 1}", name)
            .putString("camera_${index + 1}", url)
            .apply()
        nameLabels[index].text = name
        cells[index].contentDescription = "$name, kamera ${index + 1}"
        releasePlayer(index)
        statusLabels[index].text = if (url.isBlank()) "BELUM DIATUR  •  OK UNTUK SETUP" else "MENYIAPKAN STREAM…"
        statusLabels[index].setTextColor(if (url.isBlank()) Color.rgb(220, 205, 154) else accent)
        if (activityStarted) startPlayer(index)
        toast("Pengaturan ${name} disimpan di TV")
    }

    private fun actionBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (focused) Color.rgb(38, 58, 88) else Color.rgb(22, 32, 48))
        setStroke(if (focused) dp(2) else dp(1), if (focused) accent else Color.argb(145, 126, 190, 255))
    }

    private fun focusBorder(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(if (focused) dp(3) else dp(1), if (focused) accent else Color.argb(125, 126, 190, 255))
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (fullscreenIndex >= 0) {
            returnToGrid()
        } else {
            super.onBackPressed()
        }
    }
}

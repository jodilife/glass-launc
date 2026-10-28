package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 22)
    private val accent = Color.rgb(126, 190, 255)
    private lateinit var root: LinearLayout
    private val actions = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showHome()
    }

    private fun showHome() {
        actions.clear()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(38), dp(24), dp(38), dp(26))
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
            setPadding(0, dp(4), 0, dp(18))
        }
        root.addView(subtitle)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
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
        hero.addView(label("Selamat datang kembali", 28, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        hero.addView(label(
            "Pilih aplikasi, buka kamera CCTV, atau putar video dari URL.\nPemutaran menggunakan aplikasi eksternal yang kompatibel.",
            15, Color.rgb(202, 214, 231), false
        ).apply {
            maxLines = 3
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = dp(18)
        })

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        section.addView(label("AKSES CEPAT", 12, Color.rgb(160, 178, 201), true))
        section.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        section.addView(label("Pilih dengan remote  •  OK untuk membuka", 11, Color.rgb(133, 151, 174), false))
        root.addView(section, LinearLayout.LayoutParams(-1, dp(24)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            clipChildren = false
            clipToPadding = false
        }
        addAction(row, "Live TV", "Siaran antena") { openLiveTv() }
        addAction(row, "Entertainment", "Aplikasi TV & musik") { openApps() }
        addAction(row, "Kamera CCTV", "4 slot kamera • RTSP") { showCameras() }
        addAction(row, "Sumber video", "Buka URL HTTP(S)") { askVideoUrl() }
        addAction(row, "Pengaturan", "Info launcher") { showSettings() }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(96)))
        setContentView(root)
        if (actions.isNotEmpty()) root.post { actions.firstOrNull()?.requestFocus() }
    }

    private fun addAction(row: LinearLayout, title: String, desc: String, click: () -> Unit) {
        val cornerRadius = dp(14).toFloat()

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
        card.setPadding(dp(18), dp(14), dp(14), dp(14))
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

        card.addView(label(title, 18, Color.WHITE, true))
        card.addView(label(desc, 12, Color.rgb(200, 215, 235), false))

        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        lp.setMargins(0, 0, dp(12), 0)
        row.addView(card, lp)
        actions.add(card)
    }

    private val defaultCameraUrls = listOf(
        "rtsp://admin:admin@192.168.1.8:8554/Streaming/Channels/101",
        "rtsp://admin:admin@192.168.1.8:8554/Streaming/Channels/102",
        "rtsp://admin:admin@192.168.1.9:8554/Streaming/Channels/101",
        "rtsp://admin:admin@192.168.1.9:8554/Streaming/Channels/102"
    )

    private fun showCameras() {
        val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
        // Seed the four known working RTSP addresses on first launch (and migrate
        // empty slots), so users do not have to type them manually on the TV.
        val editor = prefs.edit()
        for (number in 1..4) {
            if (prefs.getString("camera_$number", null).isNullOrBlank()) {
                editor.putString("camera_$number", defaultCameraUrls[number - 1])
            }
        }
        editor.apply()

        val items = (1..4).map { number ->
            val configured = !prefs.getString("camera_$number", defaultCameraUrls[number - 1]).isNullOrBlank()
            val cameraName = prefs.getString("camera_name_$number", "Kamera $number")
                ?.takeIf { it.isNotBlank() } ?: "Kamera $number"
            "$cameraName  •  " + if (configured) "URL tersimpan" else "Belum dikonfigurasi"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kamera CCTV")
            .setItems(items) { _, which -> manageCamera(which + 1) }
            .setNegativeButton("Kembali", null)
            .show()
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
        val packageName = "com.mitv.livetv"
        try {
            // Prefer the TV manufacturer's own Live TV application so tuner/channel
            // setup remains handled by the OEM service.
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }

            // Some OEM TV apps expose a Leanback launcher activity without a
            // conventional getLaunchIntentForPackage result.
            val leanbackIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(packageName)
            if (leanbackIntent.resolveActivity(packageManager) != null) {
                startActivity(leanbackIntent)
            } else {
                toast("Aplikasi Live TV bawaan tidak ditemukan. Periksa apakah com.mitv.livetv masih terpasang.")
            }
        } catch (_: Exception) {
            toast("Live TV tidak dapat dibuka. Coba buka aplikasi TV bawaan dari menu sistem.")
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
                "Versi 0.2.0 (Entertainment Dashboard)\n\n" +
                "• Target: Android TV 11\n" +
                "• Live TV: membuka aplikasi tuner bawaan Xiaomi/MediaTek\n" +
                "• Entertainment: daftar aplikasi TV yang terpasang dan dapat diluncurkan\n" +
                "• Kamera: 4 URL RTSP tersimpan lokal; stream via pemutar eksternal\n" +
                "• Video: membuka URL HTTP(S) melalui pemutar yang kompatibel\n\n" +
                "Preview video langsung di dalam kartu belum diaktifkan. Uji tombol OK/Back di TV sebelum menjadikan launcher default."
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

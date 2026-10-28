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
            setPadding(dp(34), dp(24), dp(34), dp(24))
            setBackgroundColor(bg)
            isFocusableInTouchMode = true
        }

        // Header Layout
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("GLASS", 30, Color.WHITE, true))
        header.addView(label("TV", 30, accent, true))
        val spacer = View(this)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val time = SimpleDateFormat("EEE, dd MMM  •  HH:mm", Locale.getDefault()).format(Date())
        header.addView(label(time, 17, Color.LTGRAY, false))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        // Subtitle Layout
        val subtitle = label("Your screen. Your space.", 16, Color.rgb(185, 199, 219), false)
        subtitle.setPadding(0, dp(2), 0, dp(18))
        root.addView(subtitle)

        // Hero Content Layout
        val hero = TextView(this).apply {
            text = "LIVE BACKGROUND  •  GLASS INTERFACE\n\nWelcome home\n\nPilih sumber video atau buka kamera untuk memulai. Akses tuner TV internal perlu diuji pada firmware MiTV."
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(18), dp(26), dp(18))
            setBackgroundColor(Color.rgb(19, 29, 44))
            isFocusable = false
        }
        root.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))

        // Action Buttons Row Layout
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        addAction(row, "Aplikasi", "Buka daftar aplikasi") { openApps() }
        addAction(row, "Kamera CCTV", "RTSP / ONVIF") { showCameras() }
        addAction(row, "Sumber video", "Pilih URL video") { askVideoUrl() }
        addAction(row, "Pengaturan", "Informasi & konfigurasi") { showSettings() }
        
        root.addView(row, LinearLayout.LayoutParams(-1, dp(110)))
        setContentView(root)

        // Alihkan fokus awal ke kartu pertama agar remote langsung merespons
        if (actions.isNotEmpty()) {
            actions[0].requestFocus()
        }
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

    private fun showCameras() {
        val items = arrayOf("Kamera 1 — masukkan URL RTSP", "Kamera 2 — masukkan URL RTSP", "Kamera 3 — masukkan URL RTSP", "Kamera 4 — masukkan URL RTSP")
        AlertDialog.Builder(this)
            .setTitle("Kamera CCTV")
            .setItems(items) { _, which -> askRtsp(which + 1) }
            .setNegativeButton("Kembali", null)
            .show()
    }

    private fun askRtsp(number: Int) {
        val input = EditText(this).apply {
            hint = "rtsp://alamat-kamera/stream"
            setSingleLine(true)
            setText("rtsp://")
        }
        AlertDialog.Builder(this)
            .setTitle("URL Kamera $number")
            .setMessage("Untuk prototipe ini, stream dibuka melalui aplikasi pemutar eksternal yang mendukung RTSP (mis. VLC).")
            .setView(input)
            .setPositiveButton("Buka") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("rtsp://") || url.startsWith("rtsps://")) openExternalStream(url)
                else toast("URL harus diawali rtsp:// atau rtsps://")
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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            toast("Tidak ada aplikasi yang dapat membuka stream. Pasang pemutar yang kompatibel.")
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
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(info.packageName, info.name))
            }.setNegativeButton("Tutup", null).show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("GlassTV Launcher — prototipe")
            .setMessage(
                "Versi 0.1.2 (Glass Border Fix)\n\n" +
                "• Target: Android TV 11\n" +
                "• Navigasi remote: fokus D-pad aktif\n" +
                "• Kamera: membuka stream melalui pemutar eksternal\n" +
                "• Live TV internal: belum terintegrasi; perlu inventarisasi perangkat/vendor API\n\n" +
                "Jangan jadikan launcher default sebelum pengujian ADB dan tombol Home berhasil."
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

package id.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 22)
    private val panel = Color.argb(205, 28, 38, 55)
    private val accent = Color.rgb(126, 190, 255)
    private lateinit var root: LinearLayout
    private var focusIndex = 0
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

        val subtitle = label("Your screen. Your space.", 16, Color.rgb(185, 199, 219), false)
        subtitle.setPadding(0, dp(2), 0, dp(18))
        root.addView(subtitle)

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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setBackgroundColor(panel)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener { click() }
            setOnFocusChangeListener { v, focused ->
                v.setBackgroundColor(if (focused) Color.rgb(48, 76, 108) else panel)
                v.scaleX = if (focused) 1.05f else 1.0f
                v.scaleY = if (focused) 1.05f else 1.0f
            }
            // Mendukung tombol OK / Enter pada remote TV fisik
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    click()
                    true
                } else {
                    false
                }
            }
        }
        card.addView(label(title, 18, Color.WHITE, true))
        card.addView(label(desc, 12, Color.LTGRAY, false))
        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        lp.setMargins(0, 0, dp(10), 0)
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
        val input = android.widget.EditText(this).apply {
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
        val input = android.widget.EditText(this).apply {
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
                "Versi 0.1.0\n\n" +
                "• Target: Android TV 11\n" +
                "• Navigasi remote: fokus D-pad dasar\n" +
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

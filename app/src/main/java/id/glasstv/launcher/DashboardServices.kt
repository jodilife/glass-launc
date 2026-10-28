package id.glasstv.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/**
 * Small dashboard data source. Location is explicitly selected by the user; no device location
 * permission is requested. Weather is from Open-Meteo; prayer times use AlAdhan's Singapore
 * calculation (Fajr 20°, Isha 18°) with a two-minute precaution adjustment.
 */
internal object DashboardServices {
    data class City(val name: String, val lat: Double, val lon: Double)
    data class Snapshot(
        val city: String,
        val temperature: String,
        val condition: String,
        val prayerNames: List<String>,
        val prayerTimes: List<String>,
        val fetchedAt: Long
    )

    val cities = listOf(
        City("Jakarta Timur", -6.2250, 106.9000),
        City("Jakarta Pusat", -6.1862, 106.8341),
        City("Jakarta Selatan", -6.2615, 106.8106),
        City("Jakarta Barat", -6.1683, 106.7588),
        City("Jakarta Utara", -6.1384, 106.8639),
        City("Bekasi", -6.2383, 106.9756),
        City("Depok", -6.4025, 106.7942),
        City("Bogor", -6.5971, 106.8060),
        City("Bandung", -6.9175, 107.6191),
        City("Semarang", -6.9667, 110.4167),
        City("Yogyakarta", -7.7956, 110.3695),
        City("Surabaya", -7.2575, 112.7521),
        City("Medan", 3.5952, 98.6722),
        City("Makassar", -5.1477, 119.4327),
        City("Denpasar", -8.6705, 115.2126),
        City("Palembang", -2.9761, 104.7754),
        City("Padang", -0.9471, 100.4172),
        City("Pontianak", -0.0263, 109.3425),
        City("Balikpapan", -1.2379, 116.8529),
        City("Manado", 1.4748, 124.8421)
    )

    private val prayerKeys = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
    private val prayerNames = listOf("Subuh", "Syuruk", "Zuhur", "Asar", "Maghrib", "Isya")

    fun selectedCity(context: Context): City {
        val prefs = context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
        val name = prefs.getString("dashboard_city", "Jakarta Timur") ?: "Jakarta Timur"
        return cities.firstOrNull { it.name == name } ?: cities.first()
    }

    fun selectCity(context: Context, city: City) {
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE).edit()
            .putString("dashboard_city", city.name).apply()
        context.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun cached(context: Context): Snapshot? {
        val p = context.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE)
        val city = p.getString("city", null) ?: return null
        val temp = p.getString("temperature", "") ?: ""
        val condition = p.getString("condition", "") ?: ""
        val times = prayerKeys.map { p.getString("prayer_$it", "") ?: "" }
        if (times.any { !it.matches(Regex("\\d{2}:\\d{2}")) }) return null
        return Snapshot(city, temp, condition, prayerNames, times, p.getLong("fetched", 0L))
    }

    fun fetch(context: Context, callback: (Snapshot?, String?) -> Unit) {
        val app = context.applicationContext
        val city = selectedCity(app)
        Thread({
            try {
                val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}&current=temperature_2m,weather_code&timezone=Asia%2FJakarta"
                val weather = JSONObject(get(weatherUrl)).getJSONObject("current")
                val temp = "${weather.optDouble("temperature_2m").toInt()}°C"
                val condition = describeWeather(weather.optInt("weather_code"))
                val timestamp = System.currentTimeMillis() / 1000L
                val prayerUrl = "https://api.aladhan.com/v1/timings/$timestamp?latitude=${city.lat}&longitude=${city.lon}&method=20&timezonestring=Asia%2FJakarta&tune=2,0,2,2,0,2,2,0,0"
                val prayer = JSONObject(get(prayerUrl)).getJSONObject("data")
                    .getJSONObject("timings")
                val times = prayerKeys.map { key ->
                    val raw = prayer.optString(key).substringBefore(" ").trim()
                    if (!raw.matches(Regex("\\d{2}:\\d{2}"))) throw IllegalStateException("Format waktu $key tidak valid")
                    raw
                }
                val snapshot = Snapshot(city.name, temp, condition, prayerNames, times, System.currentTimeMillis())
                val prefs = app.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE).edit()
                    .putString("city", snapshot.city)
                    .putString("temperature", snapshot.temperature)
                    .putString("condition", snapshot.condition)
                    .putLong("fetched", snapshot.fetchedAt)
                prayerKeys.forEachIndexed { i, key -> prefs.putString("prayer_$key", times[i]) }
                prefs.apply()
                Handler(Looper.getMainLooper()).post { callback(snapshot, null) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { callback(cached(app), e.message ?: "Data belum tersedia") }
            }
        }, "glasstv-dashboard-data").start()
    }

    fun nextPrayer(snapshot: Snapshot, now: Calendar = Calendar.getInstance()): Pair<String, String> {
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        for (i in snapshot.prayerTimes.indices) {
            val parts = snapshot.prayerTimes[i].split(":")
            val value = parts[0].toInt() * 60 + parts[1].toInt()
            if (value > minute && i != 1) return snapshot.prayerNames[i] to snapshot.prayerTimes[i]
        }
        return snapshot.prayerNames[0] to snapshot.prayerTimes[0] // tomorrow's Subuh is the next prayer after Isya
    }

    fun duePrayer(snapshot: Snapshot, now: Calendar = Calendar.getInstance()): Int {
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        for (i in snapshot.prayerTimes.indices) {
            if (i == 1) continue // Syuruk is displayed but does not have an azan.
            val parts = snapshot.prayerTimes[i].split(":")
            if (parts[0].toInt() * 60 + parts[1].toInt() == minute) return i
        }
        return -1
    }

    private fun describeWeather(code: Int): String = when (code) {
        0 -> "Cerah"
        1 -> "Cerah berawan"
        2 -> "Berawan sebagian"
        3 -> "Mendung"
        45, 48 -> "Berkabut"
        in 51..57 -> "Gerimis"
        in 61..67 -> "Hujan"
        in 71..77 -> "Salju"
        in 80..82 -> "Hujan lokal"
        in 95..99 -> "Badai petir"
        else -> "Cuaca terkini"
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "GlassTVLauncher/0.4")
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

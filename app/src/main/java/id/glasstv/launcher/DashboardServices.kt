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
import java.util.TimeZone

/**
 * Small dashboard data source. Weather forecasts are provided by BMKG at kelurahan level.
 * Location is explicitly selected by the user; no device location permission is requested.
 * Prayer times use AlAdhan's Singapore calculation (Fajr 20°, Isha 18°) with a two-minute
 * precaution adjustment.
 */
internal object DashboardServices {
    data class City(
        val name: String,
        val lat: Double,
        val lon: Double,
        val bmkgCode: String = "",
        val timeZone: String = "Asia/Jakarta"
    )
    data class Snapshot(
        val city: String,
        val temperature: String,
        val condition: String,
        val prayerNames: List<String>,
        val prayerTimes: List<String>,
        val fetchedAt: Long,
        val timeZone: String = "Asia/Jakarta"
    )

    // BMKG adm4 codes correspond to kelurahan-level forecast pages.
    // Jakarta Timur options use BMKG kelurahan (adm4) codes.
    val cities = listOf(
        City("Pondok Kelapa", -6.2250, 106.9330, "31.75.07.1004"),
        City("Duren Sawit", -6.2290, 106.9160, "31.75.07.1001"),
        City("Pondok Bambu", -6.2290, 106.8910, "31.75.07.1002"),
        City("Klender", -6.2140, 106.9060, "31.75.07.1003"),
        City("Malaka Sari", -6.2180, 106.9280, "31.75.07.1005"),
        City("Malaka Jaya", -6.2130, 106.9280, "31.75.07.1006"),
        City("Pondok Kopi", -6.2140, 106.9380, "31.75.07.1007")
    )

    private val prayerKeys = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
    private val prayerNames = listOf("Subuh", "Syuruk", "Zuhur", "Asar", "Maghrib", "Isya")

    fun selectedCity(context: Context): City {
        val prefs = context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
        val name = prefs.getString("dashboard_city", "Pondok Kelapa") ?: "Pondok Kelapa"
        val preset = cities.firstOrNull { it.name == name }
        if (preset != null) return preset
        val lat = prefs.getString("dashboard_city_lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("dashboard_city_lon", null)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            val savedZone = prefs.getString("dashboard_city_timezone", null).orEmpty()
            val zone = resolveTimeZone(lat, lon, savedZone)
            return City(name, lat, lon, prefs.getString("dashboard_city_bmkg", "").orEmpty(), zone)
        }
        return cities.first()
    }

    fun selectCity(context: Context, city: City) {
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE).edit()
            .putString("dashboard_city", city.name)
            .putString("dashboard_city_lat", city.lat.toString())
            .putString("dashboard_city_lon", city.lon.toString())
            .putString("dashboard_city_bmkg", city.bmkgCode)
            .putString("dashboard_city_timezone", resolveTimeZone(city.lat, city.lon, city.timeZone))
            .apply()
        context.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun searchCities(context: Context, query: String, callback: (List<City>, String?) -> Unit) {
        Thread({
            try {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=12&language=id&format=json&countryCode=ID"))
                val results = json.optJSONArray("results")
                val found = mutableListOf<City>()
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val name = item.optString("name").trim()
                        val lat = item.optDouble("latitude", Double.NaN)
                        val lon = item.optDouble("longitude", Double.NaN)
                        if (name.isBlank() || !lat.isFinite() || !lon.isFinite()) continue
                        val region = item.optString("admin1").trim()
                        val displayName = if (region.isNotBlank() && !name.equals(region, true)) "$name, $region" else name
                        val requestedZone = item.optString("timezone", "")
                        val zone = resolveTimeZone(lat, lon, requestedZone)
                        found.add(City(displayName, lat, lon, "", zone))
                    }
                }
                Handler(Looper.getMainLooper()).post { callback(found.distinctBy { it.name }, null) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { callback(emptyList(), e.message ?: "Pencarian lokasi gagal") }
            }
        }, "glasstv-city-search").start()
    }

    fun cached(context: Context): Snapshot? {
        val p = context.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE)
        val city = p.getString("city", null) ?: return null
        val temp = p.getString("temperature", "") ?: ""
        val condition = p.getString("condition", "") ?: ""
        val times = prayerKeys.map { p.getString("prayer_$it", "") ?: "" }
        if (times.any { !it.matches(Regex("\\d{2}:\\d{2}")) }) return null
        val zone = p.getString("timezone", "Asia/Jakarta").orEmpty()
        return Snapshot(city, temp, condition, prayerNames, times, p.getLong("fetched", 0L), zone)
    }

    fun fetch(context: Context, callback: (Snapshot?, String?) -> Unit) {
        val app = context.applicationContext
        val city = selectedCity(app)
        Thread({
            try {
                val temp: String
                val condition: String
                if (city.bmkgCode.isNotBlank()) {
                    val weatherUrl = "https://api.bmkg.go.id/publik/prakiraan-cuaca?adm4=${city.bmkgCode}"
                    val weather = latestBmkgForecast(JSONObject(get(weatherUrl)))
                    temp = "${weather.optInt("t", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { "$it°C" } ?: "Suhu --"}"
                    condition = weather.optString("weather_desc", "").takeIf { it.isNotBlank() }
                        ?: weather.optString("weather_desc_en", "").takeIf { it.isNotBlank() }
                        ?: "Cuaca terkini"
                } else {
                    val weatherZone = URLEncoder.encode(city.timeZone, "UTF-8")
                    val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}&current=temperature_2m,weather_code&timezone=$weatherZone"
                    val current = JSONObject(get(weatherUrl)).getJSONObject("current")
                    val degrees = current.optDouble("temperature_2m", Double.NaN)
                    temp = if (degrees.isFinite()) "${degrees.toInt()}°C" else "Suhu --"
                    condition = weatherDescription(current.optInt("weather_code", -1))
                }
                val timestamp = System.currentTimeMillis() / 1000L
                val prayerZone = URLEncoder.encode(city.timeZone, "UTF-8")
                val prayerUrl = "https://api.aladhan.com/v1/timings/$timestamp?latitude=${city.lat}&longitude=${city.lon}&method=20&timezonestring=$prayerZone&tune=0,2,0,2,2,2,0,2,0"
                val prayer = JSONObject(get(prayerUrl)).getJSONObject("data")
                    .getJSONObject("timings")
                val times = prayerKeys.map { key ->
                    val raw = prayer.optString(key).substringBefore(" ").trim()
                    if (!raw.matches(Regex("\\d{2}:\\d{2}"))) throw IllegalStateException("Format waktu $key tidak valid")
                    raw
                }
                val snapshot = Snapshot(city.name, temp, condition, prayerNames, times, System.currentTimeMillis(), city.timeZone)
                val prefs = app.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE).edit()
                    .putString("city", snapshot.city)
                    .putString("temperature", snapshot.temperature)
                    .putString("condition", snapshot.condition)
                    .putString("timezone", snapshot.timeZone)
                    .putLong("fetched", snapshot.fetchedAt)
                prayerKeys.forEachIndexed { i, key -> prefs.putString("prayer_$key", times[i]) }
                prefs.apply()
                Handler(Looper.getMainLooper()).post { callback(snapshot, null) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { callback(cached(app), e.message ?: "Data belum tersedia") }
            }
        }, "glasstv-dashboard-data").start()
    }

    fun nextPrayer(snapshot: Snapshot, now: Calendar? = null): Pair<String, String> {
        val current = now ?: Calendar.getInstance(TimeZone.getTimeZone(snapshot.timeZone))
        val minute = current.get(Calendar.HOUR_OF_DAY) * 60 + current.get(Calendar.MINUTE)
        for (i in snapshot.prayerTimes.indices) {
            val parts = snapshot.prayerTimes[i].split(":")
            val value = parts[0].toInt() * 60 + parts[1].toInt()
            if (value > minute && i != 1) return snapshot.prayerNames[i] to snapshot.prayerTimes[i]
        }
        return snapshot.prayerNames[0] to snapshot.prayerTimes[0] // tomorrow's Subuh is the next prayer after Isya
    }

    fun duePrayer(snapshot: Snapshot, now: Calendar? = null): Int {
        val current = now ?: Calendar.getInstance(TimeZone.getTimeZone(snapshot.timeZone))
        val minute = current.get(Calendar.HOUR_OF_DAY) * 60 + current.get(Calendar.MINUTE)
        for (i in snapshot.prayerTimes.indices) {
            if (i == 1) continue // Syuruk is displayed but does not have an azan.
            val parts = snapshot.prayerTimes[i].split(":")
            if (parts[0].toInt() * 60 + parts[1].toInt() == minute) return i
        }
        return -1
    }

    private fun resolveTimeZone(lat: Double, lon: Double, suggested: String): String {
        if (suggested.isNotBlank()) {
            try {
                if (suggested in TimeZone.getAvailableIDs()) return suggested
            } catch (_: Exception) { }
        }
        if (lat in -11.5..6.5 && lon in 94.0..141.5) {
            return when {
                lon >= 130.0 -> "Asia/Jayapura"
                lon >= 114.5 -> "Asia/Makassar"
                else -> "Asia/Jakarta"
            }
        }
        return TimeZone.getDefault().id
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Cerah"
        1 -> "Cerah berawan"
        2 -> "Berawan sebagian"
        3 -> "Mendung"
        45, 48 -> "Berkabut"
        51, 53, 55 -> "Gerimis"
        56, 57 -> "Gerimis membeku"
        61, 63, 65 -> "Hujan"
        66, 67 -> "Hujan membeku"
        71, 73, 75, 77 -> "Salju"
        80, 81, 82 -> "Hujan sesaat"
        85, 86 -> "Hujan salju"
        95, 96, 99 -> "Badai petir"
        else -> "Cuaca terkini"
    }

    private fun latestBmkgForecast(root: JSONObject): JSONObject {
        val data = root.optJSONArray("data") ?: throw IllegalStateException("Format data BMKG tidak dikenal")
        val area = data.optJSONObject(0) ?: throw IllegalStateException("Prakiraan BMKG kosong")
        val days = area.optJSONArray("cuaca") ?: throw IllegalStateException("Data cuaca BMKG tidak tersedia")
        val forecasts = mutableListOf<JSONObject>()
        for (dayIndex in 0 until days.length()) {
            val day = days.optJSONArray(dayIndex)
            if (day != null) {
                for (itemIndex in 0 until day.length()) {
                    day.optJSONObject(itemIndex)?.let { forecasts.add(it) }
                }
            } else {
                days.optJSONObject(dayIndex)?.let { forecasts.add(it) }
            }
        }
        if (forecasts.isEmpty()) throw IllegalStateException("Prakiraan BMKG kosong")
        val now = System.currentTimeMillis()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        }
        return forecasts.minByOrNull { forecast ->
            val localDate = forecast.optString("local_datetime", "")
            val parsed = try { format.parse(localDate)?.time } catch (_: Exception) { null }
            if (parsed == null) Long.MAX_VALUE else kotlin.math.abs(parsed - now)
        } ?: forecasts.first()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "GlassTVLauncher/0.5")
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

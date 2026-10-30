package id.glasstv.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Downloads a weather-matched MP4 from MotionBGs into the app cache. */
internal object MotionBgsWeatherWallpaper {
    data class Result(val file: File, val title: String, val category: String)

    fun categoryFor(condition: String, localHour: Int): String {
        val value = condition.lowercase(Locale.ROOT)
        if (listOf("hujan", "gerimis", "petir", "badai", "rain", "storm", "thunder").any { value.contains(it) }) return "rain"
        if (listOf("kabut", "fog", "mist", "asap").any { value.contains(it) }) return "forest"
        if (localHour >= 18 || localHour < 6) return "night"
        if (listOf("mendung", "berawan", "awan", "cloud", "overcast").any { value.contains(it) }) return "forest"
        if (listOf("cerah", "clear", "sunny", "panas").any { value.contains(it) }) return "sunset"
        return "landscape"
    }

    fun download(context: Context, category: String, callback: (Result?, String?) -> Unit) {
        val app = context.applicationContext
        Thread({
            var connection: HttpURLConnection? = null
            try {
                val categoryUrl = "https://motionbgs.com/tag:" + category + "/"
                val categoryHtml = request(categoryUrl, null)
                val anchorRegex = Regex("""(?is)<a\b([^>]*)href=["']([^"'#?]+)["']([^>]*)>(.*?)</a>""")
                val candidates = anchorRegex.findAll(categoryHtml).mapNotNull { match ->
                    val attrs = match.groupValues[1] + " " + match.groupValues[3] + " " + match.groupValues[4]
                    val rawHref = match.groupValues[2]
                    val path = when {
                        rawHref.startsWith("https://motionbgs.com/") -> rawHref.removePrefix("https://motionbgs.com")
                        rawHref.startsWith("/") -> rawHref
                        else -> return@mapNotNull null
                    }.substringBefore("?").substringBefore("#")
                    if (!path.matches(Regex("""/[a-z0-9-]+""")) || path in listOf("/mobile", "/4k", "/gifs", "/guides")) return@mapNotNull null
                    val title = (Regex("""(?i)(?:title|alt)=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
                        ?: match.groupValues[4].replace(Regex("<[^>]+>"), " "))
                        .replace("&amp;", "&").replace("&#039;", "'").trim()
                    if (title.isBlank() || title.equals(category, true)) return@mapNotNull null
                    path to title
                }.distinctBy { it.first }.take(12).toList()
                if (candidates.isEmpty()) throw IllegalStateException("Halaman kategori MotionBGS tidak memuat daftar wallpaper.")
                val selected = candidates.firstOrNull { it.second.contains("wallpaper", true) || it.second.contains("4K", true) } ?: candidates.first()
                val detailUrl = "https://motionbgs.com" + selected.first
                val detailHtml = request(detailUrl, categoryUrl)
                // MotionBGS currently exposes numeric download URLs such as /dl/hd/9963,
                // not necessarily .mp4 filenames. Collect both HD and 4K links, then try each.
                val downloadCandidates = Regex("""(?i)(/dl/(?:hd|4k)/[A-Za-z0-9_-]+)""")
                    .findAll(detailHtml).map { it.groupValues[1] }.distinct().toList()
                if (downloadCandidates.isEmpty()) {
                    throw IllegalStateException("Tautan unduhan HD/4K tidak ditemukan di halaman MotionBGS.")
                }
                val destination = File(app.cacheDir, "glasstv_motionbgs_weather.mp4")
                val pending = File(app.cacheDir, "glasstv_motionbgs_weather.pending")
                var downloaded = false
                var lastDownloadError = "Tidak ada tautan MP4 yang dapat diunduh."
                for (candidate in downloadCandidates) {
                    val downloadUrl = "https://motionbgs.com$candidate"
                    try {
                        connection = URL(downloadUrl).openConnection() as HttpURLConnection
                        connection.connectTimeout = 15000
                        connection.readTimeout = 60000
                        connection.instanceFollowRedirects = true
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                        connection.setRequestProperty("Referer", detailUrl)
                        connection.setRequestProperty("Accept", "video/mp4,application/octet-stream,*/*")
                        val responseCode = connection.responseCode
                        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
                        pending.delete()
                        connection.inputStream.use { input -> pending.outputStream().use { output -> input.copyTo(output) } }
                        if (pending.length() < 100_000L) throw IllegalStateException("Berkas terlalu kecil (${pending.length()} byte)")
                        val header = ByteArray(12)
                        pending.inputStream().use { it.read(header) }
                        if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") throw IllegalStateException("Respons bukan berkas MP4")
                        if (destination.exists()) destination.delete()
                        if (!pending.renameTo(destination)) pending.copyTo(destination, overwrite = true)
                        pending.delete()
                        downloaded = true
                        break
                    } catch (downloadError: Exception) {
                        lastDownloadError = "$downloadUrl: ${downloadError.message}"
                        pending.delete()
                    } finally {
                        connection?.disconnect()
                        connection = null
                    }
                }
                if (!downloaded) throw IllegalStateException("MotionBGS tidak berhasil mengirim video. $lastDownloadError")
                val result = Result(destination, selected.second, category)
                Handler(Looper.getMainLooper()).post { callback(result, null) }
            } catch (error: Exception) {
                Handler(Looper.getMainLooper()).post { callback(null, error.message ?: "Unduhan MotionBGS gagal") }
            } finally {
                connection?.disconnect()
            }
        }, "glasstv-motionbgs-weather").start()
    }

    private fun request(url: String, referer: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        if (referer != null) connection.setRequestProperty("Referer", referer)
        return try {
            if (connection.responseCode != 200) throw IllegalStateException("MotionBGS HTTP " + connection.responseCode)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

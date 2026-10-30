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
                // MotionBGS markup can vary between href, data attributes and video/source tags.
                // Accept only the site's own download paths and MP4 links.
                val downloadPatterns = listOf(
                    Regex("""(?i)(?:href|src|data-src|data-url)=["'](/dl/(?:hd|4k)/[^"'?#]+)["']"""),
                    Regex("""(?i)(?:href|src|data-src)=["'](https?://motionbgs\\.com/dl/(?:hd|4k)/[^"'?#]+)["']""")
                )
                val rawDownload = downloadPatterns.asSequence()
                    .mapNotNull { it.find(detailHtml)?.groupValues?.get(1) }
                    .firstOrNull()
                    ?: Regex("""(?i)(https?://motionbgs\\.com/dl/(?:hd|4k)/[^"'\\s<>]+\\.mp4)""")
                        .find(detailHtml)?.groupValues?.get(1)
                    ?: throw IllegalStateException("MotionBGS membuka halaman wallpaper, tetapi tautan MP4 tidak ditemukan (struktur halaman mungkin berubah).")
                val downloadPath = if (rawDownload.startsWith("https://motionbgs.com/"))
                    rawDownload.removePrefix("https://motionbgs.com") else rawDownload
                val destination = File(app.cacheDir, "glasstv_motionbgs_weather.mp4")
                val pending = File(app.cacheDir, "glasstv_motionbgs_weather.pending")
                connection = URL("https://motionbgs.com" + downloadPath).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 45000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                connection.setRequestProperty("Referer", detailUrl)
                connection.setRequestProperty("Accept", "video/mp4,application/octet-stream,*/*")
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) throw IllegalStateException("Unduhan MotionBGS HTTP $responseCode")
                pending.delete()
                connection.inputStream.use { input -> pending.outputStream().use { output -> input.copyTo(output) } }
                if (pending.length() < 100_000L) throw IllegalStateException("File MP4 terlalu kecil atau akses ditolak.")
                val header = ByteArray(12)
                pending.inputStream().use { it.read(header) }
                if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") throw IllegalStateException("Respons MotionBGS bukan berkas MP4.")
                if (destination.exists()) destination.delete()
                if (!pending.renameTo(destination)) pending.copyTo(destination, overwrite = true)
                pending.delete()
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

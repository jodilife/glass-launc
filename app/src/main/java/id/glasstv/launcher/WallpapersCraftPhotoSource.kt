package id.glasstv.launcher

import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * WallpapersCraft downloader for Photo Mode.
 *
 * It follows the site's public catalogue -> wallpaper detail -> 1920x1080 download page
 * flow and stores validated image files in the app cache. The cache is not a mirror of
 * the site; downloads are limited to a small batch and are only used on this device.
 */
object WallpapersCraftPhotoSource {
    private const val BASE = "https://wallpaperscraft.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; GlassTV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
    private const val MAX_IMAGE_BYTES = 18L * 1024L * 1024L

    fun downloadBatch(
        category: String,
        destination: File,
        limit: Int = 8,
        onProgress: (String) -> Unit = {}
    ): Int {
        destination.mkdirs()
        val catalogueUrl = if (category.isBlank()) BASE else "$BASE/catalog/$category"
        onProgress("Membaca katalog $category…")
        val catalogue = fetchText(catalogueUrl)
        val detailPaths = Regex("href=[\\\"']([^\\\"']*/wallpaper/[a-zA-Z0-9_/-]+)[\\\"']", RegexOption.IGNORE_CASE)
            .findAll(catalogue)
            .map { it.groupValues[1].substringBefore('?') }
            .map { if (it.startsWith("http")) it else BASE + if (it.startsWith("/")) it else "/$it" }
            .distinct()
            .take(limit * 3)
            .toList()
        if (detailPaths.isEmpty()) throw IllegalStateException("Daftar wallpaper tidak ditemukan di halaman katalog.")

        var saved = 0
        for ((index, detailUrl) in detailPaths.withIndex()) {
            if (saved >= limit) break
            try {
                onProgress("Memeriksa wallpaper ${index + 1}/${detailPaths.size}…")
                val detail = fetchText(detailUrl)
                val downloadPath = Regex("href=[\\\"']([^\\\"']*/download/[^\\\"']+/1920x1080)[\\\"']", RegexOption.IGNORE_CASE)
                    .find(detail)?.groupValues?.get(1) ?: continue
                val downloadPageUrl = if (downloadPath.startsWith("http")) downloadPath else BASE + downloadPath
                val downloadPage = fetchText(downloadPageUrl)
                val imageUrl = Regex("(?:https?:)?//images\\.wallpaperscraft\\.com/[^\\\"'<> ]+", RegexOption.IGNORE_CASE)
                    .find(downloadPage)?.value?.let {
                        if (it.startsWith("//")) "https:$it" else it
                    } ?: continue

                val id = detailUrl.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_-]"), "")
                val output = File(destination, "wallpaperscraft_$id.jpg")
                if (output.exists() && output.length() > 20_000L) continue
                val bytes = fetchBytes(imageUrl)
                if (bytes.size < 20_000 || bytes.size.toLong() > MAX_IMAGE_BYTES) continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth < 1280 || bounds.outHeight < 720) continue
                val temp = File(destination, "${output.name}.part")
                temp.writeBytes(bytes)
                if (output.exists()) output.delete()
                if (!temp.renameTo(output)) {
                    temp.copyTo(output, overwrite = true)
                    temp.delete()
                }
                saved++
                onProgress("Mengunduh foto $saved/$limit · ${bounds.outWidth}×${bounds.outHeight}")
            } catch (error: Exception) {
                android.util.Log.w("GlassTVPhotoMode", "Skip WallpapersCraft item $detailUrl: ${error.message}")
            }
        }
        return saved
    }

    private fun fetchText(address: String): String {
        val connection = open(address)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBytes(address: String): ByteArray {
        val connection = open(address)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code saat unduh gambar")
            val type = connection.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (type.isNotBlank() && !type.startsWith("image/")) {
                throw IllegalStateException("Respons bukan gambar ($type)")
            }
            val declared = connection.contentLengthLong
            if (declared > MAX_IMAGE_BYTES) throw IllegalStateException("Ukuran gambar melebihi batas 18 MB")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) throw IllegalStateException("Gambar melebihi batas 18 MB")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8")
            setRequestProperty("Referer", BASE + "/")
        }
}

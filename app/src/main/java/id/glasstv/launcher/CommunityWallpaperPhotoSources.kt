package id.glasstv.launcher

import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Small on-device downloaders for public wallpaper catalogues.
 * Files are cached locally and are not uploaded or redistributed by GlassTV.
 */
object HdWallpapersPhotoSource {
    private const val BASE = "https://www.hdwallpapers.net"
    private const val MAX_BYTES = 18L * 1024L * 1024L

    fun downloadBatch(category: String, destination: File, limit: Int = 8, onProgress: (String) -> Unit = {}): Int {
        destination.mkdirs()
        val listing = if (category.isBlank()) BASE else "$BASE/$category"
        val html = fetchText(listing, BASE)
        val details = Regex("href=[\\\"']([^\\\"']+\\.htm(?:l)?)[\\\"']", RegexOption.IGNORE_CASE)
            .findAll(html).map { absolute(it.groupValues[1]) }.filter { it.startsWith(BASE) }
            .filter { it.contains("/wallpaper-") || it.contains("-wallpaper-") }
            .distinct().take(limit * 4).toList()
        if (details.isEmpty()) throw IllegalStateException("Tidak menemukan daftar wallpaper di kategori HDwallpapers.net.")
        var saved = 0
        for ((index, detailUrl) in details.withIndex()) {
            if (saved >= limit) break
            try {
                onProgress("Memeriksa HDwallpapers.net ${index + 1}/${details.size}…")
                val detail = fetchText(detailUrl, listing)
                val resolution = Regex("href=[\\\"']([^\\\"']*(?:3840x2160|2560x1440|1920x1080)[^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
                    .find(detail)?.groupValues?.get(1)
                val resolutionHtml = if (resolution != null) fetchText(absolute(resolution), detailUrl) else detail
                val image = extractImage(resolutionHtml) ?: extractImage(detail) ?: continue
                val id = detailUrl.substringAfterLast('/').replace(Regex("[^A-Za-z0-9_-]"), "")
                if (saveImage(image, File(destination, "hdwallpapers_$id.jpg"), detailUrl)) {
                    saved++
                    onProgress("HDwallpapers.net: $saved/$limit foto tersimpan")
                }
            } catch (e: Exception) { Log.w("GlassTVPhotoMode", "HDwallpapers item skipped: ${e.message}") }
        }
        return saved
    }

    private fun extractImage(html: String): String? {
        val candidates = listOf(
            Regex("<meta[^>]+property=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+property=[\\\"']og:image", RegexOption.IGNORE_CASE),
            Regex("(?:https?:)?//(?:static\\d?\\.)?hdwallpapers\\.net/[^\\\"'<> ]+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
        )
        return candidates.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }?.let(::absolute)
    }

    private fun saveImage(address: String, output: File, referer: String): Boolean {
        if (output.exists() && output.length() > 20_000L) return false
        val bytes = fetchBytes(address, referer)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1280 || bounds.outHeight < 720) return false
        val temp = File(output.parentFile, output.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(output)) { temp.copyTo(output, true); temp.delete() }
        return true
    }

    private fun absolute(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> BASE + path
        else -> "$BASE/$path"
    }
    private fun fetchText(url: String, referer: String) = fetch(url, referer).toString(Charsets.UTF_8)
    private fun fetchBytes(url: String, referer: String) = fetch(url, referer).also {
        if (it.size < 20_000 || it.size.toLong() > MAX_BYTES) throw IllegalStateException("Ukuran gambar tidak valid")
    }
    private fun fetch(url: String, referer: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 25000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            c.setRequestProperty("Referer", referer)
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val type = c.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (type.isNotBlank() && !type.startsWith("image/") && !type.contains("html")) throw IllegalStateException("Tipe konten tidak didukung")
            val out = ByteArrayOutputStream(); val buffer = ByteArray(16384); var total = 0L
            c.inputStream.use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_BYTES) throw IllegalStateException("File terlalu besar"); out.write(buffer,0,n) } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }
}

object AlphaCodersPhotoSource {
    private const val BASE = "https://alphacoders.com"
    private const val WALL = "https://wall.alphacoders.com"
    private const val MAX_BYTES = 18L * 1024L * 1024L

    fun downloadBatch(category: String, destination: File, limit: Int = 8, onProgress: (String) -> Unit = {}): Int {
        destination.mkdirs()
        val listing = if (category.isBlank()) "$BASE/" else "$BASE/tag/$category"
        onProgress("Membaca katalog Alpha Coders…")
        val html = fetchText(listing, BASE)
        val detailUrls = Regex("href=[\\\"']([^\\\"']*(?:big\\.php\\?i=|wallpaper/)[^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1].replace("&amp;", "&") }
            .map { if (it.startsWith("http")) it else WALL + if (it.startsWith("/")) it else "/$it" }
            .filter { it.startsWith(WALL) }.distinct().take(limit * 5).toList()
        val directImages = Regex("(?:https?:)?//images\\d?\\.alphacoders\\.com/[^\\\"'<> ]+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
            .findAll(html).map { if (it.value.startsWith("//")) "https:${it.value}" else it.value }.distinct().toList()
        var saved = 0
        val candidates = (directImages + detailUrls).distinct()
        if (candidates.isEmpty()) throw IllegalStateException("Katalog Alpha Coders tidak mengembalikan gambar. Situs mungkin membatasi akses otomatis.")
        for ((index, candidate) in candidates.withIndex()) {
            if (saved >= limit) break
            try {
                onProgress("Mengambil gambar Alpha Coders ${index + 1}/${candidates.size}…")
                val imageUrl = if (candidate.contains("alphacoders.com") && !candidate.contains("images")) {
                    val page = fetchText(candidate, listing)
                    Regex("(?:https?:)?//images\\d?\\.alphacoders\\.com/[^\\\"'<> ]+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
                        .find(page)?.value?.let { if (it.startsWith("//")) "https:$it" else it } ?: continue
                } else candidate
                val id = imageUrl.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9_-]"), "").take(80)
                val output = File(destination, "alphacoders_$id.jpg")
                if (output.exists() && output.length() > 20_000L) continue
                val bytes = fetchBytes(imageUrl, candidate)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth < 1280 || bounds.outHeight < 720) continue
                val temp = File(destination, output.name + ".part"); temp.writeBytes(bytes)
                if (!temp.renameTo(output)) { temp.copyTo(output, true); temp.delete() }
                saved++; onProgress("Alpha Coders: $saved/$limit foto tersimpan")
            } catch (e: Exception) { Log.w("GlassTVPhotoMode", "Alpha Coders item skipped: ${e.message}") }
        }
        return saved
    }

    private fun fetchText(url: String, referer: String) = fetch(url, referer).toString(Charsets.UTF_8)
    private fun fetchBytes(url: String, referer: String) = fetch(url, referer).also {
        if (it.size < 20_000 || it.size.toLong() > MAX_BYTES) throw IllegalStateException("Ukuran gambar tidak valid")
    }
    private fun fetch(url: String, referer: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 25000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            c.setRequestProperty("Referer", referer)
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val type = c.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (type.isNotBlank() && !type.startsWith("image/") && !type.contains("html")) throw IllegalStateException("Tipe konten tidak didukung")
            val out = ByteArrayOutputStream(); val buffer = ByteArray(16384); var total = 0L
            c.inputStream.use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_BYTES) throw IllegalStateException("File terlalu besar"); out.write(buffer,0,n) } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }
}

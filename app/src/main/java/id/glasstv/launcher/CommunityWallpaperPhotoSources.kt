package id.glasstv.launcher

import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * Downloads HDwallpapers.net images by following the current catalogue -> detail ->
 * desktop-resolution download flow. The download page may be an HTML countdown page,
 * so image URLs are extracted from that page instead of assuming it is the image itself.
 */
object HdWallpapersPhotoSource {
    private const val BASE = "https://www.hdwallpapers.net"
    private const val MAX_BYTES = 20L * 1024L * 1024L

    private data class Payload(
        val bytes: ByteArray,
        val contentType: String,
        val finalUrl: String
    )

    fun downloadBatch(
        category: String,
        destination: File,
        limit: Int = 20,
        onProgress: (String) -> Unit = {},
        canStore: (File, Long) -> Boolean = { _, _ -> true }
    ): Int {
        destination.mkdirs()
        val listing = if (category.isBlank()) BASE else "$BASE/$category"
        onProgress("Membaca katalog HDwallpapers.net…")
        val listingHtml = fetchText(listing, BASE)

        val detailUrls = extractAttributeUrls(listingHtml)
            .mapNotNull { absolute(it, listing) }
            .filter { url ->
                try {
                    val parsed = URL(url)
                    parsed.host.endsWith("hdwallpapers.net", ignoreCase = true) &&
                        parsed.path.endsWith(".htm", ignoreCase = true) &&
                        parsed.path.contains("wallpaper", ignoreCase = true)
                } catch (_: Exception) { false }
            }
            .distinct()
            .take(limit * 5)

        if (detailUrls.isEmpty()) {
            throw IllegalStateException("Katalog terbuka, tetapi tautan halaman wallpaper tidak ditemukan.")
        }

        var saved = 0
        var examined = 0
        var skipped = 0
        var lastReason = ""
        val seenImages = mutableSetOf<String>()

        for ((index, detailUrl) in detailUrls.withIndex()) {
            if (saved >= limit) break
            examined++
            val id = URL(detailUrl).path.substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9_-]"), "")
            val output = File(destination, "hdwallpapers_$id.jpg")
            if (output.exists() && output.length() > 20_000L) {
                skipped++
                continue
            }

            try {
                onProgress("HDwallpapers.net · memeriksa $examined/${detailUrls.size}…")
                val detailHtml = fetchText(detailUrl, listing)
                val resolutionUrls = Regex(
                    """href\s*=\s*["']([^"']*(?:3840x2160|2560x1440|1920x1080)[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                ).findAll(detailHtml)
                    .mapNotNull { absolute(it.groupValues[1], detailUrl) }
                    .distinct()
                    .sortedBy { url ->
                        when {
                            url.contains("1920x1080", ignoreCase = true) -> 0
                            url.contains("2560x1440", ignoreCase = true) -> 1
                            url.contains("3840x2160", ignoreCase = true) -> 2
                            else -> 3
                        }
                    }
                    .toList()

                val candidates = mutableListOf<String>()
                candidates.addAll(resolutionUrls)
                candidates.addAll(extractImageCandidates(detailHtml, detailUrl))
                val uniqueCandidates = candidates.distinct().filter { seenImages.add(it) }

                var imageBytes: ByteArray? = null
                for (candidate in uniqueCandidates) {
                    try {
                        imageBytes = fetchImageCandidate(candidate, detailUrl, 0, mutableSetOf())
                        if (imageBytes != null) break
                    } catch (error: Exception) {
                        lastReason = error.message ?: "tautan gambar gagal"
                        Log.w("GlassTVPhotoMode", "HDwallpapers candidate failed: $candidate", error)
                    }
                }

                val bytes = imageBytes
                if (bytes == null) {
                    skipped++
                    lastReason = if (lastReason.isBlank()) "tautan gambar resolusi desktop tidak ditemukan" else lastReason
                    onProgress("HDwallpapers.net · gambar ${index + 1} dilewati")
                    continue
                }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth < 1280 || bounds.outHeight < 720) {
                    skipped++
                    lastReason = "resolusi gambar di bawah 1280×720"
                    continue
                }
                if (bytes.size.toLong() > MAX_BYTES) {
                    skipped++
                    lastReason = "gambar melebihi 20 MB"
                    continue
                }
                if (!canStore(output, bytes.size.toLong())) {
                    skipped++
                    lastReason = "ruang koleksi penuh (maks. 100 MB / 20 foto)"
                    onProgress("HDwallpapers.net · ruang koleksi penuh")
                    break
                }

                val temp = File(destination, output.name + ".part")
                temp.writeBytes(bytes)
                if (output.exists() && !output.delete()) {
                    temp.delete()
                    throw IllegalStateException("File lama tidak dapat diganti.")
                }
                if (!temp.renameTo(output)) {
                    temp.copyTo(output, overwrite = true)
                    temp.delete()
                }
                saved++
                onProgress("HDwallpapers.net: $saved foto tersimpan · diperiksa $examined")
            } catch (error: Exception) {
                skipped++
                lastReason = error.message ?: "halaman wallpaper tidak dapat diproses"
                Log.w("GlassTVPhotoMode", "HDwallpapers item failed: $detailUrl", error)
                onProgress("HDwallpapers.net · item ${index + 1} dilewati")
            }
        }

        onProgress(
            "HDwallpapers.net selesai · $saved tersimpan · $skipped dilewati" +
                if (lastReason.isNotBlank()) " · $lastReason" else ""
        )
        return saved
    }

    private fun fetchImageCandidate(
        address: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>
    ): ByteArray? {
        if (depth > 2 || !visited.add(address)) return null
        val payload = fetchPayload(address, referer)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            return if (bounds.outWidth >= 1280 && bounds.outHeight >= 720 &&
                payload.bytes.size.toLong() <= MAX_BYTES) payload.bytes else null
        }

        val html = payload.bytes.toString(Charsets.UTF_8)
        if (depth == 2 || !html.contains("<", ignoreCase = false)) return null
        val nested = extractImageCandidates(html, payload.finalUrl)
        for (candidate in nested) {
            val result = fetchImageCandidate(candidate, payload.finalUrl, depth + 1, visited)
            if (result != null) return result
        }
        return null
    }

    private fun extractImageCandidates(html: String, baseUrl: String): List<String> {
        val raw = mutableListOf<String>()
        raw.addAll(extractAttributeUrls(html))
        Regex("""(?:https?:)?//[^"'<>\s]+""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { raw.add(it.value) }
        Regex("""["']((?:/|https?://)[^"']*(?:\.jpe?g|\.png|\.webp|/download/)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { raw.add(it.groupValues[1]) }
        Regex("""url\(\s*["']?([^)"']+)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { raw.add(it.groupValues[1]) }

        return raw.mapNotNull { absolute(it, baseUrl) }
            .filter { value ->
                val lower = value.lowercase(Locale.ROOT)
                val path = try { URL(value).path.lowercase(Locale.ROOT) } catch (_: Exception) { "" }
                path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".png") || path.endsWith(".webp") ||
                    path.endsWith(".avif") || lower.contains("/download/")
            }
            .distinct()
    }

    private fun extractAttributeUrls(html: String): List<String> {
        val attributes = Regex(
            """(?:href|src|data-src|data-original|data-url|data-href|data-download|content)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return attributes.findAll(html).map { it.groupValues[1] }.toList()
    }

    private fun absolute(value: String, baseUrl: String): String? {
        val clean = value.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("&#x2F;", "/", ignoreCase = true)
        if (clean.isBlank() || clean.startsWith("data:", true) ||
            clean.startsWith("javascript:", true) || clean.startsWith("#")) return null
        return try { URI(baseUrl).resolve(clean).toString() } catch (_: Exception) { null }
    }

    private fun fetchText(address: String, referer: String): String {
        val payload = fetchPayload(address, referer)
        return payload.bytes.toString(Charsets.UTF_8)
    }

    private fun fetchPayload(address: String, referer: String): Payload {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            )
            connection.setRequestProperty("Referer", referer)
            connection.setRequestProperty(
                "Accept",
                "image/avif,image/webp,image/apng,image/*,text/html,application/xhtml+xml,*/*;q=0.8"
            )
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw IllegalStateException("Respons melebihi batas 20 MB")
                    output.write(buffer, 0, count)
                }
            }
            return Payload(
                output.toByteArray(),
                connection.contentType?.lowercase(Locale.ROOT).orEmpty(),
                connection.url.toString()
            )
        } finally {
            connection.disconnect()
        }
    }
}

object AlphaCodersPhotoSource {
    private const val BASE = "https://alphacoders.com"
    private const val WALL = "https://wall.alphacoders.com"
    private const val MAX_BYTES = 20L * 1024L * 1024L

    fun downloadBatch(
        category: String,
        destination: File,
        limit: Int = 20,
        onProgress: (String) -> Unit = {},
        canStore: (File, Long) -> Boolean = { _, _ -> true }
    ): Int {
        destination.mkdirs()
        // Alpha Coders' current public taxonomy uses /nature-wallpapers,
        // not the retired /tag/is-nature route (which returns HTTP 404).
        val listing = if (category.isBlank()) "$BASE/" else "$BASE/$category-wallpapers"
        onProgress("Membaca katalog Alpha Coders…")
        val html = fetchText(listing, BASE)
        val detailUrls = Regex("href=[\\\"']([^\\\"']*(?:big\\.php\\?i=|/wallpaper/|wallpaper-)[^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1].replace("&amp;", "&") }
            .map { if (it.startsWith("http")) it else WALL + if (it.startsWith("/")) it else "/$it" }
            .filter { it.startsWith(WALL) }.distinct().take(limit * 5).toList()
        val directImages = Regex("(?:https?:)?//images\\d*\\.alphacoders\\.com/[^\\\"'<> ]+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
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
                    Regex("(?:https?:)?//images\\d*\\.alphacoders\\.com/[^\\\"'<> ]+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
                        .find(page)?.value?.let { if (it.startsWith("//")) "https:$it" else it } ?: continue
                } else candidate
                val id = imageUrl.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9_-]"), "").take(80)
                val output = File(destination, "alphacoders_$id.jpg")
                if (output.exists() && output.length() > 20_000L) continue
                val bytes = fetchBytes(imageUrl, candidate)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth < 1280 || bounds.outHeight < 720) continue
                if (!canStore(output, bytes.size.toLong())) {
                    onProgress("Koleksi foto penuh: maksimal 100 MB / 20 foto.")
                    break
                }
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

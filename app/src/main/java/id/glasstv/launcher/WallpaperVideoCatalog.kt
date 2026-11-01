package id.glasstv.launcher

/**
 * Curated, manually verified direct MP4 entries for the GlassTV video picker.
 * Search is local against this small catalogue; opening the source page lets the
 * user inspect the original listing before choosing a download.
 */
data class WallpaperVideoItem(
    val id: String,
    val title: String,
    val source: String,
    val pageUrl: String,
    val videoUrl: String,
    val sizeLabel: String,
    val description: String,
    val keywords: Set<String>
) {
    fun matches(query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return true
        val haystack = (title + " " + source + " " + description + " " + keywords.joinToString(" "))
            .lowercase()
        return normalized.split(Regex("\\s+")).all { token -> haystack.contains(token) }
    }
}

object WallpaperVideoCatalog {
    val items = listOf(
        WallpaperVideoItem(
            id = "lively-aurora-forest-night",
            title = "Aurora Forest Night",
            source = "Lively catalogue",
            pageUrl = "https://livelywallpaper.app/live-wallpapers/aurora-forest-night/",
            videoUrl = "https://cdn.livelywallpaper.app/wallpapers/aurora-forest-night/hd.mp4",
            sizeLabel = "1080p · 4.1 MB",
            description = "Hutan pinus gelap di bawah aurora biru kehijauan.",
            keywords = setOf("aurora", "forest", "pine", "night", "winter", "calm", "hutan", "pinus", "malam", "salju")
        ),
        WallpaperVideoItem(
            id = "lively-cosmic-black-hole-space",
            title = "Cosmic Black Hole Space",
            source = "Lively catalogue",
            pageUrl = "https://livelywallpaper.app/live-wallpapers/cosmic-black-hole-space/",
            videoUrl = "https://cdn.livelywallpaper.app/wallpapers/cosmic-black-hole-space/hd.mp4",
            sizeLabel = "1080p · 1.4 MB",
            description = "Lubang hitam bercahaya di latar ruang angkasa hitam.",
            keywords = setOf("cosmic", "black", "hole", "space", "stars", "astronomy", "angkasa", "bintang", "galaksi")
        ),
        WallpaperVideoItem(
            id = "lively-dark-clouds-mountain-rain",
            title = "Dark Clouds and Mountain Rain",
            source = "Lively catalogue",
            pageUrl = "https://livelywallpaper.app/live-wallpapers/dark-clouds-and-mountain-rain/",
            videoUrl = "https://cdn.livelywallpaper.app/wallpapers/dark-clouds-and-mountain-rain/hd.mp4",
            sizeLabel = "1080p · 3.5 MB",
            description = "Punggung gunung, awan gelap, hujan, dan kabut.",
            keywords = setOf("dark", "cloud", "clouds", "mountain", "rain", "mist", "storm", "awan", "gunung", "hujan", "kabut")
        ),
        WallpaperVideoItem(
            id = "lively-rainy-torii-shrine-path",
            title = "Rainy Torii Shrine Path",
            source = "Lively catalogue",
            pageUrl = "https://livelywallpaper.app/live-wallpapers/rainy-torii-shrine-path/",
            videoUrl = "https://cdn.livelywallpaper.app/wallpapers/rainy-torii-shrine-path/hd.mp4",
            sizeLabel = "1080p · 2.0 MB",
            description = "Jalan batu berhujan di antara pepohonan dan gerbang torii.",
            keywords = setOf("rain", "torii", "shrine", "japan", "forest", "path", "hujan", "jepang", "hutan", "jalan")
        )
    )

    fun search(query: String, sourceFilter: String = "all"): List<WallpaperVideoItem> =
        items.filter { item ->
            (sourceFilter == "all" ||
                (sourceFilter == "lively" && item.source == "Lively catalogue")) &&
                item.matches(query)
        }
}

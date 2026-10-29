package id.glasstv.launcher

import android.content.ComponentName
import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Bridge from Android media sessions to Continue Watching and SmartTube's own recommendation
 * notifications. The user must explicitly enable notification access in Android Settings.
 * Notification title/text is only read for SmartTube notifications categorized as recommendations.
 */
class PlaybackListenerService : NotificationListenerService() {
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    // Only notification picture payloads are candidates; notification/app large icons are not thumbnails.
    private val notificationArtwork = ConcurrentHashMap<String, Bitmap>()
    private val artworkAttempts = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        try { activeNotifications?.forEach { captureSmartTubeRecommendation(it) } } catch (_: Exception) { }
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        clearCallbacks()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (isSmartTubeRecommendation(sbn)) {
            captureSmartTubeRecommendation(sbn)
        } else if (isSupportedPackage(pkg)) {
            // For playback notifications we read image payloads only, not their text.
            extractNotificationArtwork(sbn.notification)?.let { bitmap ->
                notificationArtwork[pkg] = bitmap
            }
        }
        refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isSmartTubeRecommendation(sbn)) removeSmartTubeRecommendation(sbn)
        refreshSessions()
    }

    private fun isSmartTubePackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("smarttube") || p.contains("smartyoutubetv2") || p.contains("teamsmart")
    }

    private fun isSmartTubeRecommendation(sbn: StatusBarNotification): Boolean =
        isSmartTubePackage(sbn.packageName) &&
            sbn.notification.category == Notification.CATEGORY_RECOMMENDATION

    private fun recommendationId(sbn: StatusBarNotification): String =
        Integer.toHexString(sbn.key.hashCode())

    private fun captureSmartTubeRecommendation(sbn: StatusBarNotification) {
        if (!isSmartTubeRecommendation(sbn)) return
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return
        val description = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val id = recommendationId(sbn)
        notification.contentIntent?.let { smartTubePendingIntents[id] = it }
        var artworkName = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("smarttube_rec_artwork_$id", null)
        val image = try {
            (extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)
                ?: (extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)
                ?: (extras.getParcelable(Notification.EXTRA_BIG_PICTURE) as? Bitmap)
        } catch (_: Exception) { null }
        if (image != null && !image.isRecycled) {
            val name = "smarttube_rec_$id.jpg"
            try {
                FileOutputStream(File(filesDir, name)).use { output ->
                    if (image.compress(Bitmap.CompressFormat.JPEG, 84, output)) artworkName = name
                }
            } catch (_: Exception) { }
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString("smarttube_rec_ids", "").orEmpty()
            .split(",").filter { it.isNotBlank() && it != id }
        val ordered = (listOf(id) + existing).take(8)
        val edit = prefs.edit()
            .putString("smarttube_rec_ids", ordered.joinToString(","))
            .putString("smarttube_rec_title_$id", title)
            .putString("smarttube_rec_description_$id", description)
            .putString("smarttube_rec_package_$id", sbn.packageName)
            .putLong("smarttube_rec_updated_$id", System.currentTimeMillis())
        if (artworkName != null) edit.putString("smarttube_rec_artwork_$id", artworkName)
        edit.apply()
        (listOf(id) + existing).drop(8).forEach { stale ->
            val staleArtwork = prefs.getString("smarttube_rec_artwork_$stale", null)
            if (!staleArtwork.isNullOrBlank()) File(filesDir, staleArtwork).delete()
            prefs.edit()
                .remove("smarttube_rec_title_$stale")
                .remove("smarttube_rec_description_$stale")
                .remove("smarttube_rec_package_$stale")
                .remove("smarttube_rec_artwork_$stale")
                .remove("smarttube_rec_updated_$stale")
                .apply()
            smartTubePendingIntents.remove(stale)
        }
    }

    private fun removeSmartTubeRecommendation(sbn: StatusBarNotification) {
        val id = recommendationId(sbn)
        smartTubePendingIntents.remove(id)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ids = prefs.getString("smarttube_rec_ids", "").orEmpty()
            .split(",").filter { it.isNotBlank() && it != id }
        val artwork = prefs.getString("smarttube_rec_artwork_$id", null)
        if (!artwork.isNullOrBlank()) File(filesDir, artwork).delete()
        prefs.edit()
            .putString("smarttube_rec_ids", ids.joinToString(","))
            .remove("smarttube_rec_title_$id")
            .remove("smarttube_rec_description_$id")
            .remove("smarttube_rec_package_$id")
            .remove("smarttube_rec_artwork_$id")
            .remove("smarttube_rec_updated_$id")
            .apply()
    }

    private fun refreshSessions() {
        try {
            val manager = getSystemService(MediaSessionManager::class.java)
            val sessions = manager.getActiveSessions(ComponentName(this, PlaybackListenerService::class.java))
            val activePackages = sessions.map { it.packageName }.toSet()
            controllers.keys.filter { it !in activePackages }.toList().forEach { removeController(it) }

            sessions.forEach { controller ->
                val pkg = controller.packageName ?: return@forEach
                if (!isSupportedPackage(pkg)) return@forEach
                if (controllers[pkg] !== controller) {
                    removeController(pkg)
                    val callback = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) = saveSession(controller)
                        override fun onPlaybackStateChanged(state: PlaybackState?) = saveSession(controller)
                        override fun onSessionDestroyed() = removeController(pkg)
                    }
                    controllers[pkg] = controller
                    callbacks[pkg] = callback
                    controller.registerCallback(callback)
                }
                saveSession(controller)
            }
        } catch (e: SecurityException) {
            Log.i(TAG, "Media-session access has not been granted yet")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to refresh media sessions", e)
        }
    }

    private fun saveSession(controller: MediaController) {
        val pkg = controller.packageName ?: return
        if (!isSupportedPackage(pkg)) return
        val metadata = controller.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val state = controller.playbackState
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        saveArtwork(pkg, title, metadata)
        if (isAudioPackage(pkg)) {
            prefs.edit()
                .putString("audio_title_$pkg", title)
                .putString("audio_artist_$pkg", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
                .putString("audio_album_$pkg", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
                .putLong("audio_duration_$pkg", duration)
                .putLong("audio_position_$pkg", state?.position ?: 0L)
                .putInt("audio_state_$pkg", state?.state ?: 0)
                .putLong("audio_updated_$pkg", System.currentTimeMillis())
                .putString("audio_package_$pkg", pkg)
                .apply()
            return
        }
        if (duration < MIN_DURATION_MS) return

        val position = state?.position ?: 0L
        if (position < MIN_POSITION_MS || position >= duration - 15_000L) return
        val playbackSpeed = if (state?.state == PlaybackState.STATE_PLAYING) state.playbackSpeed else 0f
        // Keep one recent item per supported app so switching between apps doesn't erase a row.
        prefs.edit()
            .putString("watch_title_$pkg", title)
            .putString("watch_artist_$pkg", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            .putLong("watch_duration_$pkg", duration)
            .putLong("watch_position_$pkg", position)
            .putLong("watch_updated_$pkg", System.currentTimeMillis())
            .putString("watch_package_$pkg", pkg)
            .putFloat("watch_speed_$pkg", playbackSpeed)
            .apply()
    }

    /**
     * Try image-only notification extras when the media session has no artwork bitmap.
     * This intentionally does not read notification title/text.
     */
    /**
     * A notification's large icon is usually the SmartTube app logo, not the video image.
     * Only use EXTRA_PICTURE, which is intended for actual picture payloads.
     */
    private fun extractNotificationArtwork(notification: Notification): Bitmap? {
        return try {
            val picture = notification.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
            picture?.takeIf { !it.isRecycled }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Try every image source SmartTube/YouTube may publish. Do file/network work off the
     * notification listener callback thread so a remote image cannot freeze the TV UI.
     */
    private fun saveArtwork(pkg: String, title: String, metadata: MediaMetadata) {
        val artworkUriKeys = arrayOf(
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI
        )
        val bitmapKeys = arrayOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        val bitmapAvailable = bitmapKeys.any { key ->
            try { metadata.getBitmap(key) != null } catch (_: Exception) { false }
        }
        val artworkUris = artworkUriKeys.mapNotNull { key ->
            try { metadata.getString(key)?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        }
        val mediaUri = try { metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) } catch (_: Exception) { null }
        val mediaId = try { metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) } catch (_: Exception) { null }
        val signature = listOf(title, bitmapAvailable.toString(), artworkUris.joinToString(","), mediaUri.orEmpty(), mediaId.orEmpty()).joinToString("|")
        synchronized(artworkAttempts) {
            if (artworkAttempts[pkg] == signature) return
            artworkAttempts[pkg] = signature
        }

        Thread({
            try {
                persistArtwork(pkg, title, metadata, artworkUris, mediaUri, mediaId)
            } catch (e: Exception) {
                Log.i(TAG, "Artwork lookup failed for $pkg", e)
            }
        }, "GlassTVArtwork").start()
    }

    private fun persistArtwork(
        pkg: String,
        title: String,
        metadata: MediaMetadata,
        artworkUris: List<String>,
        mediaUri: String?,
        mediaId: String?
    ) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val oldTitle = prefs.getString("watch_artwork_title_$pkg", null)
        val oldName = prefs.getString("watch_artwork_$pkg", null)
        val oldSource = prefs.getString("watch_artwork_source_$pkg", null)
        // Do not trust thumbnails saved by the prior build: it could save the app logo as artwork.
        val trustedSources = setOf("metadata_bitmap", "metadata_uri", "youtube_thumbnail", "notification_picture")
        if (oldTitle == title && oldSource in trustedSources &&
            !oldName.isNullOrBlank() && File(filesDir, oldName).isFile) return

        var source: Bitmap? = null
        var sourceType: String? = null
        val bitmapKeys = arrayOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        for (key in bitmapKeys) {
            try { source = metadata.getBitmap(key) } catch (_: Exception) { source = null }
            if (source != null && !source.isRecycled) {
                sourceType = "metadata_bitmap"
                break
            }
            source = null
        }

        if (source == null) {
            for (uri in artworkUris) {
                source = loadArtworkUri(uri)
                if (source != null) {
                    sourceType = "metadata_uri"
                    break
                }
            }
        }

        if (source == null) {
            val videoId = extractYoutubeVideoId(mediaUri) ?: extractYoutubeVideoId(mediaId)
            if (videoId != null) {
                val candidateUrls = listOf(
                    "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                )
                for (url in candidateUrls) {
                    source = loadArtworkUri(url)
                    if (source != null) {
                        sourceType = "youtube_thumbnail"
                        break
                    }
                }
            }
        }

        if (source == null) {
            source = notificationArtwork[pkg]?.takeUnless { it.isRecycled }
            if (source != null) sourceType = "notification_picture"
        }

        val fileName = "watch_art_" + pkg.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg"
        val target = File(filesDir, fileName)
        if (source == null || sourceType == null) {
            Log.d(TAG, "No actual thumbnail available for $pkg; metadataKeys=${try { metadata.keySet() } catch (_: Exception) { emptySet<String>() }}, artUris=${artworkUris.isNotEmpty()}, mediaUri=${!mediaUri.isNullOrBlank()}, mediaId=${!mediaId.isNullOrBlank()}, notificationPicture=${notificationArtwork.containsKey(pkg)}")
            // Remove any legacy app-logo image so the UI falls back to the correctly sized app icon.
            if (!oldName.isNullOrBlank()) File(filesDir, oldName).delete()
            prefs.edit()
                .remove("watch_artwork_$pkg")
                .remove("watch_artwork_title_$pkg")
                .remove("watch_artwork_source_$pkg")
                .apply()
            return
        }

        var scaled: Bitmap? = null
        try {
            val ratio = minOf(480f / source.width, 270f / source.height, 1f)
            val image = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * ratio).toInt().coerceAtLeast(1),
                    (source.height * ratio).toInt().coerceAtLeast(1),
                    true
                ).also { scaled = it }
            } else source
            FileOutputStream(target).use { output ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                    throw IllegalStateException("Could not encode artwork")
                }
            }
            prefs.edit()
                .putString("watch_artwork_$pkg", fileName)
                .putString("watch_artwork_title_$pkg", title)
                .putString("watch_artwork_source_$pkg", sourceType)
                .apply()
            Log.d(TAG, "Saved real thumbnail for $pkg using $sourceType (${source.width}x${source.height})")
        } catch (e: Exception) {
            target.delete()
            Log.i(TAG, "Could not persist thumbnail for $pkg", e)
            if (!oldName.isNullOrBlank()) File(filesDir, oldName).delete()
            prefs.edit()
                .remove("watch_artwork_$pkg")
                .remove("watch_artwork_title_$pkg")
                .remove("watch_artwork_source_$pkg")
                .apply()
        } finally {
            scaled?.recycle()
        }
    }

    private fun loadArtworkUri(value: String): Bitmap? {
        return try {
            val uri = Uri.parse(value)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val connection = (URL(value).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3500
                        readTimeout = 3500
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "GlassTVLauncher/0.4")
                    }
                    try {
                        connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        connection.disconnect()
                    }
                }
                "content", "file", "android.resource" ->
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                else -> null
            }
        } catch (_: Exception) {
            Log.d(TAG, "Artwork URI could not be loaded (${value.substringBefore(':')})")
            null
        }
    }

    private fun extractYoutubeVideoId(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) return raw
        val patterns = listOf(
            Regex("""(?:youtu\.be/)([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE),
            Regex("""youtube\.com/(?:watch\?[^\s#]*v=|embed/|shorts/|live/)([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(raw)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun isSupportedPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("youtube") || p.contains("smarttube") || p.contains("smartyoutubetv2") ||
            p.contains("teamsmart") || isAudioPackage(p)
    }

    private fun isAudioPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("spotify") || p.contains("aimp") || p.contains("vlc") ||
            p.contains("poweramp") || p.contains("deezer") || p.contains("tidal") ||
            p.contains("foobar") || p.contains("music") || p.contains("plex") ||
            p.contains("tunein") || p.contains("podcast") || p.contains("amazon")
    }

    private fun removeController(pkg: String) {
        val controller = controllers.remove(pkg)
        val callback = callbacks.remove(pkg)
        if (controller != null && callback != null) {
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
    }

    private fun clearCallbacks() {
        controllers.keys.toList().forEach { removeController(it) }
    }

    companion object {
        private val smartTubePendingIntents = ConcurrentHashMap<String, PendingIntent>()

        fun launchSmartTubeRecommendation(id: String): Boolean {
            val pending = smartTubePendingIntents[id] ?: return false
            return try {
                pending.send()
                true
            } catch (_: Exception) {
                smartTubePendingIntents.remove(id)
                false
            }
        }

        private const val TAG = "GlassTVPlayback"
        private const val PREFS = "glass_tv"
        private const val MIN_DURATION_MS = 3 * 60 * 1000L
        private const val MIN_POSITION_MS = 15 * 1000L
    }
}

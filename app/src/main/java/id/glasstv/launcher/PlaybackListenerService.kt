package id.glasstv.launcher

import android.content.ComponentName
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Optional bridge from Android media sessions to the launcher's Continue Watching row.
 * The user must explicitly enable notification access in Android Settings.
 * Only media metadata/state is read; notification text is never inspected.
 */
class PlaybackListenerService : NotificationListenerService() {
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    // Store only image payloads from supported apps' notifications; notification text is never read.
    private val notificationArtwork = mutableMapOf<String, Bitmap>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        clearCallbacks()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Some TV players omit artwork from MediaMetadata but include it in the media notification.
        // Read image extras only; do not inspect notification title, body, or other text.
        val pkg = sbn.packageName
        if (isSupportedPackage(pkg)) {
            extractNotificationArtwork(sbn.notification)?.let { bitmap ->
                notificationArtwork[pkg] = bitmap
            }
        }
        refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshSessions()
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
        if (duration < MIN_DURATION_MS) return

        val state = controller.playbackState
        val position = state?.position ?: 0L
        if (position < MIN_POSITION_MS || position >= duration - 15_000L) return
        val playbackSpeed = if (state?.state == PlaybackState.STATE_PLAYING) state.playbackSpeed else 0f
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        saveArtwork(pkg, title, metadata)
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
    private fun extractNotificationArtwork(notification: Notification): Bitmap? {
        val extras = notification.extras
        try {
            val picture = extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
            if (picture != null && !picture.isRecycled) return picture
        } catch (_: Exception) { }

        try {
            val largeIconBitmap = extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
            if (largeIconBitmap != null && !largeIconBitmap.isRecycled) return largeIconBitmap
        } catch (_: Exception) { }

        return try {
            drawableToBitmap(notification.getLargeIcon()?.loadDrawable(this))
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) {
            return drawable.bitmap.takeIf { !it.isRecycled }
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 320
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 180
        val ratio = minOf(1280f / width, 720f / height, 1f)
        val bitmap = Bitmap.createBitmap(
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Persist media-session artwork locally. Not all players expose artwork; the UI
     * falls back to notification artwork, then the installed player's app icon.
     */
    private fun saveArtwork(pkg: String, title: String, metadata: MediaMetadata) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val oldTitle = prefs.getString("watch_artwork_title_$pkg", null)
        val oldName = prefs.getString("watch_artwork_$pkg", null)
        if (oldTitle == title && !oldName.isNullOrBlank() && File(filesDir, oldName).isFile) return

        var source: Bitmap? = null
        val artworkKeys = arrayOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        for (key in artworkKeys) {
            try {
                source = metadata.getBitmap(key)
            } catch (_: Exception) {
                source = null
            }
            if (source != null) break
        }

        val fileName = "watch_art_" + pkg.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg"
        val target = File(filesDir, fileName)
        if (source == null) {
            source = notificationArtwork[pkg]?.takeUnless { it.isRecycled }
        }
        if (source == null) {
            Log.d(TAG, "No media-session or notification artwork available for $pkg")
            if (oldTitle != title) {
                if (!oldName.isNullOrBlank()) File(filesDir, oldName).delete()
                prefs.edit()
                    .remove("watch_artwork_$pkg")
                    .remove("watch_artwork_title_$pkg")
                    .apply()
            }
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
                .apply()
        } catch (e: Exception) {
            target.delete()
            Log.i(TAG, "Media session did not provide usable artwork for $pkg", e)
            if (oldTitle != title) {
                prefs.edit().remove("watch_artwork_$pkg").remove("watch_artwork_title_$pkg").apply()
            }
        } finally {
            scaled?.recycle()
        }
    }

    private fun isSupportedPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("youtube") || p.contains("smarttube") || p.contains("teamsmart")
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
        private const val TAG = "GlassTVPlayback"
        private const val PREFS = "glass_tv"
        private const val MIN_DURATION_MS = 3 * 60 * 1000L
        private const val MIN_POSITION_MS = 15 * 1000L
    }
}

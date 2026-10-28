package id.glasstv.launcher

import android.content.ComponentName
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        clearCallbacks()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Notification contents are deliberately ignored. Refresh only media-session metadata.
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

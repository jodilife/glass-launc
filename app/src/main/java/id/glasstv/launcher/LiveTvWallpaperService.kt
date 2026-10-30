package id.glasstv.launcher

import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Experimental native-tuner wallpaper.
 *
 * Unlike the sample MediaPlayer implementation, this uses TvInputManager.Session
 * and the WallpaperService surface directly. It does not create an ExoPlayer and
 * therefore provides an isolated path for diagnosing the Home TvView composition issue.
 */
class LiveTvWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = LiveTvEngine()

    inner class LiveTvEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visibleNow = false
        private var surface: Surface? = null
        private var tvSession: TvInputManager.Session? = null
        private var sessionCreating = false

        private val sessionCallback = object : TvInputManager.SessionCallback() {
            override fun onSessionCreated(session: TvInputManager.Session) {
                sessionCreating = false
                if (!shouldPlay() || surface?.isValid != true) {
                    session.release()
                    return
                }
                tvSession = session
                val target = resolveChannel()
                if (target == null) {
                    Log.w(TAG, "No available tuner/channel for Live TV wallpaper")
                    releaseSession()
                    return
                }
                try {
                    session.setSurface(surface)
                    session.tune(target.second)
                    Log.i(TAG, "Wallpaper tuner session started: ${target.first}")
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to attach tuner to wallpaper surface", error)
                    releaseSession()
                }
            }

            override fun onVideoAvailable(session: TvInputManager.Session) {
                Log.i(TAG, "Wallpaper tuner video is available")
            }

            override fun onVideoUnavailable(session: TvInputManager.Session, reason: Int) {
                Log.w(TAG, "Wallpaper tuner video unavailable: $reason")
            }

            override fun onSessionReleased(session: TvInputManager.Session) {
                if (tvSession === session) tvSession = null
                sessionCreating = false
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surface = holder.surface
            startSessionIfNeeded()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surface = holder.surface
            tvSession?.setSurface(holder.surface)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surface = null
            releaseSession()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            visibleNow = visible
            if (visible) startSessionIfNeeded() else releaseSession()
        }

        override fun onDestroy() {
            releaseSession()
            handler.removeCallbacksAndMessages(null)
            super.onDestroy()
        }

        private fun shouldPlay(): Boolean {
            val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
            return visibleNow &&
                prefs.getBoolean("live_tv_wallpaper_service", false) &&
                prefs.getBoolean("live_tv_idle_background", false) &&
                prefs.getBoolean("live_tv_idle_mode_active", false)
        }

        private fun startSessionIfNeeded() {
            if (!shouldPlay() || surface?.isValid != true || tvSession != null || sessionCreating) return
            val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager ?: return
            val target = resolveChannel() ?: return
            sessionCreating = true
            try {
                manager.createSession(target.first, sessionCallback, handler)
            } catch (error: Exception) {
                sessionCreating = false
                Log.e(TAG, "Unable to create wallpaper tuner session", error)
            }
        }

        private fun resolveChannel(): Pair<String, Uri>? {
            val prefs = getSharedPreferences("glass_tv", MODE_PRIVATE)
            val selectedInputId = prefs.getString("live_tv_input_id", "auto") ?: "auto"
            val manager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager ?: return null
            val inputs = manager.tvInputList
            val selectedInput = if (selectedInputId == "auto") null
                else inputs.firstOrNull { it.id == selectedInputId } ?: return null

            if (selectedInput?.isPassthroughInput == true) {
                return selectedInput.id to TvContract.buildChannelUriForPassthroughInput(selectedInput.id)
            }

            val channels = try {
                contentResolver.query(
                    TvContract.Channels.CONTENT_URI,
                    arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID),
                    null, null, null
                )?.use { cursor ->
                    val found = mutableListOf<Pair<Long, String>>()
                    while (cursor.moveToNext()) {
                        val idIndex = cursor.getColumnIndex(TvContract.Channels._ID)
                        val inputIndex = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                        if (idIndex >= 0 && inputIndex >= 0 && !cursor.isNull(inputIndex)) {
                            found.add(cursor.getLong(idIndex) to cursor.getString(inputIndex))
                        }
                    }
                    found
                }.orEmpty()
            } catch (error: SecurityException) {
                Log.e(TAG, "READ_TV_LISTINGS permission is required", error)
                return null
            }

            val candidates = if (selectedInput != null) listOf(selectedInput)
                else inputs.filter { it.type == TvInputInfo.TYPE_TUNER }
            val savedChannelId = prefs.getLong("live_tv_channel_id", -1L)
            for (input in candidates) {
                val channel = channels.firstOrNull { it.second == input.id && it.first == savedChannelId }
                    ?: channels.firstOrNull { it.second == input.id }
                if (channel != null) {
                    return input.id to TvContract.buildChannelUri(channel.first)
                }
            }
            return null
        }

        private fun releaseSession() {
            val current = tvSession
            tvSession = null
            if (current != null) {
                try { current.setSurface(null) } catch (_: Exception) { }
                try { current.release() } catch (_: Exception) { }
            }
        }
    }

    companion object {
        private const val TAG = "GlassTVWallpaper"
    }
}

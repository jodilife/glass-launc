package id.glasstv.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Plays the original Glass TV Home navigation/select WAV samples; see THIRD_PARTY_NOTICES_GLASS_TV.md. */
internal object UiSoundEffects {
    private var pool: SoundPool? = null
    private var activeProfile = ""
    private var navId = 0
    private var selectId = 0
    private val readySamples = mutableSetOf<Int>()

    fun initialize(context: Context) {
        ensureLoaded(context.applicationContext, activeProfile(context))
    }

    fun setProfile(context: Context, profile: String) {
        val safe = profile.takeIf { it in setOf("ps5", "switch", "xbox", "off") } ?: "ps5"
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE).edit()
            .putString("ui_sound_profile", safe).apply()
        release()
        ensureLoaded(context.applicationContext, safe)
    }

    fun activeProfile(context: Context): String =
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
            .getString("ui_sound_profile", "ps5") ?: "ps5"

    fun profileLabel(profile: String): String = when (profile) {
        "switch" -> "Nintendo Switch"
        "xbox" -> "Xbox Series X/S"
        "off" -> "Mati"
        else -> "PlayStation 5"
    }

    fun playNavigation(context: Context) {
        if (!context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
                .getBoolean("ui_sound_enabled", true)) return
        val profile = activeProfile(context)
        if (profile == "off") return
        ensureLoaded(context.applicationContext, profile)
        play(navId, when (profile) { "switch" -> 0.85f; "xbox" -> 0.75f; else -> 0.65f })
    }

    fun playSelect(context: Context) {
        if (!context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
                .getBoolean("ui_sound_enabled", true)) return
        val profile = activeProfile(context)
        if (profile == "off") return
        ensureLoaded(context.applicationContext, profile)
        play(selectId, when (profile) { "switch" -> 0.95f; "xbox" -> 0.90f; else -> 0.85f })
    }

    private fun play(id: Int, volume: Float) {
        val soundPool = pool ?: return
        val ready = synchronized(readySamples) { readySamples.contains(id) }
        if (id == 0 || !ready) return
        try { soundPool.play(id, volume, volume, 0, 0, 1.0f) } catch (_: Exception) { }
    }

    @Synchronized
    private fun ensureLoaded(context: Context, profile: String) {
        if (profile == "off") {
            if (pool != null || activeProfile != "off") release()
            activeProfile = "off"
            return
        }
        if (pool != null && activeProfile == profile) return
        release()
        activeProfile = profile
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            pool = soundPool
            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) synchronized(readySamples) { readySamples.add(sampleId) }
            }
            val samples = when (profile) {
                "switch" -> R.raw.switch_nav to R.raw.switch_select
                "xbox" -> R.raw.xbox_nav to R.raw.xbox_select
                else -> R.raw.ps5_nav to R.raw.ps5_select
            }
            navId = soundPool.load(context, samples.first, 1)
            selectId = soundPool.load(context, samples.second, 1)
        } catch (_: Exception) { release() }
    }

    @Synchronized
    fun release() {
        try { pool?.release() } catch (_: Exception) { }
        pool = null
        navId = 0
        selectId = 0
        activeProfile = ""
        synchronized(readySamples) { readySamples.clear() }
    }
}

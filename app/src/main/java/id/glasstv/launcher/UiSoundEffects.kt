package id.glasstv.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates short, original synthesized UI cues inspired by console-style navigation.
 * It deliberately does not bundle or copy commercial console sound recordings.
 */
internal object UiSoundEffects {
    private var pool: SoundPool? = null
    private var activeProfile = ""
    private var navId = 0
    private var selectId = 0
    private val readySamples = mutableSetOf<Int>()

    fun initialize(context: Context) {
        val app = context.applicationContext
        val profile = app.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
            .getString("ui_sound_profile", "ps5") ?: "ps5"
        ensureLoaded(app, profile)
    }

    fun setProfile(context: Context, profile: String) {
        val safe = profile.takeIf { it in setOf("ps5", "switch", "xbox", "off") } ?: "ps5"
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
            .edit().putString("ui_sound_profile", safe).apply()
        release()
        ensureLoaded(context.applicationContext, safe)
    }

    fun activeProfile(context: Context): String =
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
            .getString("ui_sound_profile", "ps5") ?: "ps5"

    fun profileLabel(profile: String): String = when (profile) {
        "switch" -> "Switch"
        "xbox" -> "Xbox"
        "off" -> "Mati"
        else -> "PS5"
    }

    fun playNavigation(context: Context) {
        if (!context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
                .getBoolean("ui_sound_enabled", true)) return
        val profile = activeProfile(context)
        if (profile == "off") return
        ensureLoaded(context.applicationContext, profile)
        play(navId, if (profile == "switch") 0.72f else 0.58f)
    }

    fun playSelect(context: Context) {
        if (!context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
                .getBoolean("ui_sound_enabled", true)) return
        val profile = activeProfile(context)
        if (profile == "off") return
        ensureLoaded(context.applicationContext, profile)
        play(selectId, if (profile == "switch") 0.82f else 0.72f)
    }

    private fun play(sampleId: Int, volume: Float) {
        val soundPool = pool ?: return
        if (sampleId == 0 || sampleId !in readySamples) return
        try { soundPool.play(sampleId, volume, volume, 1, 0, 1f) } catch (_: Exception) { }
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
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attributes)
                .build()
            pool = soundPool
            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) synchronized(readySamples) { readySamples.add(sampleId) }
            }
            val navFile = createWave(context.cacheDir, profile, "nav")
            val selectFile = createWave(context.cacheDir, profile, "select")
            navId = soundPool.load(navFile.absolutePath, 1)
            selectId = soundPool.load(selectFile.absolutePath, 1)
        } catch (_: Exception) {
            release()
        }
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

    private fun createWave(cacheDir: File, profile: String, kind: String): File {
        val file = File(cacheDir, "glasstv_${profile}_${kind}.wav")
        if (file.isFile && file.length() > 44L) return file
        val rate = 24000
        val seconds = if (kind == "nav") 0.075 else 0.125
        val count = (rate * seconds).toInt()
        val raw = ByteArray(count * 2)
        val random = Random(if (kind == "nav") 71 else 97)
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            val p = i.toDouble() / (count - 1).coerceAtLeast(1)
            val startHz = when (profile) { "switch" -> 980.0; "xbox" -> 430.0; else -> 620.0 }
            val endHz = when (profile) { "switch" -> 1540.0; "xbox" -> 790.0; else -> 1220.0 }
            val freq = startHz + (endHz - startHz) * p
            val phase = 2.0 * PI * (startHz * t + (endHz - startHz) * t * t / (2.0 * seconds))
            val primary = sin(phase)
            val overtone = sin(phase * (if (kind == "nav") 1.51 else 2.03) + 0.3)
            val envelope = if (kind == "nav") {
                sin(PI * p).coerceAtLeast(0.0).let { it * exp(-p * 0.6) }
            } else {
                (1.0 - p).coerceAtLeast(0.0) * exp(-p * 2.5)
            }
            val click = if (profile == "switch") (random.nextDouble() * 2.0 - 1.0) * exp(-p * 24.0) * 0.18 else 0.0
            val swoosh = if (kind == "nav" && profile == "ps5") (random.nextDouble() * 2.0 - 1.0) * exp(-p * 9.0) * 0.10 else 0.0
            val warm = if (profile == "xbox") sin(phase * 0.5) * 0.25 else 0.0
            val mono = ((primary * 0.72 + overtone * 0.22 + click + swoosh + warm) * envelope * 0.47)
                .coerceIn(-1.0, 1.0)
            val value = (mono * Short.MAX_VALUE).toInt()
            raw[i * 2] = (value and 0xFF).toByte()
            raw[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun writeAscii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
            fun writeShortLE(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }
            fun writeIntLE(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 24) and 0xFF)
            }
            writeAscii("RIFF")
            writeIntLE(36 + raw.size)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(1)
            writeIntLE(rate)
            writeIntLE(rate * 2)
            writeShortLE(2)
            writeShortLE(16)
            writeAscii("data")
            writeIntLE(raw.size)
            out.write(raw)
        }
        return file
    }
}

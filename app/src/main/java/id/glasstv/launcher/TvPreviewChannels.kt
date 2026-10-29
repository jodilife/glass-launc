package id.glasstv.launcher

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri

/**
 * Reads only public Android TV Preview Channels published by installed apps.
 * Content is intentionally not fabricated: rows appear only when an installed provider exposes
 * readable preview programs. If there are none, the Home screen shows an honest SmartTube shortcut.
 */
internal object TvPreviewChannels {
    data class Program(val title: String, val artwork: String?, val intentUri: String?)
    data class Channel(val title: String, val packageName: String, val programs: List<Program>)

    fun read(context: Context, maxChannels: Int = 4, maxPrograms: Int = 8): List<Channel> {
        // Query the provider directly; the legacy READ_EPG_DATA permission is deprecated.
        return try {
            val channels = query(context, TvContract.Channels.CONTENT_URI) { cursor ->
                val type = cursor.string(TvContract.Channels.COLUMN_TYPE) ?: return@query null
                if (type != TvContract.Channels.TYPE_PREVIEW) return@query null
                val packageName = cursor.string(TvContract.Channels.COLUMN_PACKAGE_NAME) ?: return@query null
                val name = cursor.string(TvContract.Channels.COLUMN_DISPLAY_NAME).orEmpty().ifBlank { "Rekomendasi" }
                val id = cursor.long(TvContract.Channels._ID)
                Triple(id, packageName, name)
            }.filterNotNull()
            channels.mapNotNull { (id, packageName, name) ->
                val programs = query(context, TvContract.buildPreviewProgramsUriForChannel(id)) { cursor ->
                    val title = cursor.string(TvContract.PreviewPrograms.COLUMN_TITLE)?.trim().orEmpty()
                    if (title.isBlank()) return@query null
                    val poster = cursor.string(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
                    val thumb = cursor.string(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI)
                    val intentUri = cursor.string(TvContract.PreviewPrograms.COLUMN_INTENT_URI)
                    Program(title, poster ?: thumb, intentUri)
                }.filterNotNull().take(maxPrograms)
                if (programs.isEmpty()) null else Channel(name, packageName, programs)
            }.distinctBy { "${it.packageName}:${it.title}" }.take(maxChannels)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun launchIntent(context: Context, channel: Channel, program: Program): Intent? {
        val deepLink = program.intentUri?.let {
            try { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) } catch (_: Exception) { null }
        }
        return (deepLink ?: context.packageManager.getLeanbackLaunchIntentForPackage(channel.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(channel.packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun <T> query(context: Context, uri: Uri, map: (Cursor) -> T?): List<T?> = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val result = ArrayList<T?>(cursor.count)
            while (cursor.moveToNext()) result.add(map(cursor))
            result
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.long(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else -1L
    }
}

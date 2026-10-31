package id.glasstv.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build

/**
 * Reads actual Android TV Preview Channel programs and Watch Next entries published by providers.
 * It does not fabricate rows when the TV has no program data.
 */
internal object TvPreviewChannels {
    data class Program(
        val title: String,
        val artwork: String?,
        val intentUri: String?,
        val packageName: String? = null
    )
    data class Channel(
        val title: String,
        val packageName: String,
        val programs: List<Program>,
        val category: String = "recommendations"
    )

    fun read(context: Context, maxChannels: Int = 10, maxPrograms: Int = 8): List<Channel> {
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val previewChannels = try {
            val rows = query(context, TvContract.Channels.CONTENT_URI) { cursor ->
                val type = cursor.string(TvContract.Channels.COLUMN_TYPE) ?: return@query null
                if (type != TvContract.Channels.TYPE_PREVIEW) return@query null
                val packageName = cursor.string(TvContract.Channels.COLUMN_PACKAGE_NAME) ?: return@query null
                val name = cursor.string(TvContract.Channels.COLUMN_DISPLAY_NAME).orEmpty().ifBlank { "Rekomendasi" }
                val id = cursor.long(TvContract.Channels._ID)
                Triple(id, packageName, name)
            }.filterNotNull()
            rows.mapNotNull { (id, packageName, name) ->
                val programs = query(context, TvContract.buildPreviewProgramsUriForChannel(id)) { cursor ->
                    val title = cursor.string(TvContract.PreviewPrograms.COLUMN_TITLE)?.trim().orEmpty()
                    if (title.isBlank()) return@query null
                    val poster = cursor.string(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
                    val thumb = cursor.string(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI)
                    val intentUri = cursor.string(TvContract.PreviewPrograms.COLUMN_INTENT_URI)
                    Program(title, poster ?: thumb, intentUri, packageName)
                }.filterNotNull().take(maxPrograms)
                if (programs.isEmpty()) null else Channel(
                    name, packageName, programs,
                    if (isHistoryTitle(name)) "history" else "recommendations"
                )
            }.distinctBy { "${it.packageName}:${it.title}" }.take(maxChannels)
        } catch (_: Exception) { emptyList() }

        val watchNext = readWatchNext(context, maxPrograms)
        val result = ArrayList<Channel>(previewChannels.size + 1)
        result.addAll(previewChannels)
        if (watchNext.isNotEmpty()) result.add(Channel("Riwayat & Lanjutkan Menonton", "", watchNext, "history"))
        return result
    }

    private fun isHistoryTitle(title: String): Boolean {
        val value = title.lowercase()
        return listOf("history", "watch next", "watchnext", "continue", "riwayat", "lanjutkan", "recent")
            .any { value.contains(it) }
    }

    private fun readWatchNext(context: Context, maxPrograms: Int): List<Program> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        return try {
            query(
                context,
                TvContract.WatchNextPrograms.CONTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC"
            ) { cursor ->
                val title = cursor.string(TvContract.WatchNextPrograms.COLUMN_TITLE)?.trim().orEmpty()
                if (title.isBlank()) return@query null
                val poster = cursor.string(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)
                val thumb = cursor.string(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                val intentUri = cursor.string(TvContract.WatchNextPrograms.COLUMN_INTENT_URI)
                val owner = cursor.string(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME)
                Program(title, poster ?: thumb, intentUri, owner)
            }.filterNotNull().take(maxPrograms)
        } catch (_: Exception) { emptyList() }
    }

    fun launchIntent(context: Context, channel: Channel, program: Program): Intent? {
        val deepLink = program.intentUri?.let {
            try { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) } catch (_: Exception) { null }
        }
        val owner = program.packageName?.takeIf { it.isNotBlank() } ?: channel.packageName
        val fallback = if (owner.isNotBlank()) {
            context.packageManager.getLeanbackLaunchIntentForPackage(owner)
                ?: context.packageManager.getLaunchIntentForPackage(owner)
        } else null
        return (deepLink ?: fallback)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun <T> query(
        context: Context,
        uri: Uri,
        sortOrder: String? = null,
        map: (Cursor) -> T?
    ): List<T?> = try {
        context.contentResolver.query(uri, null, null, null, sortOrder)?.use { cursor ->
            val result = ArrayList<T?>(cursor.count)
            while (cursor.moveToNext()) result.add(map(cursor))
            result
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
    private fun Cursor.long(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else -1L
    }
}

package sh.hnet.music.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** Files the user picks in the system's picker (playlists, presets, backups). */
object Documents {
    /** The name the picker shows for [uri]; null when its provider doesn't say. Never logged. */
    fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}

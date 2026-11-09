package sh.hnet.music.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** A playlist the user made or imported; kept in the app's database only (SPEC-playlists §2). */
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Entries added, removed or moved, renamed or replaced: the default sort (§4.3). */
    val changedAt: Long,
    /** A built-in playlist ([STARRED]) or null for the user's own; built-in ones can't be renamed or deleted. */
    val builtin: String? = null,
) {
    companion object {
        /** Starred (SPEC-now-playing-v2 §6): shown as "Starred" / "Ulubione" from resources, whatever is stored. */
        const val STARRED = "starred"
    }
}

/**
 * One entry of a playlist, in [position] order. It points to a library track by [documentUri] and
 * keeps a snapshot of what it knew (path, tags, duration), so a track that disappears stays in place
 * as missing and can be found again (§6.1). The same track may appear in several entries.
 */
@Entity(tableName = "playlist_entry", indices = [Index("playlistId"), Index("documentUri")])
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val documentUri: String?,
    /** The picked folder of the track it was resolved to; null for an imported line that matched nothing. */
    val folderUri: String?,
    /** The path inside [folderUri], or the raw path of an imported line. */
    val path: String?,
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
)

/** What the Playlists view needs of each entry. */
data class EntryRef(val id: Long, val playlistId: Long, val documentUri: String?)

/** What a missing entry shows: its own title, else the file name (with extension, to find it). */
val PlaylistEntry.shownTitle: String
    get() = title ?: path?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()

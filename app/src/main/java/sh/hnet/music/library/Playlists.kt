package sh.hnet.music.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import sh.hnet.music.data.EntryRef
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.Playlist
import sh.hnet.music.data.PlaylistDao
import sh.hnet.music.data.PlaylistEntry
import java.text.Collator
import java.util.Locale

enum class PlaylistSort { RECENTLY_CHANGED, RECENTLY_ADDED, NAME }

/** A playlist as lists show it: its playable tracks in order and how many entries are missing. */
data class PlaylistSummary(
    val playlist: Playlist,
    val tracks: List<LibraryTrack>,
    /** The entries [tracks] come from, in the same order. */
    val entryIds: List<Long>,
    val missing: Int,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }

    /**
     * The tracks whose covers make the picture (§4.2): the first track of each of the first four
     * distinct albums; four make a mosaic, fewer show the first one's cover, none the placeholder.
     */
    val coverTracks: List<LibraryTrack> by lazy {
        val seen = HashSet<String>()
        val firsts = ArrayList<LibraryTrack>(4)
        for (track in tracks) {
            val key = if (track.album.isNullOrBlank()) "\u0000" + track.documentUri else Albums.keyOf(track)
            if (seen.add(key)) firsts += track
            if (firsts.size == 4) break
        }
        if (firsts.size == 4) firsts else firsts.take(1)
    }
}

/** An entry and its library track, or null while it's missing. */
data class PlaylistRow(val entry: PlaylistEntry, val track: LibraryTrack?)

data class PlaylistPage(val playlist: Playlist, val rows: List<PlaylistRow>) {
    val summary: PlaylistSummary by lazy {
        val playable = rows.filter { it.track != null }
        PlaylistSummary(playlist, playable.map { it.track!! }, playable.map { it.entry.id }, rows.size - playable.size)
    }
}

/** The entries an add made, and how many of its tracks the playlist had already. */
class AddResult(val entryIds: List<Long>, val alreadyThere: Int)

/** A new entry for a library track, with the snapshot that keeps it if the track goes (§6.1). */
fun entryOf(playlistId: Long, position: Int, track: LibraryTrack) = PlaylistEntry(
    playlistId = playlistId,
    position = position,
    documentUri = track.documentUri,
    folderUri = track.folderUri,
    path = track.relativePath,
    title = track.title,
    artists = track.artists,
    album = track.album,
    durationMs = track.durationMs,
)

/** A deleted playlist kept for Undo. */
class DeletedPlaylist(val playlist: Playlist, val entries: List<PlaylistEntry>)

/** Playlists with their library tracks resolved; missing entries are those not in the library now. */
class PlaylistRepository(private val dao: PlaylistDao, private val library: LibraryRepository, scope: CoroutineScope) {
    val playlists: StateFlow<List<PlaylistSummary>?> =
        combine(dao.observePlaylists(), dao.observeEntryRefs(), library.tracksByUri) { playlists, refs, tracks ->
            val byPlaylist = refs.groupBy(EntryRef::playlistId)
            playlists.map { playlist ->
                val entries = byPlaylist[playlist.id].orEmpty()
                val playable = entries.mapNotNull { ref -> ref.documentUri?.let(tracks::get)?.let { ref.id to it } }
                PlaylistSummary(playlist, playable.map { it.second }, playable.map { it.first }, entries.size - playable.size)
            }
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /** One playlist with every entry, each resolved to its library track or missing (null). */
    fun page(id: Long): Flow<PlaylistPage?> =
        combine(dao.observePlaylist(id), dao.observeEntries(id), library.tracksByUri) { playlist, entries, tracks ->
            playlist?.let { PlaylistPage(it, entries.map { entry -> PlaylistRow(entry, entry.documentUri?.let(tracks::get)) }) }
        }.flowOn(Dispatchers.Default)

    /** Appends [tracks] (§7.3: at the end, duplicates allowed); the result says how many were there already. */
    suspend fun add(id: Long, tracks: List<LibraryTrack>): AddResult {
        val present = dao.documentUris(id).toHashSet()
        val start = dao.lastPosition(id) + 1
        val ids = dao.append(id, tracks.mapIndexed { i, track -> entryOf(id, start + i, track) }, System.currentTimeMillis())
        return AddResult(ids, tracks.count { it.documentUri in present })
    }

    /** Undo of [add]: exactly the entries it made. */
    suspend fun removeEntries(id: Long, entryIds: List<Long>) = dao.removeEntries(id, entryIds, System.currentTimeMillis())

    /** Replaces every entry with [tracks]; returns the old ones for Undo. */
    suspend fun replace(id: Long, tracks: List<LibraryTrack>): List<PlaylistEntry> {
        val old = dao.entries(id)
        dao.replaceEntries(id, tracks.mapIndexed { i, track -> entryOf(id, i, track) }, System.currentTimeMillis())
        return old
    }

    suspend fun restoreEntries(id: Long, entries: List<PlaylistEntry>) = dao.replaceEntries(id, entries, System.currentTimeMillis())

    suspend fun reorder(id: Long, entryIds: List<Long>) = dao.reorder(id, entryIds, System.currentTimeMillis())

    suspend fun removeEntry(entryId: Long): PlaylistEntry? {
        val entry = dao.entry(entryId) ?: return null
        dao.removeEntry(entry, System.currentTimeMillis())
        return entry
    }

    suspend fun restoreEntry(entry: PlaylistEntry) = dao.restoreEntry(entry, System.currentTimeMillis())

    /** Whether another playlist has [name]; names compare trimmed, ignoring case and diacritics (§7.4). */
    fun nameTaken(name: String, except: Long? = null): Boolean {
        val wanted = nameKey(name)
        return playlists.value.orEmpty().any { it.playlist.id != except && nameKey(it.playlist.name) == wanted }
    }

    suspend fun create(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(Playlist(name = name.trim(), createdAt = now, changedAt = now))
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim(), System.currentTimeMillis())

    suspend fun delete(id: Long): DeletedPlaylist? {
        val playlist = dao.playlist(id) ?: return null
        val deleted = DeletedPlaylist(playlist, dao.entries(id))
        dao.delete(id)
        return deleted
    }

    suspend fun restore(deleted: DeletedPlaylist) = dao.restore(deleted.playlist, deleted.entries)

    companion object {
        const val MAX_NAME = 100

        fun nameKey(name: String) = SearchIndex.normalize(name.trim())

        fun sort(list: List<PlaylistSummary>, sort: PlaylistSort, locale: Locale): List<PlaylistSummary> = when (sort) {
            PlaylistSort.RECENTLY_CHANGED -> list.sortedByDescending { it.playlist.changedAt }
            PlaylistSort.RECENTLY_ADDED -> list.sortedByDescending { it.playlist.createdAt }
            PlaylistSort.NAME -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
                list.sortedWith { a, b -> collator.compare(a.playlist.name, b.playlist.name) }
            }
        }
    }
}

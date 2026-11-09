package sh.hnet.music.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
data class PlaylistSummary(val playlist: Playlist, val tracks: List<LibraryTrack>, val missing: Int) {
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

/** A deleted playlist kept for Undo. */
class DeletedPlaylist(val playlist: Playlist, val entries: List<PlaylistEntry>)

/** Playlists with their library tracks resolved; missing entries are those not in the library now. */
class PlaylistRepository(private val dao: PlaylistDao, library: LibraryRepository, scope: CoroutineScope) {
    val playlists: StateFlow<List<PlaylistSummary>?> =
        combine(dao.observePlaylists(), dao.observeEntryRefs(), library.tracksByUri) { playlists, refs, tracks ->
            val byPlaylist = refs.groupBy(EntryRef::playlistId)
            playlists.map { playlist ->
                val entries = byPlaylist[playlist.id].orEmpty()
                val playable = entries.mapNotNull { ref -> ref.documentUri?.let(tracks::get) }
                PlaylistSummary(playlist, playable, entries.size - playable.size)
            }
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

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

package sh.hnet.music.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.library.DeletedPlaylist
import sh.hnet.music.library.AddResult
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.PlaylistPage
import sh.hnet.music.data.PlaylistEntry
import sh.hnet.music.library.PlaylistSort
import sh.hnet.music.library.PlaylistFormat
import sh.hnet.music.library.PlaylistTransfer
import sh.hnet.music.library.PlaylistSummary
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.Album
import sh.hnet.music.library.AlbumSort
import sh.hnet.music.library.Albums
import sh.hnet.music.library.Artist
import sh.hnet.music.library.Artists
import sh.hnet.music.library.Sorted
import sh.hnet.music.library.TrackSort
import sh.hnet.music.library.Tracks
import sh.hnet.music.ui.components.PlayState
import java.util.Locale

/** The library views' data and the playback actions they start; one instance for the activity. */
class BrowseViewModel(private val app: MusicApplication) : ViewModel() {
    private val repository = app.library
    private val player = app.player

    /** The Library's views in the user's order (Settings › Library views). */
    val viewOrder: StateFlow<List<LibraryView>> = app.preferences.viewOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryView.entries)

    /**
     * The view the Library shows; null until the start view is read. Kept here, not in saved
     * state: it lives as long as the activity's view model, so switching tabs keeps it and a new
     * process opens on the chosen start view (SPEC-settings-v2 §4.2).
     */
    val view = MutableStateFlow<LibraryView?>(null)

    /** Views shown before the current one: Back returns to them. */
    var viewHistory: List<LibraryView> = emptyList()
        private set

    init {
        viewModelScope.launch {
            val start = app.preferences.startView.first()
            view.value = start ?: app.preferences.lastView.first() ?: LibraryView.ALBUMS
        }
    }

    fun showView(chosen: LibraryView) {
        val current = view.value
        if (chosen == current) return
        if (current != null) viewHistory = viewHistory + current
        view.value = chosen
        viewModelScope.launch { app.preferences.setLastView(chosen) }
    }

    /** Back in the view history; false when there is none. */
    fun previousView(): Boolean {
        val previous = viewHistory.lastOrNull() ?: return false
        viewHistory = viewHistory.dropLast(1)
        view.value = previous
        viewModelScope.launch { app.preferences.setLastView(previous) }
        return true
    }

    fun setViewOrder(order: List<LibraryView>) {
        viewModelScope.launch { app.preferences.setViewOrder(order) }
    }

    val albumSort: StateFlow<AlbumSort> = app.preferences.albumSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlbumSort.ALBUM_ARTIST)

    val trackSort: StateFlow<TrackSort> = app.preferences.trackSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrackSort.TITLE)

    /** Sorted with their fast-scroller sections; null until the library has loaded. */
    val albums: StateFlow<Sorted<Album>?> = combine(repository.albums, albumSort) { albums, sort ->
        albums?.let {
            val locale = Locale.getDefault()
            val sorted = Albums.sort(it, sort, locale)
            Sorted(sorted, sorted.map { album -> Albums.section(album, sort, locale) })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Artists view's filter: only artists with an album of their own (on by default). */
    val artistsWithAlbumsOnly: StateFlow<Boolean> = app.preferences.artistsWithAlbumsOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setArtistsWithAlbumsOnly(on: Boolean) {
        viewModelScope.launch { app.preferences.setArtistsWithAlbumsOnly(on) }
    }

    /**
     * The Artists view's list: with the filter on, only artists with at least one album under their
     * own name – those who are only on compilations or others' albums stay out (they are still on
     * those albums' pages, in Search and on their own page).
     */
    val artists: StateFlow<Sorted<Artist>?> = combine(repository.artists, artistsWithAlbumsOnly) { list, withAlbums ->
        list?.let { all -> Artists.sort(if (withAlbums) all.filter { it.ownAlbums.isNotEmpty() } else all, Locale.getDefault()) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tracks: StateFlow<Sorted<LibraryTrack>?> = combine(repository.tracks, trackSort) { tracks, sort ->
        tracks?.let { Tracks.sort(it, sort, Locale.getDefault()) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasTracks: StateFlow<Boolean?> = repository.tracks
        .map { it?.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun album(key: String): Flow<Album?> = repository.albumsByKey.map { it[key] }

    fun artist(name: String): Flow<Artist?> = repository.artistsByKey.map { it[Artists.key(name)] }

    /** An artist's tracks in the Tracks view's order (the "Show all" page). */
    fun artistTracks(name: String): Flow<Sorted<LibraryTrack>?> = combine(artist(name), trackSort) { artist, sort ->
        artist?.let { Tracks.sort(it.tracks, sort, Locale.getDefault()) }
    }.flowOn(Dispatchers.Default)

    val playlistSort: StateFlow<PlaylistSort> = app.preferences.playlistSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistSort.RECENTLY_CHANGED)

    val playlists: StateFlow<List<PlaylistSummary>?> = combine(app.playlists.playlists, playlistSort) { list, sort ->
        list?.let { PlaylistRepository.sort(it, sort, Locale.getDefault()) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setPlaylistSort(sort: PlaylistSort) {
        viewModelScope.launch { app.preferences.setPlaylistSort(sort) }
    }

    /** Why [name] can't be used for a playlist (an error string), or null when it can (§7.4). */
    fun playlistNameError(name: String, except: Long? = null): Int? = when {
        name.isBlank() -> R.string.error_name_empty
        app.playlists.nameTaken(name, except) -> R.string.error_name_taken
        else -> null
    }

    suspend fun createPlaylist(name: String): Long = app.playlists.create(name)

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { app.playlists.rename(id, name) }
    }

    suspend fun deletePlaylist(id: Long): DeletedPlaylist? = app.playlists.delete(id)

    fun restorePlaylist(deleted: DeletedPlaylist) {
        viewModelScope.launch { app.playlists.restore(deleted) }
    }

    fun playlist(id: Long): Flow<PlaylistPage?> = app.playlists.page(id)

    /** The playlist entry playing (and whether it plays), when the queue came from a playlist. */
    val playingEntry: Flow<Pair<Long?, Boolean>> = player.state.map { it.currentEntryId to it.isPlaying }

    /** A copy of the playable entries goes into the queue (§12), each item knowing its entry. */
    fun playPlaylist(summary: PlaylistSummary, start: Int = 0, shuffle: Boolean? = null) =
        player.play(summary.tracks, start, shuffle, summary.entryIds)

    fun reorderPlaylist(id: Long, entryIds: List<Long>) {
        viewModelScope.launch { app.playlists.reorder(id, entryIds) }
    }

    suspend fun removeEntry(entryId: Long): PlaylistEntry? = app.playlists.removeEntry(entryId)

    fun restoreEntry(entry: PlaylistEntry) {
        viewModelScope.launch { app.playlists.restoreEntry(entry) }
    }

    suspend fun addToPlaylist(id: Long, tracks: List<LibraryTrack>): AddResult = app.playlists.add(id, tracks)

    fun undoAdd(id: Long, entryIds: List<Long>) = removeEntries(id, entryIds)

    fun removeEntries(id: Long, entryIds: List<Long>) {
        viewModelScope.launch { app.playlists.removeEntries(id, entryIds) }
    }

    suspend fun createPlaylistWith(name: String, tracks: List<LibraryTrack>): Long {
        val id = app.playlists.create(name)
        if (tracks.isNotEmpty()) app.playlists.add(id, tracks)
        return id
    }

    suspend fun replacePlaylist(id: Long, tracks: List<LibraryTrack>): List<PlaylistEntry> = app.playlists.replace(id, tracks)

    fun restoreReplaced(id: Long, entries: List<PlaylistEntry>) {
        viewModelScope.launch { app.playlists.restoreEntries(id, entries) }
    }

    /** Every playlist but Starred (starring is how tracks get in), recently changed first (§7.2). */
    val playlistsByChange: StateFlow<List<PlaylistSummary>> = app.playlists.playlists
        .map { it.orEmpty().filter { summary -> !summary.starred }.sortedByDescending { summary -> summary.playlist.changedAt } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun importPlaylist(uri: android.net.Uri): PlaylistTransfer.Result = app.transfer.import(uri)

    suspend fun exportPlaylist(id: Long, format: PlaylistFormat, uri: android.net.Uri): PlaylistTransfer.Result {
        app.preferences.setExportFormat(format)
        return app.transfer.export(id, format, uri)
    }

    val exportFormat: StateFlow<PlaylistFormat> = app.preferences.exportFormat.stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistFormat.M3U8)

    /** The tracks in Starred (SPEC-now-playing-v2 §3). */
    val starred: StateFlow<Set<String>> = app.playlists.starred

    fun setStarred(track: LibraryTrack, on: Boolean) {
        viewModelScope.launch { if (on) app.playlists.star(track) else app.playlists.unstar(track.documentUri) }
    }

    /** Unstars and returns what came out, for Undo (§6: swiping in Starred). */
    suspend fun unstar(documentUri: String): List<PlaylistEntry> = app.playlists.unstar(documentUri)

    fun playNext(tracks: List<LibraryTrack>) = player.playNext(tracks)

    fun addToQueue(tracks: List<LibraryTrack>) = player.addToQueue(tracks)

    /** The current track and whether it plays, for marking it in lists. */
    val playState: Flow<Pair<String?, Boolean>> = combine(player.currentId, player.isPlaying) { id, playing -> id to playing }

    init {
        player.connect()
    }

    fun setAlbumSort(sort: AlbumSort) {
        viewModelScope.launch { app.preferences.setAlbumSort(sort) }
    }

    fun setTrackSort(sort: TrackSort) {
        viewModelScope.launch { app.preferences.setTrackSort(sort) }
    }

    /** Play: from the first track, shuffle off; Shuffle: shuffled from a random track (SPEC §4.3). */
    fun play(tracks: List<LibraryTrack>, shuffle: Boolean) = player.play(tracks, 0, shuffle)

    /** A tapped row: its list from that row, shuffle as it was (SPEC §5.6). */
    fun playFrom(tracks: List<LibraryTrack>, index: Int) = player.play(tracks, index)

    companion object {
        fun stateOf(track: LibraryTrack, current: Pair<String?, Boolean>): PlayState = when {
            track.documentUri != current.first -> PlayState.NONE
            current.second -> PlayState.PLAYING
            else -> PlayState.PAUSED
        }
    }
}

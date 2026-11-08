package sh.hnet.music.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
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

    val artists: StateFlow<Sorted<Artist>?> = repository.artists
        .map { it?.let { list -> Artists.sort(list, Locale.getDefault()) } }
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

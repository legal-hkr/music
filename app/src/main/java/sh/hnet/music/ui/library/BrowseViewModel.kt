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
import sh.hnet.music.ui.components.PlayState
import java.util.Locale

/** The library views' data and the playback actions they start; one instance for the activity. */
class BrowseViewModel(private val app: MusicApplication) : ViewModel() {
    private val repository = app.library
    private val player = app.player

    val albumSort: StateFlow<AlbumSort> = app.preferences.albumSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlbumSort.ALBUM_ARTIST)

    /** Null until the library has loaded. */
    val albums: StateFlow<List<Album>?> = combine(repository.albums, albumSort) { albums, sort ->
        albums?.let { Albums.sort(it, sort, Locale.getDefault()) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasTracks: StateFlow<Boolean?> = repository.tracks
        .map { it?.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun album(key: String): Flow<Album?> = repository.albumsByKey.map { it[key] }

    /** A track's relation to what plays now, for lists. */
    val playState: Flow<Pair<String?, Boolean>> = combine(player.currentId, player.isPlaying) { id, playing -> id to playing }

    init {
        player.connect()
    }

    fun setAlbumSort(sort: AlbumSort) {
        viewModelScope.launch { app.preferences.setAlbumSort(sort) }
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

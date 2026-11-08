package sh.hnet.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.Track
import sh.hnet.music.playback.PlayerState

/** The mini player's and now playing's view of the player; one instance for the activity. */
class PlayerViewModel(app: MusicApplication) : ViewModel() {
    val player = app.player
    private val dao = app.database.trackDao()
    private val folders = app.database.libraryFolderDao()

    val state: StateFlow<PlayerState> = player.state

    val queue: StateFlow<sh.hnet.music.playback.QueueSnapshot> = player.queue

    val tracks: StateFlow<Map<String, LibraryTrack>> = app.library.tracksByUri

    /** The current track as the library lists it (title, artists, cover). */
    val current: StateFlow<LibraryTrack?> = combine(player.state, app.library.tracksByUri) { state, tracks ->
        state.currentId?.let { tracks[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The current track with its technical details, for the chip and the details page. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val details: StateFlow<Track?> = player.state.map { it.currentId }
        .flatMapLatest { id -> id?.let(dao::observeTrack) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun folderName(folderUri: String): Flow<String?> = folders.observeAll().map { list -> list.firstOrNull { it.treeUri == folderUri }?.displayName }

    init {
        player.connect()
    }
}

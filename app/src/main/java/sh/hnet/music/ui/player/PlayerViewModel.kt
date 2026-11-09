package sh.hnet.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.Track
import sh.hnet.music.playback.PlayerState
import sh.hnet.music.playback.AppliedGain
import sh.hnet.music.playback.ReplayGain
import sh.hnet.music.playback.albumInOrder
import sh.hnet.music.playback.gainTags
import sh.hnet.music.library.Albums

/** The mini player's and now playing's view of the player; one instance for the activity. */
class PlayerViewModel(app: MusicApplication) : ViewModel() {
    val player = app.player
    private val dao = app.database.trackDao()
    private val folders = app.database.libraryFolderDao()

    val state: StateFlow<PlayerState> = player.state

    val queue: StateFlow<sh.hnet.music.playback.QueueSnapshot> = player.queue

    val tracks: StateFlow<Map<String, LibraryTrack>> = app.library.playableByUri

    /** The current track as the library lists it (title, artists, cover). */
    val current: StateFlow<LibraryTrack?> = combine(player.state, app.library.playableByUri) { state, tracks ->
        state.currentId?.let { tracks[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The current track with its technical details, for the chip and the details page. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val details: StateFlow<Track?> = player.state.map { it.currentId }
        .flatMapLatest { id -> id?.let(dao::observeTrack) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The ReplayGain applied to the current track, as the service computes it, for the chip. */
    val gain: StateFlow<AppliedGain?> = combine(details, app.preferences.gain, player.queue, app.library.playableByUri) { track, settings, queue, tracks ->
        track ?: return@combine null
        val keyAt = { i: Int -> queue.items.getOrNull(i)?.let { tracks[it.mediaId] }?.takeIf { !it.album.isNullOrBlank() }?.let(Albums::keyOf) }
        val shuffle = player.state.value.shuffle
        ReplayGain.compute(track.gainTags(), settings, albumInOrder(shuffle, keyAt, queue.current, queue.items.size))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun folderName(folderUri: String): Flow<String?> = folders.observeAll().map { list -> list.firstOrNull { it.treeUri == folderUri }?.displayName }

    init {
        player.connect()
        // The tracks either side of the current one in play order get their covers loaded ahead
        viewModelScope.launch {
            combine(player.queue, app.library.playableByUri) { queue, tracks ->
                listOf(queue.current + 1, queue.current - 1).mapNotNull { i -> queue.items.getOrNull(i)?.let { tracks[it.mediaId] } }
            }.distinctUntilChanged().collectLatest { neighbours -> neighbours.forEach { app.covers.prefetch(it) } }
        }
    }
}

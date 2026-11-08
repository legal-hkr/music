package sh.hnet.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.music.data.LibraryTrack

/** What the UI shows of the player; [currentId] null when the queue is empty. */
data class PlayerState(
    val currentId: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val queueSize: Int = 0,
)

/** The UI's side of playback: a controller of [PlaybackService], connected once for the process. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    /** Document URI of the current track (playing or paused). */
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private fun sync(player: Player) {
        val id = player.currentMediaItem?.mediaId
        _currentId.value = id
        _isPlaying.value = player.isPlaying
        _state.value = PlayerState(
            currentId = id,
            isPlaying = player.isPlaying,
            durationMs = player.duration.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            queueSize = player.mediaItemCount,
        )
    }

    /** Runs [action] on the controller, connecting first when needed. Main thread only. */
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        pending += action
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val connected = runCatching { future.get() }.getOrNull() ?: run { pending.clear(); return@addListener }
            controller = connected
            connected.addListener(listener)
            sync(connected)
            pending.toList().forEach { it(connected) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Connects early, so lists can mark the current track as soon as they show. */
    fun connect() = withController {}

    /** The current position; read on demand (the seek bar polls it while visible). */
    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0

    /**
     * Replaces the queue with [tracks] and plays from [start]. [shuffle] null keeps the current
     * shuffle mode; true starts at a random track.
     */
    fun play(tracks: List<LibraryTrack>, start: Int = 0, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        withController { c ->
            shuffle?.let { c.shuffleModeEnabled = it }
            val index = if (shuffle == true) tracks.indices.random() else start.coerceIn(tracks.indices)
            c.setMediaItems(tracks.map(::item), index, 0)
            c.prepare()
            c.play()
        }
    }

    /** Inserts after the current track; starts nothing when the queue was empty. */
    fun playNext(track: LibraryTrack) = withController { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item(track))
            c.prepare()
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, item(track))
        }
    }

    fun addToQueue(track: LibraryTrack) = withController { c ->
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItem(item(track))
        if (wasEmpty) c.prepare()
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    /** Within the first 3 s the previous track, after that the start of this one (Media3's default). */
    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    /** Off, all, one, off… */
    fun cycleRepeat() = withController { it.repeatMode = nextRepeatMode(it.repeatMode) }

    private fun item(track: LibraryTrack): MediaItem = MediaItem.Builder()
        .setMediaId(track.documentUri)
        .setUri(track.documentUri.toUri())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.displayTitle)
                .setArtist(track.artists.joinToString(", ").ifEmpty { null })
                .setAlbumTitle(track.album)
                .setAlbumArtist(track.albumArtists.joinToString(", ").ifEmpty { null })
                .setTrackNumber(track.trackNumber)
                .setDiscNumber(track.discNumber)
                .build(),
        )
        .build()

    companion object {
        fun nextRepeatMode(mode: Int) = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}

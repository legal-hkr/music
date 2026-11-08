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

/** The UI's side of playback: a controller of [PlaybackService], connected once for the process. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _currentId = MutableStateFlow<String?>(null)
    /** Document URI of the current track (playing or paused). */
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private fun sync(player: Player) {
        _currentId.value = player.currentMediaItem?.mediaId
        _isPlaying.value = player.isPlaying
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
}

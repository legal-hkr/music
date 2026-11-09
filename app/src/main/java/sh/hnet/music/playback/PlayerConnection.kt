package sh.hnet.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
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

/** A queued item: its place in the player's list ([index]) and what it is. */
data class QueueItem(val index: Int, val mediaId: String, val title: String?, val artists: String?)

/**
 * The queue in the order it plays (shuffled when shuffle is on); [current] is the position of the
 * current item in [items], -1 when empty.
 */
data class QueueSnapshot(val items: List<QueueItem> = emptyList(), val current: Int = -1)

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

    private val _queue = MutableStateFlow(QueueSnapshot())
    val queue: StateFlow<QueueSnapshot> = _queue.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) syncQueue(player)
        }
    }

    /** The items in play order, walking the timeline with the player's own shuffle order. */
    private fun syncQueue(player: Player) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            _queue.value = QueueSnapshot()
            return
        }
        val shuffle = player.shuffleModeEnabled
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != androidx.media3.common.C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        val items = order.map { i ->
            val item = player.getMediaItemAt(i)
            QueueItem(i, item.mediaId, item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString())
        }
        _queue.value = QueueSnapshot(items, order.indexOf(player.currentMediaItemIndex))
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
            syncQueue(connected)
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
    fun playNext(track: LibraryTrack) = playNext(listOf(track))

    fun playNext(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItems(tracks.map(::item))
                c.prepare()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map(::item))
            }
        }
    }

    fun addToQueue(track: LibraryTrack) = addToQueue(listOf(track))

    fun addToQueue(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return
        withController { c ->
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(tracks.map(::item))
            if (wasEmpty) c.prepare()
        }
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

    /**
     * Plays the queue's item at [index] (the player's list index) from its start. Nothing is
     * removed: the tracks before it become history, hidden from the queue sheet (which starts at
     * the current track) but still reached by Previous, one by one in the queue's order.
     */
    fun jumpTo(index: Int) = withController { c ->
        c.seekToDefaultPosition(index)
        c.play()
    }

    /** Removes the item at [index]; true when there was one. */
    fun remove(index: Int): Boolean {
        val c = controller ?: return false
        if (index !in 0 until c.mediaItemCount) return false
        c.removeMediaItem(index)
        return true
    }

    /**
     * Puts [track] at [index] (Undo of a removal). Built anew from the library: items read back
     * through the controller carry no file URI, and the session refuses items without one.
     */
    fun insert(index: Int, track: LibraryTrack) = withController { c -> c.addMediaItem(index.coerceIn(0, c.mediaItemCount), item(track)) }

    /** Moves an item in the player's list; called once, when a dragged row is dropped. */
    fun move(from: Int, to: Int) = withController { c -> if (from != to) c.moveMediaItem(from, to) }

    /** Clears everything except the current track, which keeps playing (SPEC §10). */
    fun clearAroundCurrent() = withController { c ->
        val current = c.currentMediaItemIndex
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    private fun item(track: LibraryTrack): MediaItem = track.toMediaItem()

    companion object {
        fun nextRepeatMode(mode: Int) = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}

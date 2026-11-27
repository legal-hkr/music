package sh.hnet.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** The player reached the end of the queue with repeat off (SPEC-now-playing-v2 §5.2). */
    val ended: Boolean = false,
    val queueSize: Int = 0,
    /** The playlist entry the current item was started from, if any. */
    val currentEntryId: Long? = null,
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

    private val _connected = MutableStateFlow(false)
    /** Whether the player's state is known yet: before that, an empty queue means nothing (the widgets wait for it). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _queue = MutableStateFlow(QueueSnapshot())
    val queue: StateFlow<QueueSnapshot> = _queue.asStateFlow()

    private val _problems = MutableSharedFlow<PlaybackProblem>(extraBufferCapacity = 8)
    /** Tracks the service couldn't play; only while someone collects (the app on screen), nothing is kept for later. */
    val problems: SharedFlow<PlaybackProblem> = _problems.asSharedFlow()

    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (command.customAction != PlaybackProblem.ACTION) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            PlaybackProblem.from(args)?.let { _problems.tryEmit(it) }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

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
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
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
            // Repeat one: Next starts the track again, so there always is one
            hasNext = player.hasNextMediaItem() || (player.repeatMode == Player.REPEAT_MODE_ONE && player.mediaItemCount > 0),
            ended = player.playbackState == Player.STATE_ENDED,
            queueSize = player.mediaItemCount,
            currentEntryId = player.currentMediaItem?.playlistEntryId,
        )
    }

    /** Runs [action] on the controller, connecting first when needed. Main thread only. */
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        pending += action
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        future.addListener({
            connecting = false
            val connected = runCatching { future.get() }.getOrNull() ?: run { pending.clear(); return@addListener }
            controller = connected
            connected.addListener(listener)
            sync(connected)
            syncQueue(connected)
            _connected.value = true
            pending.toList().forEach { it(connected) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Connects early, so lists can mark the current track as soon as they show. */
    fun connect() = withController {}

    /** Runs [done] once everything asked for before it has gone to the player. */
    fun whenConnected(done: () -> Unit) = withController { done() }

    /** The current position; read on demand (the seek bar polls it while visible). */
    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0

    /**
     * Replaces the queue with [tracks] and plays from [start]. [shuffle] null keeps the current
     * shuffle mode; true starts at a random track.
     */
    fun play(tracks: List<LibraryTrack>, start: Int = 0, shuffle: Boolean? = null, playlistEntries: List<Long>? = null) {
        if (tracks.isEmpty()) return
        withController { c ->
            shuffle?.let { c.shuffleModeEnabled = it }
            val index = if (shuffle == true) tracks.indices.random() else start.coerceIn(tracks.indices)
            // Played shuffled, the service shuffles the new queue from this track once it has it
            c.setMediaItems(tracks.mapIndexed { i, track -> track.toMediaItem(playlistEntries?.getOrNull(i)) }, index, 0)
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

    fun togglePlayPause() = withController { c -> if (c.isPlaying) c.pause() else resume(c) }

    /** Plays, whatever the state (a widget's Play: what it shows may be older than the player's state). */
    fun play() = withController(::resume)

    /** Pauses, whatever the state (a widget's Pause). */
    fun pause() = withController { it.pause() }

    private fun resume(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
        c.play()
    }

    /** Repeat one: the current track again (the queue is just this track then). */
    fun next() = withController { if (it.repeatMode == Player.REPEAT_MODE_ONE) it.seekTo(0) else it.seekToNext() }

    /** After the queue ended: from its first track (§5.2). */
    fun playFromStart() = withController { c ->
        if (c.mediaItemCount == 0) return@withController
        c.seekTo(if (c.shuffleModeEnabled) c.currentTimeline.getFirstWindowIndex(true) else 0, 0)
        c.prepare()
        c.play()
    }

    /** Within the first 3 s the previous track, after that the start of this one (Media3's default). */
    fun previous() = withController { if (it.repeatMode == Player.REPEAT_MODE_ONE) it.seekTo(0) else it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    /**
     * Shuffle on: the current track stays where it is and every other one follows it in random
     * order (the queue sheet shows them all). Off: the queue's own order again, from the current
     * track's place in it – shuffling never changes that order.
     */
    fun setShuffle(enabled: Boolean) = withController { c ->
        c.shuffleModeEnabled = enabled
        if (enabled) reshuffle(c)
    }

    private fun reshuffle(c: MediaController) {
        c.sendCustomCommand(SessionCommand(QueueShuffleOrder.ACTION_RESHUFFLE, Bundle.EMPTY), Bundle.EMPTY)
    }

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

    /**
     * Clears everything except the current track, which keeps playing (SPEC §10). Done by the service,
     * which keeps what went – items and shuffled order – for [undoClear].
     */
    fun clearAroundCurrent() = withController { it.sendCustomCommand(SessionCommand(QueueCommands.ACTION_CLEAR, Bundle.EMPTY), Bundle.EMPTY) }

    /** Undo of [clearAroundCurrent]: the queue exactly as it was, shuffled order included. */
    fun undoClear() = withController { it.sendCustomCommand(SessionCommand(QueueCommands.ACTION_UNDO_CLEAR, Bundle.EMPTY), Bundle.EMPTY) }

    private fun item(track: LibraryTrack): MediaItem = track.toMediaItem()

    companion object {
        fun nextRepeatMode(mode: Int) = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}

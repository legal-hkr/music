package sh.hnet.music.playback

import sh.hnet.music.DSD_PCM_RATES
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.music.MusicApplication
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import sh.hnet.music.MainActivity
import sh.hnet.music.R

/** Playback in the background: one ExoPlayer and its media session. */
// Custom notification buttons (icons, slots) are still marked unstable in Media3
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()
    private val store by lazy { QueueStore(this) }
    private var saveJob: Job? = null
    private var restored = false
    /** The ExoPlayer itself (the session holds it wrapped in RepeatOnePlayer). */
    private var exoPlayer: ExoPlayer? = null

    private val shuffleCommand = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
    private val reshuffleCommand = SessionCommand(QueueShuffleOrder.ACTION_RESHUFFLE, Bundle.EMPTY)
    private val clearCommand = SessionCommand(QueueCommands.ACTION_CLEAR, Bundle.EMPTY)
    private val undoClearCommand = SessionCommand(QueueCommands.ACTION_UNDO_CLEAR, Bundle.EMPTY)
    /** The PCM rate DSD files are converted to (Settings › Playback); read on the loading thread. */
    @Volatile private var dsdPcmRate = DSD_PCM_RATES.first()
    /** Files that failed one after another with nothing playing in between. */
    private var failedInARow = 0
    /** The last Clear queue, until its Undo or the next one. */
    private var cleared: ClearedQueue? = null

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val gainSink = ReplayGainRenderersFactory.sink(this)
        val player = ExoPlayer.Builder(this, ReplayGainRenderersFactory(this, gainSink))
            // DSD (DSF, DSDIFF) is converted to PCM by our own extractor, asked before Media3's
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, DsdExtractorsFactory { dsdPcmRate }))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Our shuffle order: the current track first, inserted tracks where the queue shows them
        player.shuffleOrder = QueueShuffleOrder(IntArray(0))
        // Tapping the notification opens the app on the now-playing screen
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        exoPlayer = player
        // Repeat one: Next and Previous start the track again, wherever they come from
        session = MediaSession.Builder(this, RepeatOnePlayer(player))
            .setSessionActivity(open)
            .setCallback(Callback())
            .setMediaButtonPreferences(buttons(player))
            .build()
        // Shuffle and repeat buttons in the notification follow the player's modes
        player.addListener(object : Player.Listener {
            // Shuffle off needs nothing more: shuffling never changes the list, so the current track is
            // back at its own place and what followed it there comes next (owner's decision)
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                refreshButtons()
                // The switch may arrive after the new queue it was meant for: shuffle that queue now
                if (shuffleModeEnabled && player.mediaItemCount > 0 && (player.shuffleOrder as? QueueShuffleOrder)?.fresh == true) shuffleFromCurrent(player)
            }
            override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()

            // A new queue while shuffle is on (Shuffle on an album, or anything played shuffled):
            // shuffled from its first track the moment it is the player's
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled && player.mediaItemCount > 0 &&
                    (player.shuffleOrder as? QueueShuffleOrder)?.fresh == true
                ) shuffleFromCurrent(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) failedInARow = 0
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                    )
                ) scheduleSave()
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) gainSink.shuffle = player.shuffleModeEnabled
            }

            // A file that can't be played (gone, unreadable) is skipped rather than stopping the queue –
            // but not round and round when nothing in it plays (repeat all over unreadable files)
            override fun onPlayerError(error: PlaybackException) {
                failedInARow++
                if (failedInARow < player.mediaItemCount && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        scope.launch {
            (application as MusicApplication).preferences.gain.collect { gainSink.settings = it }
        }
        // Read by the extractor when it opens a file: a new rate applies from the next one
        scope.launch {
            (application as MusicApplication).preferences.dsdPcmRate.collect { dsdPcmRate = it }
        }
        // Play counts: a listen of at least half a track (checked every second while playing)
        val trackDao = (application as MusicApplication).database.trackDao()
        val counter = PlayCounter(player) { uri -> scope.launch(Dispatchers.IO) { trackDao.countPlay(uri) } }
        player.addListener(counter)
        scope.launch { restore(player) }
        // One clock while playing: the play count checked every second, the position saved every
        // SAVE_EVERY_MS so a killed process loses little
        scope.launch {
            var tick = 0L
            while (true) {
                delay(TICK_MS)
                if (!player.isPlaying) continue
                counter.check()
                if (++tick % (SAVE_EVERY_MS / TICK_MS) == 0L) scheduleSave()
            }
        }
    }

    /** The queue as it stands; null when empty. Main thread (reads the player). */
    private fun snapshot(player: Player): SavedQueue? {
        if (player.mediaItemCount == 0) return null
        val timeline = player.currentTimeline
        val order = if (player.shuffleModeEnabled && !timeline.isEmpty) {
            buildList {
                var i = timeline.getFirstWindowIndex(true)
                while (i != C.INDEX_UNSET && size < timeline.windowCount) {
                    add(i)
                    i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
                }
            }
        } else emptyList()
        return SavedQueue(
            uris = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            entryIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).playlistEntryId },
            current = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            shuffleOrder = order,
        )
    }

    private fun scheduleSave() {
        // Nothing is saved before the saved queue was looked at, or an empty start would erase it
        if (!restored) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val queue = session?.player?.let(::snapshot)
            withContext(Dispatchers.IO) { store.save(queue) }
        }
    }

    /** Puts back the saved queue, paused where it was, unless something was queued meanwhile. */
    private suspend fun restore(player: ExoPlayer) {
        try {
            val saved = withContext(Dispatchers.IO) { store.load() } ?: return
            if (player.mediaItemCount > 0) return
            val dao = (application as MusicApplication).database.trackDao()
            val known = withContext(Dispatchers.IO) {
                saved.uris.distinct().chunked(500).flatMap { dao.libraryTracks(it) }.associateBy { it.documentUri }
            }
            if (player.mediaItemCount > 0) return
            // Tracks that left the library are dropped; the current one may be among them
            val kept = saved.uris.withIndex().filter { known.containsKey(it.value) }
            if (kept.isEmpty()) return
            val currentKept = kept.indexOfFirst { it.index == saved.current }
            val start = if (currentKept >= 0) currentKept else kept.indexOfFirst { it.index > saved.current }.coerceAtLeast(0)
            // With the playlist entries they were played from, so the playlist page marks the playing one
            player.setMediaItems(kept.map { known.getValue(it.value).toMediaItem(saved.entryIds.getOrNull(it.index)) }, start, if (currentKept >= 0) saved.positionMs else 0)
            if (saved.shuffle && kept.size == saved.uris.size && saved.shuffleOrder.size == kept.size) {
                player.shuffleOrder = QueueShuffleOrder(saved.shuffleOrder.toIntArray())
            } else if (saved.shuffle) {
                // Some tracks left: shuffled anew from the current one
                player.shuffleOrder = QueueShuffleOrder.fromCurrent(kept.size, start)
            }
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            player.prepare()
        } finally {
            restored = true
        }
    }

    /** Clear queue (SPEC §10): all but the current track goes, kept with the shuffle order for Undo. */
    private fun clearQueue(player: ExoPlayer) {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count < 2 || current !in 0 until count) return
        cleared = ClearedQueue(
            before = (0 until current).map(player::getMediaItemAt),
            after = (current + 1 until count).map(player::getMediaItemAt),
            currentId = player.getMediaItemAt(current).mediaId,
            order = (player.shuffleOrder as? QueueShuffleOrder)?.toList()?.toIntArray()?.takeIf { it.size == count },
        )
        if (current + 1 < count) player.removeMediaItems(current + 1, count)
        if (current > 0) player.removeMediaItems(0, current)
    }

    /**
     * Undo of Clear queue: the same items in the same places, in the same shuffled order. Only around
     * the track it was cleared around: a queue played since (an album, say) isn't mixed with the old one.
     */
    private fun undoClear(player: ExoPlayer) {
        val undo = cleared ?: return
        cleared = null
        if (player.mediaItemCount != 1 || player.currentMediaItem?.mediaId != undo.currentId) return
        if (undo.before.isNotEmpty()) player.addMediaItems(0, undo.before)
        if (undo.after.isNotEmpty()) player.addMediaItems(player.currentMediaItemIndex + 1, undo.after)
        val order = undo.order
        player.shuffleOrder = if (order != null && order.size == player.mediaItemCount) QueueShuffleOrder(order)
        else QueueShuffleOrder.fromCurrent(player.mediaItemCount, player.currentMediaItemIndex)
    }

    private fun refreshButtons() {
        val current = session ?: return
        current.setMediaButtonPreferences(buttons(current.player))
    }

    /**
     * Shuffle and Repeat as custom actions (SPEC §9.6), showing their state. In the overflow slot:
     * the back/forward slots would replace previous/next, and Media3 doesn't pass the secondary
     * slots on to the platform's controls (notification, lock screen).
     */
    private fun buttons(player: Player): List<CommandButton> {
        val shuffle = CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.desc_shuffle_on_action else R.string.desc_shuffle_off_action))
            .setSessionCommand(shuffleCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val (icon, label) = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to R.string.desc_repeat_all_action
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to R.string.desc_repeat_one_action
            else -> CommandButton.ICON_REPEAT_OFF to R.string.desc_repeat_off_action
        }
        val repeat = CommandButton.Builder(icon)
            .setDisplayName(getString(label))
            .setSessionCommand(repeatCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        return listOf(shuffle, repeat)
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    // The defaults for the controller's trust, plus Shuffle and Repeat
                    (if (controller.isTrusted) MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS else MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS)
                        .buildUpon()
                        .add(shuffleCommand)
                        .add(repeatCommand)
                        .add(reshuffleCommand)
                        .add(clearCommand)
                        .add(undoClearCommand)
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                ACTION_SHUFFLE -> {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                    if (player.shuffleModeEnabled) exoPlayer?.let(::shuffleFromCurrent)
                }
                // Not conditional on shuffle being on: the controller's switch may arrive after this
                // command; with shuffle off the order is simply unused
                QueueShuffleOrder.ACTION_RESHUFFLE -> exoPlayer?.let(::shuffleFromCurrent)
                QueueCommands.ACTION_CLEAR -> exoPlayer?.let(::clearQueue)
                QueueCommands.ACTION_UNDO_CLEAR -> exoPlayer?.let(::undoClear)
                ACTION_REPEAT -> player.repeatMode = PlayerConnection.nextRepeatMode(player.repeatMode)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // Last chance: written at once, the scope is about to go
        if (restored) session?.player?.let { store.save(snapshot(it)) }
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val SAVE_EVERY_MS = 10_000L
        const val ACTION_SHUFFLE = "sh.hnet.music.SHUFFLE"
        const val ACTION_REPEAT = "sh.hnet.music.REPEAT"

        /** The current track first, the rest after it in random order (QueueShuffleOrder). */
        fun shuffleFromCurrent(player: ExoPlayer) {
            player.shuffleOrder = QueueShuffleOrder.fromCurrent(player.mediaItemCount, player.currentMediaItemIndex)
        }
    }
}

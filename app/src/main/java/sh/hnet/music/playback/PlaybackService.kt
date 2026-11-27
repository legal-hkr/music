package sh.hnet.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.music.DSD_PCM_RATES
import sh.hnet.music.MainActivity
import sh.hnet.music.MusicApplication
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
    @Volatile private var dsdBoost = true
    @Volatile private var countPlays = true
    /** Queue positions that failed with nothing playing in between: skipping stops when the next one is among them. */
    private val failed = HashSet<Int>()
    /** The last Clear queue, until its Undo or the next one. */
    private var cleared: ClearedQueue? = null

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val bitPerfect = (application as MusicApplication).bitPerfect
        val gainSink = ReplayGainRenderersFactory.sink(this, bitPerfect)
        val player = ExoPlayer.Builder(this, ReplayGainRenderersFactory(this, gainSink))
            // DSD (DSF, DSDIFF) is converted to PCM by our own extractor, asked before Media3's
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    this,
                    DsdExtractorsFactory(
                        object : DsdPlayback {
                            override fun pcmRate() = dsdPcmRate
                            override fun pcmGainDb() = if (dsdBoost) 6f else 0f
                            override fun asDop(dsdRate: Int, channels: Int) = bitPerfect.wantsDop(dsdRate, channels)
                        },
                    ),
                ),
            )
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
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                // Positions mean other tracks now, and a changed queue is another try
                failed.clear()
                if (player.shuffleModeEnabled && player.mediaItemCount > 0 && (player.shuffleOrder as? QueueShuffleOrder)?.fresh == true) shuffleFromCurrent(player)
            }

            // A file whose audio no decoder on this phone takes: Media3 plays it as silence, with no error. It is
            // skipped as a failed one (owner's decision 27.11.2026: nothing plays silently, nothing is skipped silently)
            override fun onTracksChanged(tracks: Tracks) = skipIfSilent(player, tracks)

            // Play on a track stopped at for want of a decoder: tried again, and said again, rather than silence
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) skipIfSilent(player, player.currentTracks)
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

            // A file that can't be played (gone, unreadable) is skipped rather than stopping the queue
            override fun onPlayerError(error: PlaybackException) {
                // The output, not the file: every next track would fail the same way, so it stops here
                if (PlaybackProblem.isOutputError(error.errorCode)) {
                    player.pause()
                    report(PlaybackProblem(PlaybackProblem.Kind.OUTPUT, player.currentMediaItem?.mediaMetadata?.title?.toString()))
                    return
                }
                // Access to the folder lost: SAF says so with a SecurityException, which Media3 reports as an unspecified IO error
                val noAccess = generateSequence<Throwable>(error) { it.cause }.any { it is SecurityException }
                skipFailed(player, if (noAccess) PlaybackProblem.Kind.NO_ACCESS else PlaybackProblem.kindOf(error.errorCode))
            }
        })
        scope.launch {
            (application as MusicApplication).preferences.gain.collect { gainSink.settings = it }
        }
        // Bit-perfect output: the devices' switches, USB outputs coming and going, music sent to another output.
        // When that changes how what plays goes out, the output is opened again, so the change is heard (and
        // shown) at once
        bitPerfect.start()
        bitPerfect.onOutputChanged = { reopenOutput(player) }
        val outputs = (application as MusicApplication).outputs
        scope.launch {
            outputs.devices.collect { state ->
                val keys = state.bitPerfectKeys
                if (bitPerfect.enabledKeys != keys) {
                    bitPerfect.enabledKeys = keys
                    bitPerfect.refresh()
                }
            }
        }
        // Devices are remembered once music plays on them (SPEC-output §2.1)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) outputs.played()
            }
        })
        scope.launch {
            outputs.system.map { it.current }.distinctUntilChanged().collect {
                if (player.isPlaying) outputs.played()
                // Music sent elsewhere while a USB device stays connected, or back to it: bit-perfect follows
                bitPerfect.refresh()
            }
        }
        // The equalizer follows the device: its preset is heard as soon as the output changes (SPEC-output §2.2)
        scope.launch {
            outputs.system.map { it.current?.key }.distinctUntilChanged().collect { key ->
                if (key != null) (application as MusicApplication).equalizer.update { it.withDevice(key) }
            }
        }
        // The equalizer: heard at once, whatever changes it
        scope.launch {
            (application as MusicApplication).equalizer.state.collect { gainSink.equalizer = it.applied }
        }
        // Read by the extractor when it opens a file: a new rate applies from the next one
        scope.launch {
            (application as MusicApplication).preferences.dsdPcmRate.collect { dsdPcmRate = it }
        }
        scope.launch {
            (application as MusicApplication).preferences.dsdBoost.collect { dsdBoost = it }
        }
        scope.launch {
            (application as MusicApplication).preferences.countPlays.collect { countPlays = it }
        }
        // Play counts: a listen of at least half a track (checked every second while playing), unless
        // counting is switched off at the moment it would count
        val trackDao = (application as MusicApplication).database.trackDao()
        val counter = PlayCounter(player) { uri -> if (countPlays) scope.launch(Dispatchers.IO) { trackDao.countPlay(uri) } }
        player.addListener(counter)
        scope.launch { restore(player) }
        // Whatever started the service, placed Now playing widgets show the player from here on
        (application as MusicApplication).widgets.playerStarted()
        // One clock while playing: the play count checked every second, the position saved every
        // SAVE_EVERY_MS so a killed process loses little
        scope.launch {
            var tick = 0L
            while (true) {
                delay(TICK_MS)
                if (!player.isPlaying) continue
                // A track that really plays ends a run of failures (not the moment a skipped one starts: a file
                // without a decoder is "playing" until its tracks are known)
                if (player.currentPosition >= TICK_MS) failed.clear()
                counter.check()
                if (++tick % (SAVE_EVERY_MS / TICK_MS) == 0L) scheduleSave()
            }
        }
    }

    /**
     * The current track failed ([kind]): the next one plays, and Music's controller is told, so the app shows it.
     * Not round and round when nothing in the queue plays (repeat all over unreadable files): it stops when the next
     * track already failed since something last played, and says so when that is every track.
     */
    private fun skipFailed(player: ExoPlayer, kind: PlaybackProblem.Kind, format: String? = null) {
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
        val first = failed.add(player.currentMediaItemIndex)
        val next = player.nextMediaItemIndex
        if (next != C.INDEX_UNSET && next !in failed) {
            report(PlaybackProblem(kind, title, format))
            // playWhenReady stays as it was: a queue restored paused (a failed file at start) stays paused
            player.seekToNextMediaItem()
            player.prepare()
            return
        }
        // Without a decoder the player would go on through silence
        if (kind == PlaybackProblem.Kind.NO_DECODER) player.pause()
        // Said again when it is tried again (Play), not when the output reopens under a track already stopped at
        if (!first && !player.playWhenReady) return
        val everything = player.mediaItemCount > 1 && failed.size >= player.mediaItemCount
        report(if (everything) PlaybackProblem(PlaybackProblem.Kind.NOTHING_PLAYS, null) else PlaybackProblem(kind, title, format))
    }

    /**
     * A file whose audio no decoder on this phone takes (or that has no audio at all): Media3 plays it as silence,
     * with no error. It is skipped as a failed one (owner's decision 27.11.2026: nothing plays silently, nothing is
     * skipped silently). Tracks.EMPTY (no groups) is a track not prepared yet.
     */
    private fun skipIfSilent(player: ExoPlayer, tracks: Tracks) {
        if (tracks.isEmpty || tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) return
        val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
        skipFailed(player, PlaybackProblem.Kind.NO_DECODER, PlaybackProblem.formatName(format?.sampleMimeType))
    }

    /** To Music's own controllers only: a notification or a car's controller has no use for it. */
    private fun report(problem: PlaybackProblem) {
        val current = session ?: return
        val args = problem.toBundle()
        current.connectedControllers
            .filter { it.packageName == packageName && !current.isMediaNotificationController(it) }
            .forEach { current.sendCustomCommand(it, PlaybackProblem.COMMAND, args) }
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

    /** Stops and prepares again where it was: the renderer configures its output anew. Nothing to do while idle. */
    private fun reopenOutput(player: Player) {
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) return
        player.stop()
        player.prepare()
    }

    override fun onDestroy() {
        (application as MusicApplication).bitPerfect.release()
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

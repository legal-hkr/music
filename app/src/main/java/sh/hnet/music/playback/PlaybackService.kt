package sh.hnet.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
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

    private val shuffleCommand = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val gainSink = ReplayGainRenderersFactory.sink(this)
        val player = ExoPlayer.Builder(this, ReplayGainRenderersFactory(this, gainSink))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Tapping the notification opens the app on the now-playing screen
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(Callback())
            .setMediaButtonPreferences(buttons(player))
            .build()
        // Shuffle and repeat buttons in the notification follow the player's modes
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()
            override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()
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
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        scope.launch {
            (application as MusicApplication).preferences.gain.collect { gainSink.settings = it }
        }
        scope.launch { restore(player) }
        // While playing, the position is saved now and then, so a killed process loses little
        scope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                if (player.isPlaying) scheduleSave()
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
            player.setMediaItems(kept.map { known.getValue(it.value).toMediaItem() }, start, if (currentKept >= 0) saved.positionMs else 0)
            if (saved.shuffle && kept.size == saved.uris.size && saved.shuffleOrder.size == kept.size) {
                player.shuffleOrder = DefaultShuffleOrder(saved.shuffleOrder.toIntArray(), System.nanoTime())
            }
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            player.prepare()
        } finally {
            restored = true
        }
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
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(shuffleCommand)
                        .add(repeatCommand)
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
                ACTION_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
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
        const val SAVE_EVERY_MS = 10_000L
        const val ACTION_SHUFFLE = "sh.hnet.music.SHUFFLE"
        const val ACTION_REPEAT = "sh.hnet.music.REPEAT"
    }
}

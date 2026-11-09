package sh.hnet.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Counts a listen when a track has played for at least half its length in one play (owner's
 * decision): time actually playing, so seeking to the end doesn't count; a pause doesn't break it.
 * A play starts when the track starts – a new item, a repeat of it, Next on repeat one (a seek
 * back to its start). Each play counts at most once. [onCounted] gets the track's document URI.
 *
 * Listens to the player (main thread); [check] is called now and then while playing.
 */
class PlayCounter(private val player: Player, private val onCounted: (String) -> Unit) : Player.Listener {
    private var mediaId: String? = null
    /** Time played in this play before the current stretch. */
    private var playedMs = 0L
    /** When the current stretch of playing began (elapsed realtime), or null while not playing. */
    private var since: Long? = null
    private var counted = false

    private fun startPlay(id: String?) {
        mediaId = id
        playedMs = 0
        counted = false
        since = if (player.isPlaying) SystemClock.elapsedRealtime() else null
    }

    private fun closeStretch() {
        since?.let { playedMs += SystemClock.elapsedRealtime() - it }
        since = null
    }

    /** Counts the play once it has reached half the track. */
    fun check() {
        if (counted) return
        val id = mediaId ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val played = playedMs + (since?.let { SystemClock.elapsedRealtime() - it } ?: 0)
        if (played * 2 >= duration) {
            counted = true
            onCounted(id)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        check()
        startPlay(mediaItem?.mediaId)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            if (mediaId == null) startPlay(player.currentMediaItem?.mediaId)
            if (since == null) since = SystemClock.elapsedRealtime()
        } else {
            closeStretch()
            check()
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Back to the very start of the same track (Next or Previous on repeat one, or a seek there): a new play
        if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex && newPosition.positionMs < START_MS) {
            check()
            startPlay(player.currentMediaItem?.mediaId)
        }
    }

    private companion object {
        /** A seek to before this is a seek to the start. */
        const val START_MS = 1_000L
    }
}

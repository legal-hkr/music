package sh.hnet.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The session's player: the ExoPlayer, except that with repeat one the queue is just the current
 * track (owner's decision), so Next and Previous – from the notification, a headset, the lock
 * screen – start it again instead of moving on. Media3's own repeat one only repeats at the end.
 */
@OptIn(UnstableApi::class)
class RepeatOnePlayer(player: Player) : ForwardingPlayer(player) {
    private val repeatOne: Boolean get() = repeatMode == Player.REPEAT_MODE_ONE && mediaItemCount > 0

    override fun seekToNext() = if (repeatOne) seekTo(0) else super.seekToNext()

    override fun seekToNextMediaItem() = if (repeatOne) seekTo(0) else super.seekToNextMediaItem()

    override fun seekToPrevious() = if (repeatOne) seekTo(0) else super.seekToPrevious()

    override fun seekToPreviousMediaItem() = if (repeatOne) seekTo(0) else super.seekToPreviousMediaItem()

    override fun hasNextMediaItem(): Boolean = repeatOne || super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = repeatOne || super.hasPreviousMediaItem()
}

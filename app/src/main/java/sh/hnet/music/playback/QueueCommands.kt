package sh.hnet.music.playback

import androidx.media3.common.MediaItem

/**
 * Session commands for what the queue does in the service itself, where the player's own items and
 * shuffle order are at hand (the controller's copies carry no file URI and no shuffle order).
 */
object QueueCommands {
    /** Clear queue: everything but the current track goes, kept for [ACTION_UNDO_CLEAR]. */
    const val ACTION_CLEAR = "sh.hnet.music.CLEAR_QUEUE"

    /** Undo of the last Clear queue: the same tracks in the same places, in the same shuffled order. */
    const val ACTION_UNDO_CLEAR = "sh.hnet.music.UNDO_CLEAR_QUEUE"
}

/** What Clear queue took away, for its Undo: the player's own items and the play order as it was. */
internal class ClearedQueue(
    val before: List<MediaItem>,
    val after: List<MediaItem>,
    /** The current track's id: the order fits again only around the same track. */
    val currentId: String,
    /** The shuffle order over the whole queue as it was (list indices), or null when it wasn't ours. */
    val order: IntArray?,
)

package sh.hnet.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * The player's shuffle order, made for a queue that is shown from the current track on (the queue
 * sheet hides what came before it as history). Media3's default order is random as a whole: the
 * current track lands anywhere in it, everything before it counts as played and is never heard,
 * and inserted tracks land anywhere too. Here:
 * - [fromCurrent] puts the current track first and the others after it in random order (shuffle
 *   switched on, or a new queue played shuffled);
 * - tracks inserted inside the list (Play next, Undo of a removal) follow the track before them
 *   in the list, so Play next plays next; tracks appended (Add to queue) go to the end;
 * - switching shuffle off plays the list's own order again, which shuffling never changed: the
 *   current track is back at its own place and what followed it there comes next.
 * Pure index arithmetic, unit-tested.
 */
@OptIn(UnstableApi::class)
class QueueShuffleOrder(
    private val order: IntArray,
    /**
     * Not shuffled yet: the list order of a queue just put in place (cleared, then filled). The
     * service shuffles such a queue as soon as it is the player's, when shuffle is on – a new queue
     * played shuffled, whatever order the app's commands arrive in.
     */
    val fresh: Boolean = false,
) : ShuffleOrder {
    /** Where each list index stands in the shuffled order. */
    private val positions = IntArray(order.size).also { for (i in order.indices) it[order[i]] = i }

    override fun getLength(): Int = order.size

    override fun getNextIndex(index: Int): Int {
        val position = positions[index] + 1
        return if (position < order.size) order[position] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val position = positions[index] - 1
        return if (position >= 0) order[position] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order.last()

    override fun getFirstIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order.first()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val shifted = order.map { if (it >= insertionIndex) it + insertionCount else it }
        val inserted = (insertionIndex until insertionIndex + insertionCount).toList()
        val at = when {
            // Appended: at the end of what plays
            insertionIndex >= order.size -> shifted.size
            // Inside the list: right after the track before them (Play next: after the current one)
            insertionIndex > 0 -> positions[insertionIndex - 1] + 1
            // At the very start of the list: before the track that was first there
            else -> positions[0]
        }
        // Filling a cleared queue keeps it fresh; anything added to a queue in use doesn't
        return QueueShuffleOrder((shifted.subList(0, at) + inserted + shifted.subList(at, shifted.size)).toIntArray(), fresh && order.isEmpty())
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = indexToExclusive - indexFrom
        return QueueShuffleOrder(
            order.filter { it < indexFrom || it >= indexToExclusive }
                .map { if (it >= indexToExclusive) it - removed else it }
                .toIntArray(),
            fresh,
        )
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), fresh = true)

    /** The order as list indices, first to last (saved with the queue). */
    fun toList(): List<Int> = order.toList()

    companion object {
        /** Session command: shuffle from the current track (sent after switching shuffle on, or playing a new queue shuffled). */
        const val ACTION_RESHUFFLE = "sh.hnet.music.RESHUFFLE"

        /** [count] tracks: [current] first, the others after it in random order. */
        fun fromCurrent(count: Int, current: Int, random: Random = Random.Default): QueueShuffleOrder {
            if (count == 0) return QueueShuffleOrder(IntArray(0))
            val first = current.coerceIn(0, count - 1)
            val others = (0 until count).filter { it != first }.shuffled(random)
            return QueueShuffleOrder((listOf(first) + others).toIntArray())
        }
    }
}

package sh.hnet.music.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueShuffleOrderTest {
    /** The order as the player walks it. */
    private fun ShuffleOrder.walk(): List<Int> {
        val result = ArrayList<Int>()
        var index = firstIndex
        while (index != C.INDEX_UNSET) {
            result += index
            index = getNextIndex(index)
        }
        return result
    }

    @Test
    fun `the current track comes first and every other one follows it once`() {
        repeat(20) { seed ->
            val order = QueueShuffleOrder.fromCurrent(6, current = 3, random = Random(seed)).walk()
            assertEquals(3, order.first())
            assertEquals((0 until 6).toSet(), order.toSet())
            assertEquals(6, order.size)
        }
    }

    @Test
    fun `previous walks the order back`() {
        val order = QueueShuffleOrder(intArrayOf(2, 0, 3, 1))
        assertEquals(3, order.getPreviousIndex(1))
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(2))
        assertEquals(1, order.lastIndex)
    }

    @Test
    fun `play next inserted after the current track plays right after it`() {
        // List 0..4, current 2, shuffled 2, 4, 0, 3, 1; two tracks inserted at list index 3
        val order = QueueShuffleOrder(intArrayOf(2, 4, 0, 3, 1)).cloneAndInsert(3, 2)
        // Old 3 and 4 shift to 5 and 6; the new 3 and 4 follow 2
        assertEquals(listOf(2, 3, 4, 6, 0, 5, 1), order.walk())
    }

    @Test
    fun `added to the queue goes to the end`() {
        val order = QueueShuffleOrder(intArrayOf(2, 4, 0, 3, 1)).cloneAndInsert(5, 2)
        assertEquals(listOf(2, 4, 0, 3, 1, 5, 6), order.walk())
    }

    @Test
    fun `inserted at the start of the list goes before the track that was first there`() {
        val order = QueueShuffleOrder(intArrayOf(2, 0, 1)).cloneAndInsert(0, 1)
        assertEquals(listOf(3, 0, 1, 2), order.walk())
    }

    @Test
    fun `removing keeps the order of the rest`() {
        val order = QueueShuffleOrder(intArrayOf(2, 4, 0, 3, 1)).cloneAndRemove(1, 3)
        // 1 and 2 go; 3 and 4 become 1 and 2
        assertEquals(listOf(2, 0, 1), order.walk())
    }

    @Test
    fun `a new queue starts in list order until shuffled from its current track`() {
        val order = QueueShuffleOrder(IntArray(0)).cloneAndClear().cloneAndInsert(0, 4)
        assertEquals(listOf(0, 1, 2, 3), order.walk())
    }

    @Test
    fun `a queue just put in place is fresh until shuffled or added to`() {
        val filled = QueueShuffleOrder(intArrayOf(1, 0)).cloneAndClear().cloneAndInsert(0, 5) as QueueShuffleOrder
        assertEquals(true, filled.fresh)
        assertEquals(false, (filled.cloneAndInsert(5, 1) as QueueShuffleOrder).fresh)
        assertEquals(false, QueueShuffleOrder.fromCurrent(5, 2).fresh)
        assertEquals(false, (QueueShuffleOrder(intArrayOf(0, 1)).cloneAndInsert(2, 1) as QueueShuffleOrder).fresh)
    }
}

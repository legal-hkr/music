package sh.hnet.music.playback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueStoreTest {
    private fun store() = QueueStore(File(Files.createTempDirectory("queue").toFile(), "queue"))

    @Test
    fun `a queue with playlist entries reads back as it was written`() {
        val store = store()
        val queue = SavedQueue(
            uris = listOf("content://a", "content://b", "content://c"),
            current = 1,
            positionMs = 4200,
            shuffle = true,
            repeatMode = 2,
            shuffleOrder = listOf(1, 0, 2),
            entryIds = listOf(17L, null, 19L),
        )
        store.save(queue)
        assertEquals(queue, store.load())
    }

    @Test
    fun `a queue written before entries were kept reads with none`() {
        val file = File(Files.createTempDirectory("queue").toFile(), "queue")
        file.writeText("1\t0\t0\t0\t0\n\ncontent://a\ncontent://b\n")
        val loaded = QueueStore(file).load()!!
        assertEquals(listOf("content://a", "content://b"), loaded.uris)
        assertEquals(listOf<Long?>(null, null), loaded.entryIds)
    }
}

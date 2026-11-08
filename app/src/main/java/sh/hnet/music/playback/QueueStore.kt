package sh.hnet.music.playback

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The queue as it was, to come back after the process is gone: document URIs only (no titles or
 * paths), in the app's own no-backup directory.
 */
data class SavedQueue(
    val uris: List<String>,
    val current: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    /** The shuffle's play order as player indices; empty when not shuffled. */
    val shuffleOrder: List<Int>,
)

class QueueStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "queue")

    /** Written whole to a temporary file and renamed, so a crash never leaves half a queue. */
    fun save(queue: SavedQueue?) {
        if (queue == null || queue.uris.isEmpty()) {
            file.delete()
            return
        }
        val text = buildString {
            append(VERSION).append('\t').append(queue.current).append('\t').append(queue.positionMs).append('\t')
                .append(if (queue.shuffle) 1 else 0).append('\t').append(queue.repeatMode).append('\n')
            append(queue.shuffleOrder.joinToString(",")).append('\n')
            queue.uris.forEach { append(it).append('\n') }
        }
        try {
            val partial = File(file.path + ".part")
            partial.writeText(text)
            partial.renameTo(file)
        } catch (_: IOException) {
            // The previous copy stays; the next change writes again
        }
    }

    fun load(): SavedQueue? = try {
        val lines = file.takeIf { it.exists() }?.readLines() ?: return null
        val header = lines.getOrNull(0)?.split('\t') ?: return null
        if (header.size < 5 || header[0] != VERSION) return null
        SavedQueue(
            uris = lines.drop(2).filter { it.isNotEmpty() },
            current = header[1].toInt(),
            positionMs = header[2].toLong(),
            shuffle = header[3] == "1",
            repeatMode = header[4].toInt(),
            shuffleOrder = lines.getOrNull(1).orEmpty().split(',').mapNotNull { it.toIntOrNull() },
        )
    } catch (_: IOException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    private companion object {
        const val VERSION = "1"
    }
}

package sh.hnet.music.playback

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The queue as it was, to come back after the process is gone: document URIs only (no titles or
 * paths) and, for tracks played from a playlist, the entry they came from (so its page marks the
 * playing entry again), in the app's own no-backup directory.
 */
data class SavedQueue(
    val uris: List<String>,
    val current: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    /** The shuffle's play order as player indices; empty when not shuffled. */
    val shuffleOrder: List<Int>,
    /** Each item's playlist entry id, null for one not played from a playlist; as long as [uris]. */
    val entryIds: List<Long?> = uris.map { null },
)

class QueueStore(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "queue"))

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
            // One line per item: its URI, and after a tab its playlist entry when it has one
            queue.uris.forEachIndexed { i, uri ->
                append(uri)
                queue.entryIds.getOrNull(i)?.let { append('\t').append(it) }
                append('\n')
            }
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
        // Lines without a tab (written before entries were kept) are plain URIs
        val items = lines.drop(2).filter { it.isNotEmpty() }.map { it.split('\t', limit = 2) }
        SavedQueue(
            uris = items.map { it[0] },
            current = header[1].toInt(),
            positionMs = header[2].toLong(),
            shuffle = header[3] == "1",
            repeatMode = header[4].toInt(),
            shuffleOrder = lines.getOrNull(1).orEmpty().split(',').mapNotNull { it.toIntOrNull() },
            entryIds = items.map { it.getOrNull(1)?.toLongOrNull() },
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

package sh.hnet.music.library

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.tags.CoverExtractor
import sh.hnet.music.tags.FileChannelSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover thumbnails: decoded from the embedded picture once, kept in memory and in the app's own
 * cache directory (never backed up), one folder per picked folder so removing it removes its covers.
 */
class CoverCache(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val root = File(context.cacheDir, "covers")

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoding = Dispatchers.IO.limitedParallelism(3)

    /** The smallest stored size that covers [px]; few sizes keep the cache small. */
    fun sizeFor(px: Int): Int = SIZES.firstOrNull { it >= px } ?: SIZES.last()

    private fun key(track: LibraryTrack, size: Int) =
        sha1("${track.documentUri}\u0000${track.lastModified}\u0000${track.size}") + "_$size"

    private fun folderOf(folderUri: String) = File(root, sha1(folderUri))

    fun cached(track: LibraryTrack, size: Int): Bitmap? = memory.get(key(track, size))

    /** Sizes the player's surfaces (mini player, now playing) show the current cover at. */
    private val playerSizes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun notePlayerSize(size: Int) {
        playerSizes.add(size)
    }

    /**
     * Loads [track]'s cover at the player's sizes ahead of time, so skipping to it shows the cover
     * at once instead of the placeholder while it is decoded.
     */
    suspend fun prefetch(track: LibraryTrack) {
        if (!track.hasEmbeddedCover) return
        playerSizes.forEach { size -> if (cached(track, size) == null) load(track, size) }
    }

    suspend fun load(track: LibraryTrack, size: Int): Bitmap? = withContext(decoding) {
        val key = key(track, size)
        memory.get(key)?.let { return@withContext it }
        val file = File(folderOf(track.folderUri), "$key.webp")
        val bitmap = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null) ?: decodeEmbedded(track, size)?.also { store(file, it) }
        bitmap?.also { memory.put(key, it) }
    }

    private fun decodeEmbedded(track: LibraryTrack, size: Int): Bitmap? {
        val bytes = try {
            val descriptor = resolver.openFileDescriptor(track.documentUri.toUri(), "r") ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { CoverExtractor.extract(FileChannelSource(it.channel)) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Square, cropped to the center, as every place showing a cover crops it
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return if (side > size) square.scale(size, size) else square
    }

    private fun store(file: File, bitmap: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            partial.renameTo(file)
        } catch (_: IOException) {
            // A missing thumbnail is decoded again next time
        }
    }

    /** Removes the covers of a folder that left the library. */
    fun clearFolder(folderUri: String) {
        folderOf(folderUri).deleteRecursively()
        memory.evictAll()
    }

    private companion object {
        val SIZES = intArrayOf(144, 384, 768)

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

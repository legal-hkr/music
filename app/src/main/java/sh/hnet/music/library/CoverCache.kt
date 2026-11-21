package sh.hnet.music.library

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import sh.hnet.music.data.FolderCover
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.tags.CoverExtractor
import sh.hnet.music.tags.FileChannelSource

/**
 * Cover thumbnails: decoded once from the file's own picture, or from the cover image in its folder
 * when it has none, kept in memory and in the app's own cache directory (never backed up), one folder
 * per picked folder so removing it removes its covers. A folder image's thumbnail is keyed by the
 * image, so the tracks of an album share it.
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

    private fun key(cover: FolderCover, size: Int) =
        sha1("${cover.uri}\u0000${cover.lastModified}\u0000${cover.size}") + "_$size"

    private fun folderOf(folderUri: String) = File(root, sha1(folderUri))

    /** Embedded pictures that couldn't be decoded: those tracks show their folder image instead. */
    private val undecodable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The thumbnail in memory, or null. A track with its own picture never takes the folder image here,
     * as the album's other tracks may have put it in memory first – only once its own failed to decode.
     */
    fun cached(track: LibraryTrack, size: Int): Bitmap? {
        if (track.hasEmbeddedCover) {
            val key = key(track, size)
            memory.get(key)?.let { return it }
            if (key !in undecodable) return null
        }
        return track.folderCover?.let { memory.get(key(it, size)) }
    }

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
        if (!track.hasCover) return
        playerSizes.forEach { size -> if (cached(track, size) == null) load(track, size) }
    }

    /** The file's own picture, else (or when that one can't be decoded) its folder's image. */
    suspend fun load(track: LibraryTrack, size: Int): Bitmap? = withContext(decoding) {
        ownPicture(track, size) ?: track.folderCover?.let { cover -> loadAs(key(cover, size), track.folderUri) { decode(readImage(cover), size) } }
    }

    private fun ownPicture(track: LibraryTrack, size: Int): Bitmap? {
        if (!track.hasEmbeddedCover) return null
        val key = key(track, size)
        val bitmap = loadAs(key, track.folderUri) { decode(readEmbedded(track), size) }
        if (bitmap == null) undecodable += key
        return bitmap
    }

    private fun loadAs(key: String, folderUri: String, decode: () -> Bitmap?): Bitmap? {
        memory.get(key)?.let { return it }
        val file = File(folderOf(folderUri), "$key.webp")
        val bitmap = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null) ?: decode()?.also { store(file, it) }
        return bitmap?.also { memory.put(key, it) }
    }

    private fun readEmbedded(track: LibraryTrack): ByteArray? = read(track.documentUri) { CoverExtractor.extract(FileChannelSource(it.channel)) }

    /** A folder image whole; one larger than [MAX_IMAGE_BYTES] is left out rather than read into memory. */
    private fun readImage(cover: FolderCover): ByteArray? = read(cover.uri) { stream ->
        val length = stream.channel.size()
        if (length <= 0 || length > MAX_IMAGE_BYTES) null else stream.readBytes()
    }

    private fun read(uri: String, block: (FileInputStream) -> ByteArray?): ByteArray? = try {
        resolver.openFileDescriptor(uri.toUri(), "r")?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(block)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decode(bytes: ByteArray?, size: Int): Bitmap? {
        if (bytes == null) return null
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
        var partial: File? = null
        try {
            file.parentFile?.mkdirs()
            // Its own temporary file: the tracks of an album share a folder image's thumbnail, so two
            // loads of one key may write at once, and one name would mix their bytes
            partial = File.createTempFile(file.name, ".part", file.parentFile)
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            if (partial.renameTo(file)) partial = null
        } catch (_: IOException) {
            // A missing thumbnail is decoded again next time
        } finally {
            partial?.delete()
        }
    }

    /** Removes the covers of a folder that left the library. */
    fun clearFolder(folderUri: String) {
        folderOf(folderUri).deleteRecursively()
        memory.evictAll()
    }

    private companion object {
        val SIZES = intArrayOf(144, 384, 768)

        /** Larger folder images (print scans) are left out; the embedded-picture parser has its own bound. */
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

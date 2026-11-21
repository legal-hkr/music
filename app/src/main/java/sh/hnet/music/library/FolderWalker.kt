package sh.hnet.music.library

import android.content.ContentResolver
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import sh.hnet.music.data.ScannedFile

/** What a folder holds: the audio candidates, and the images that may be covers (FolderCovers). */
internal data class Listing(val files: List<ScannedFile>, val images: List<ScannedFile>)

/**
 * Lists the audio candidates below a picked folder through the Storage Access Framework.
 * Each directory is one provider query, which is the slow part of a scan on large libraries.
 */
internal object FolderWalker {
    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    /** Extensions taken as audio even when the provider reports a generic MIME type. */
    private val AUDIO_EXTENSIONS = setOf(
        "flac", "mp3", "m4a", "m4b", "m4p", "mp4", "aac", "alac", "ogg", "oga", "opus",
        "wav", "aif", "aiff", "aifc", "wv", "ape", "dsf", "dff", "mka", "mp2", "mpc", "tta",
    )

    /** A directory the provider wouldn't list; the folder's result is incomplete. */
    class IncompleteListing : Exception()

    /**
     * All candidates, or throws [IncompleteListing] when any directory can't be listed, so a
     * missing SD card or a revoked grant never looks like a folder whose files were deleted.
     */
    /**
     * @param skipDirectories Document IDs (same provider) of other picked folders inside this one: their
     *   files belong to them, so nothing is listed twice.
     */
    fun list(resolver: ContentResolver, folderUri: String, skipDirectories: Set<String>): Listing {
        val tree = folderUri.toUri()
        val files = mutableListOf<ScannedFile>()
        val images = mutableListOf<ScannedFile>()
        val pending = ArrayDeque<Pair<String, String>>() // document ID, relative path
        pending += DocumentsContract.getTreeDocumentId(tree) to ""
        while (pending.isNotEmpty()) {
            val (directoryId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
            val cursor = try {
                resolver.query(children, PROJECTION, null, null, null)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null // the provider no longer knows the document
            } ?: throw IncompleteListing()
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (name.startsWith('.')) continue
                    val relative = if (path.isEmpty()) name else "$path/$name"
                    val mime = it.getString(2).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (id !in skipDirectories) pending += id to relative
                    } else {
                        val audio = isAudio(name, mime)
                        if (!audio && !FolderCovers.isCandidate(name)) continue
                        val file = ScannedFile(
                            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            folderUri = folderUri,
                            relativePath = relative,
                            size = if (it.isNull(3)) -1 else it.getLong(3),
                            lastModified = if (it.isNull(4)) -1 else it.getLong(4),
                        )
                        if (audio) files += file else images += file
                    }
                }
            }
        }
        return Listing(files, images)
    }

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

}

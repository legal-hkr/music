package sh.hnet.music.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.net.toUri

/** Grants for folders picked with ACTION_OPEN_DOCUMENT_TREE: read only, kept across restarts. */
object LibraryFolders {
    private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Takes the grant and adds the folder; [addedAt] keeps a replaced folder's place in the list. */
    suspend fun add(resolver: ContentResolver, dao: LibraryFolderDao, treeUri: Uri, addedAt: Long = System.currentTimeMillis()) {
        resolver.takePersistableUriPermission(treeUri, READ)
        dao.upsert(LibraryFolder(treeUri.toString(), displayName(resolver, treeUri), addedAt))
    }

    /** Takes the grant again for a folder that lost it (picked again in the system picker). */
    fun regrant(resolver: ContentResolver, treeUri: Uri) = resolver.takePersistableUriPermission(treeUri, READ)

    suspend fun remove(resolver: ContentResolver, dao: LibraryFolderDao, tracks: TrackDao, treeUri: String) {
        val uri = treeUri.toUri()
        if (resolver.persistedUriPermissions.any { it.uri == uri }) {
            resolver.releasePersistableUriPermission(uri, READ)
        }
        tracks.removeFolder(treeUri)
        dao.delete(treeUri)
    }

    /** False when the grant is gone (revoked in settings, app data restored) or the volume is missing. */
    fun isGranted(resolver: ContentResolver, treeUri: String): Boolean {
        val uri = treeUri.toUri()
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    /** The folder's own document, where the system picker opens to pick it again. */
    fun documentUri(treeUri: String): Uri {
        val tree = treeUri.toUri()
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    /** Provider and document ID of the tree's root, to tell when one folder lies inside another. */
    fun rootOf(treeUri: String): Pair<String, String> {
        val tree = treeUri.toUri()
        return tree.authority.orEmpty() to DocumentsContract.getTreeDocumentId(tree)
    }

    /**
     * Where the folder is, in the picker's terms: the volume's own name, then the path
     * ("Internal shared storage › Music › Jazz"). Null for providers other than local storage.
     */
    fun location(context: Context, treeUri: String): String? {
        val (authority, documentId) = rootOf(treeUri)
        if (authority != EXTERNAL_STORAGE) return null
        val volumeId = documentId.substringBefore(':')
        val path = documentId.substringAfter(':', "").split('/').filter { it.isNotEmpty() }
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
        val volume = volumes.firstOrNull {
            if (volumeId == "primary") it.isPrimary else it.uuid.equals(volumeId, ignoreCase = true)
        }
        val volumeName = volume?.getDescription(context) ?: volumeId
        return (listOf(volumeName) + path).joinToString(" › ")
    }

    private fun displayName(resolver: ContentResolver, treeUri: Uri): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return treeUri.lastPathSegment.orEmpty()
    }
}

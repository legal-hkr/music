package sh.hnet.music.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

/** Grants for folders picked with ACTION_OPEN_DOCUMENT_TREE: read only, kept across restarts. */
object LibraryFolders {
    private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION

    suspend fun add(resolver: ContentResolver, dao: LibraryFolderDao, treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, READ)
        dao.upsert(LibraryFolder(treeUri.toString(), displayName(resolver, treeUri), System.currentTimeMillis()))
    }

    suspend fun remove(resolver: ContentResolver, dao: LibraryFolderDao, treeUri: String) {
        val uri = treeUri.toUri()
        if (resolver.persistedUriPermissions.any { it.uri == uri }) {
            resolver.releasePersistableUriPermission(uri, READ)
        }
        dao.delete(treeUri)
    }

    private fun displayName(resolver: ContentResolver, treeUri: Uri): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return treeUri.lastPathSegment.orEmpty()
    }
}

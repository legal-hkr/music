package sh.hnet.music.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryFolder
import sh.hnet.music.data.LibraryFolders
import sh.hnet.music.data.SkippedFile
import sh.hnet.music.library.FolderScan

/** A picked folder as Settings › Library shows it. */
data class FolderRow(
    val folder: LibraryFolder,
    val location: String?,
    val tracks: Int,
    val skipped: Int,
    /** Non-null while the folder waits in or goes through a scan. */
    val scan: FolderScan?,
    val unavailable: Boolean,
)

/** The library as a whole, for the Settings root and the navigation badge. */
data class LibrarySummary(
    val folders: Int = 0,
    val tracks: Int = 0,
    val scanning: Int = 0,
    val unavailable: Int = 0,
)

sealed interface LibraryMessage {
    data class AlreadyAdded(val name: String) : LibraryMessage
}

/** Folders, their scan state and the actions on them; one instance for the activity. */
class LibraryViewModel(private val app: MusicApplication) : ViewModel() {
    private val folderDao = app.database.libraryFolderDao()
    private val trackDao = app.database.trackDao()
    private val scanner = app.scanner
    private val resolver = app.contentResolver
    private val locations = HashMap<String, String?>()

    private val _messages = MutableSharedFlow<LibraryMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<LibraryMessage> = _messages.asSharedFlow()

    val folders: StateFlow<List<FolderRow>> = combine(
        folderDao.observeAll(),
        trackDao.observeTrackCounts(),
        trackDao.observeSkippedCounts(),
        scanner.progress,
        scanner.unavailable,
    ) { folders, trackCounts, skippedCounts, progress, unavailable ->
        val tracks = trackCounts.associate { it.folderUri to it.count }
        val skipped = skippedCounts.associate { it.folderUri to it.count }
        folders.map { folder ->
            val uri = folder.treeUri
            FolderRow(
                folder = folder,
                location = locations.getOrPut(uri) { LibraryFolders.location(app, uri) },
                tracks = tracks[uri] ?: 0,
                skipped = skipped[uri] ?: 0,
                scan = progress[uri],
                unavailable = uri in unavailable,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<LibrarySummary> = folders.map { rows ->
        LibrarySummary(
            folders = rows.size,
            tracks = rows.sumOf { it.tracks },
            scanning = rows.count { it.scan != null },
            unavailable = rows.count { it.unavailable },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySummary())

    fun skippedFiles(treeUri: String): Flow<List<SkippedFile>> = trackDao.observeSkipped(treeUri)

    fun refreshAccess() {
        viewModelScope.launch { scanner.refreshAccess() }
    }

    /**
     * A folder picked in the system picker. With [replacing], the user was asked to pick a folder
     * that lost access again: the same one gets its grant back, another one takes its place.
     */
    fun onFolderPicked(uri: Uri, replacing: String? = null) {
        viewModelScope.launch {
            val picked = uri.toString()
            withContext(Dispatchers.IO) {
                when {
                    picked == replacing -> LibraryFolders.regrant(resolver, uri)
                    folderDao.get(picked) != null -> {
                        _messages.tryEmit(LibraryMessage.AlreadyAdded(folderDao.get(picked)!!.displayName))
                        return@withContext
                    }
                    replacing != null -> {
                        val addedAt = folderDao.get(replacing)?.addedAt ?: System.currentTimeMillis()
                        LibraryFolders.remove(resolver, folderDao, trackDao, replacing)
                        LibraryFolders.add(resolver, folderDao, uri, addedAt)
                    }
                    else -> LibraryFolders.add(resolver, folderDao, uri)
                }
                scanner.refreshAccess()
                scanner.requestScan()
            }
        }
    }

    fun removeFolder(treeUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            LibraryFolders.remove(resolver, folderDao, trackDao, treeUri)
            locations -= treeUri
            scanner.refreshAccess()
            // Files of a folder inside it now belong to this one's parent again, if any
            scanner.requestScan()
        }
    }
}

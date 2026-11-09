package sh.hnet.music.ui.settings

import kotlinx.coroutines.flow.update
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryFolder
import sh.hnet.music.data.LibraryFolders
import sh.hnet.music.data.ShortFile
import sh.hnet.music.data.SkippedFile
import sh.hnet.music.library.FolderScan

/** A picked folder as Settings › Library shows it. */
data class FolderRow(
    val folder: LibraryFolder,
    val location: String?,
    val tracks: Int,
    /** Skipped files and, with Skip short files on, the tracks it leaves out. */
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

    /** Folders found without access since the last report: once per loss (SPEC §13). */
    data class LostAccess(val names: List<String>) : LibraryMessage
}

/** The running scan as a whole, for Scan now (SPEC-settings-v2 §5.1); null when idle. */
data class ScanStatus(val done: Int, val total: Int) {
    /** False while folders are still being listed and nothing is known to read yet. */
    val counted: Boolean get() = total > 0
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

    /** Skip short files, in seconds; 0 = off. */
    val skipShortSeconds: StateFlow<Int> = app.preferences.skipShortSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** When the last full scan ended; null before the first. */
    val lastScan: StateFlow<Long?> = scanner.lastScan

    /** Tracks and skipped files per folder, both counted with the short-file threshold. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val counts = skipShortSeconds.flatMapLatest { seconds ->
        val minMs = seconds * 1000L
        combine(trackDao.observeTrackCounts(minMs), trackDao.observeSkippedCounts(), trackDao.observeShortCounts(minMs)) { tracks, skipped, short ->
            val skippedByFolder = HashMap<String, Int>()
            (skipped + short).forEach { skippedByFolder.merge(it.folderUri, it.count, Int::plus) }
            tracks.associate { it.folderUri to it.count } to skippedByFolder
        }
    }

    val folders: StateFlow<List<FolderRow>> = combine(
        combine(folderDao.observeAll(), app.library.removing) { folders, removing -> folders.filter { it.treeUri !in removing } },
        counts,
        scanner.progress,
        scanner.unavailable,
    ) { folders, (tracks, skipped), progress, unavailable ->
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

    /** Folders already reported as unreachable; reported again only after access came back. */
    private var reportedLost = emptySet<String>()

    init {
        viewModelScope.launch {
            folders.collect { rows ->
                val lost = rows.filter { it.unavailable }
                val fresh = lost.filter { it.folder.treeUri !in reportedLost }
                reportedLost = lost.mapTo(HashSet()) { it.folder.treeUri }
                if (fresh.isNotEmpty()) _messages.emit(LibraryMessage.LostAccess(fresh.map { it.folder.displayName }))
            }
        }
    }

    /** The running scan summed over its folders (SPEC-settings-v2 §5.1). */
    val scanStatus: StateFlow<ScanStatus?> = scanner.progress.map { progress ->
        if (progress.isEmpty()) null
        else ScanStatus(done = progress.values.sumOf { it.done }, total = progress.values.sumOf { it.total })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun skippedFiles(treeUri: String): Flow<List<SkippedFile>> = trackDao.observeSkipped(treeUri)

    /** The folder's tracks left out for being short; empty while Skip short files is off. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shortFiles(treeUri: String): Flow<List<ShortFile>> =
        skipShortSeconds.flatMapLatest { seconds -> trackDao.observeShort(treeUri, seconds * 1000L) }

    /** Scan now: every folder, incrementally, as on app start. */
    fun scanNow() = scanner.requestScan()

    fun setSkipShortSeconds(seconds: Int) {
        viewModelScope.launch { app.preferences.setSkipShortSeconds(seconds) }
    }

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
                        app.covers.clearFolder(replacing)
                        LibraryFolders.add(resolver, folderDao, uri, addedAt)
                    }
                    else -> LibraryFolders.add(resolver, folderDao, uri)
                }
                scanner.refreshAccess()
                scanner.requestScan()
            }
        }
    }

    /** Remove folder, step one: hidden at once with its tracks; [finishRemovingFolder] or [undoRemovingFolder] follows. */
    fun startRemovingFolder(treeUri: String) = app.library.removing.update { it + treeUri }

    /** Undo: the folder is back as it was – nothing of it was touched. */
    fun undoRemovingFolder(treeUri: String) = app.library.removing.update { it - treeUri }

    /** The Undo snackbar went: the folder's grant, index and covers go for good. */
    fun finishRemovingFolder(treeUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            LibraryFolders.remove(resolver, folderDao, trackDao, treeUri)
            app.covers.clearFolder(treeUri)
            locations -= treeUri
            // Shown again only once there is nothing left of it to show
            app.library.removing.update { it - treeUri }
            scanner.refreshAccess()
            // Files of a folder inside it now belong to this one's parent again, if any
            scanner.requestScan()
        }
    }
}

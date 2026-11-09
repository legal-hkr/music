package sh.hnet.music.library

import android.content.ContentResolver
import android.content.SharedPreferences
import androidx.core.content.edit
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.hnet.music.data.FileState
import sh.hnet.music.data.LibraryFolder
import sh.hnet.music.data.LibraryFolderDao
import sh.hnet.music.data.LibraryFolders
import sh.hnet.music.data.ScannedFile
import sh.hnet.music.data.SkipReason
import sh.hnet.music.data.SkippedFile
import sh.hnet.music.data.Track
import sh.hnet.music.data.TrackDao
import sh.hnet.music.tags.AudioCodec
import sh.hnet.music.tags.FileChannelSource
import sh.hnet.music.tags.TagParser
import java.io.IOException

/** A folder in a running scan: [total] is known once its files have been listed. */
data class FolderScan(val listing: Boolean = true, val done: Int = 0, val total: Int = 0)

/**
 * Incremental scan of the picked folders, one after another: list a folder, read tags only of
 * files that are new or changed (size or modification time), drop files that are gone. Results
 * are written in batches, so a scan cut short keeps what it had read and the next one continues.
 */
class LibraryScanner(
    private val resolver: ContentResolver,
    private val folders: LibraryFolderDao,
    private val tracks: TrackDao,
    private val scope: CoroutineScope,
    /** Where the last scan's end is kept (not a setting: Restore defaults leaves it). */
    private val stamps: SharedPreferences,
) {
    /** Folders waiting in or going through the running scan, by tree URI. Empty when idle. */
    private val _progress = MutableStateFlow<Map<String, FolderScan>>(emptyMap())
    val progress: StateFlow<Map<String, FolderScan>> = _progress.asStateFlow()

    /** Folders the app can't read (no grant, volume missing); their tracks are kept. */
    private val _unavailable = MutableStateFlow<Set<String>>(emptySet())
    val unavailable: StateFlow<Set<String>> = _unavailable.asStateFlow()

    /** Folders whose last listing failed although the grant was there (e.g. an SD card taken out). */
    private var listingFailed = emptySet<String>()

    private val _lastScan = MutableStateFlow(stamps.getLong(LAST_SCAN, 0L).takeIf { it > 0 })

    /** When the last scan of all folders ended (Settings › Music folders › Scan now); null before the first. */
    val lastScan: StateFlow<Long?> = _lastScan.asStateFlow()

    private val mutex = Mutex()
    private var rescanRequested = false
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val parsing = Dispatchers.IO.limitedParallelism(PARALLEL_READS)

    /** Starts a scan of all folders; a request during a scan runs one more scan after it. */
    fun requestScan() {
        synchronized(this) {
            rescanRequested = true
            if (job?.isActive == true) return
            job = scope.launch {
                while (takeRequest()) mutex.withLock { scanAll() }
            }
        }
    }

    private fun takeRequest(): Boolean = synchronized(this) {
        rescanRequested.also { rescanRequested = false }
    }

    /** Re-checks the grants without listing anything, e.g. when the folders page is shown. */
    suspend fun refreshAccess() = withContext(Dispatchers.IO) {
        val all = folders.all().map { it.treeUri }
        listingFailed = listingFailed intersect all.toSet()
        _unavailable.value = all.filterTo(HashSet()) { !LibraryFolders.isGranted(resolver, it) } + listingFailed
    }

    private suspend fun scanAll() = withContext(Dispatchers.IO) {
        val all = folders.all()
        _progress.value = all.associate { it.treeUri to FolderScan() }
        val failed = mutableSetOf<String>()
        try {
            for (folder in all) {
                if (!scanFolder(folder, all)) failed += folder.treeUri
                _progress.update { it - folder.treeUri }
            }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_SCAN, now) }
            _lastScan.value = now
        } finally {
            _progress.value = emptyMap()
            listingFailed = failed.filterTo(HashSet()) { LibraryFolders.isGranted(resolver, it) }
            _unavailable.value = failed
        }
    }

    /** False when the folder couldn't be listed; its tracks stay as they were. */
    private suspend fun scanFolder(folder: LibraryFolder, all: List<LibraryFolder>): Boolean {
        val uri = folder.treeUri
        if (!LibraryFolders.isGranted(resolver, uri)) return false
        val (authority, _) = LibraryFolders.rootOf(uri)
        val nested = all.asSequence()
            .filter { it.treeUri != uri }
            .map { LibraryFolders.rootOf(it.treeUri) }
            .filter { it.first == authority }
            .mapTo(HashSet()) { it.second }
        val files = try {
            FolderWalker.list(resolver, uri, nested)
        } catch (_: FolderWalker.IncompleteListing) {
            return false
        }

        val known = (tracks.trackStates(uri) + tracks.skippedStates(uri)).associateBy { it.documentUri }
        val toRead = files.filter { known[it.documentUri]?.matches(it) != true }
        val present = files.mapTo(HashSet()) { it.documentUri }
        val gone = known.keys.filter { it !in present }
        if (gone.isNotEmpty()) tracks.removeFiles(gone)

        _progress.update { it + (uri to FolderScan(listing = false, total = toRead.size)) }
        toRead.chunked(BATCH).forEach { batch ->
            val results = coroutineScope { batch.map { file -> async(parsing) { read(file) } }.awaitAll() }
            tracks.applyResults(results.filterIsInstance<Track>(), results.filterIsInstance<SkippedFile>())
            _progress.update { map ->
                val current = map[uri] ?: return@update map
                map + (uri to current.copy(done = current.done + batch.size))
            }
        }
        return true
    }

    private fun FileState.matches(file: ScannedFile) = size == file.size && lastModified == file.lastModified

    /** A [Track], or a [SkippedFile] saying why not. */
    private fun read(file: ScannedFile): Any {
        val parsed = try {
            val descriptor = resolver.openFileDescriptor(file.documentUri.toUri(), "r")
                ?: return SkippedFile.from(file, SkipReason.UNREADABLE)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                TagParser.parse(FileChannelSource(stream.channel)) // null: not a format we read
            }
        } catch (_: IOException) {
            return SkippedFile.from(file, SkipReason.UNREADABLE)
        } catch (_: SecurityException) {
            return SkippedFile.from(file, SkipReason.UNREADABLE)
        } catch (_: IllegalArgumentException) {
            return SkippedFile.from(file, SkipReason.UNREADABLE)
        }
        return when (parsed?.properties?.codec) {
            null, AudioCodec.UNKNOWN -> SkippedFile.from(file, SkipReason.UNSUPPORTED)
            AudioCodec.PROTECTED -> SkippedFile.from(file, SkipReason.PROTECTED)
            else -> Track.from(file, parsed)
        }
    }

    private companion object {
        const val PARALLEL_READS = 4
        private const val LAST_SCAN = "last_scan"
        const val BATCH = 100
    }
}

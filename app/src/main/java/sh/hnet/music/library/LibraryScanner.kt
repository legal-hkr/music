package sh.hnet.music.library

import android.content.ContentResolver
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

/** Where a running scan is; [total] is known once the folders have been listed. */
data class ScanProgress(val listing: Boolean, val done: Int = 0, val total: Int = 0)

/**
 * Incremental scan of the picked folders: list them, read tags only of files that are new or
 * changed (size or modification time), drop files that are gone. Results are written in
 * batches, so a scan cut short keeps what it had read and the next one continues from there.
 */
class LibraryScanner(
    private val resolver: ContentResolver,
    private val folders: LibraryFolderDao,
    private val tracks: TrackDao,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    /** Folders whose last scan couldn't list them (no grant, volume missing); their tracks are kept. */
    private val _unavailable = MutableStateFlow<Set<String>>(emptySet())
    val unavailable: StateFlow<Set<String>> = _unavailable.asStateFlow()

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

    private suspend fun scanAll() = withContext(Dispatchers.IO) {
        _progress.value = ScanProgress(listing = true)
        try {
            val listed = mutableMapOf<String, List<ScannedFile>>()
            val unavailable = mutableSetOf<String>()
            for (folder in folders.all()) {
                val uri = folder.treeUri
                if (!LibraryFolders.isGranted(resolver, uri)) {
                    unavailable += uri
                    continue
                }
                try {
                    listed[uri] = FolderWalker.list(resolver, uri)
                } catch (_: FolderWalker.IncompleteListing) {
                    unavailable += uri
                }
            }
            _unavailable.value = unavailable

            val toRead = mutableListOf<ScannedFile>()
            for ((folderUri, files) in listed) {
                val known = (tracks.trackStates(folderUri) + tracks.skippedStates(folderUri)).associateBy { it.documentUri }
                files.filterTo(toRead) { known[it.documentUri]?.matches(it) != true }
                val present = files.mapTo(HashSet()) { it.documentUri }
                val gone = known.keys.filter { it !in present }
                if (gone.isNotEmpty()) tracks.removeFiles(gone)
            }

            _progress.value = ScanProgress(listing = false, done = 0, total = toRead.size)
            toRead.chunked(BATCH).forEach { batch ->
                val results = coroutineScope { batch.map { file -> async(parsing) { read(file) } }.awaitAll() }
                tracks.applyResults(
                    results.filterIsInstance<Track>(),
                    results.filterIsInstance<SkippedFile>(),
                )
                _progress.update { it?.copy(done = it.done + batch.size) }
            }
        } finally {
            _progress.value = null
        }
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
        const val BATCH = 100
    }
}

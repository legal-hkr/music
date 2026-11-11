package sh.hnet.music.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.PlayCount
import sh.hnet.music.data.TrackDao

/** The library in memory, grouped once for every view; null until the database has answered. */
class LibraryRepository(private val dao: TrackDao, scanner: LibraryScanner, skipShortSeconds: Flow<Int>, scope: CoroutineScope) {
    private var first = true

    /**
     * Folders being removed: hidden at once, removed for good when the Undo snackbar goes (owner's
     * decision, as ComfyChair). Until then the folder, its grant and its index are untouched.
     */
    val removing = MutableStateFlow<Set<String>>(emptySet())

    /** A scan writes in batches; between them the views regroup at most a few times a second. */
    @OptIn(FlowPreview::class)
    private val reachable: StateFlow<List<LibraryTrack>?> = combine(dao.observeLibrary(), scanner.unavailable, removing) { tracks, unavailable, removing ->
        // A folder that lost access keeps its index, but its tracks leave every view (SPEC §13);
        // so do a folder's being removed
        if (unavailable.isEmpty() && removing.isEmpty()) tracks
        else tracks.filter { it.folderUri !in unavailable && it.folderUri !in removing }
    }
        .debounce { if (first) { first = false; 0L } else 400L }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The library: reachable tracks without those Skip short files leaves out. Short files stay in
     * the index, so changing the threshold needs no rescan (SPEC-settings-v2 §5.2); an unknown
     * length is never short.
     */
    val tracks: StateFlow<List<LibraryTrack>?> = combine(reachable, skipShortSeconds) { tracks, skipShort ->
        val minMs = skipShort * 1000L
        if (tracks == null || minMs == 0L) tracks else tracks.filter { it.durationMs <= 0 || it.durationMs >= minMs }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Reachable tracks by URI, short ones included: what the player shows. The current queue isn't
     * touched by Skip short files (§5.2), so a track it leaves out still plays and shows.
     */
    val playableByUri: StateFlow<Map<String, LibraryTrack>> = reachable
        .map { list -> list.orEmpty().associateBy { it.documentUri } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val albums: StateFlow<List<Album>?> = tracks
        .map { it?.let(Albums::group) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    val artists: StateFlow<List<Artist>?> = combine(tracks, albums) { tracks, albums ->
        if (tracks == null || albums == null) null else Artists.build(tracks, albums)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    val artistsByKey: StateFlow<Map<String, Artist>> = artists
        .map { list -> list.orEmpty().associateBy { it.key } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val tracksByUri: StateFlow<Map<String, LibraryTrack>> = tracks
        .map { list -> list.orEmpty().associateBy { it.documentUri } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Play counts by document URI (tracks never listened to aren't there). */
    val playCounts: StateFlow<Map<String, Int>> = dao.observePlayCounts()
        .map { counts -> counts.associate { it.documentUri to it.count } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Every play count back to zero (Settings › Reset); returns what they were, for [restorePlayCounts]. */
    suspend fun resetPlayCounts(): List<PlayCount> = dao.resetPlayCounts()

    /** Undo of [resetPlayCounts]: the counts as they were (a listen counted in between is overwritten). */
    suspend fun restorePlayCounts(counts: List<PlayCount>) = dao.upsertPlayCounts(counts)

    val albumsByKey: StateFlow<Map<String, Album>> = albums
        .map { list -> list.orEmpty().associateBy { it.key } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
}

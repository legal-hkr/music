package sh.hnet.music.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.TrackDao

/** The library in memory, grouped once for every view; null until the database has answered. */
class LibraryRepository(dao: TrackDao, scanner: LibraryScanner, scope: CoroutineScope) {
    private var first = true

    /** A scan writes in batches; between them the views regroup at most a few times a second. */
    @OptIn(FlowPreview::class)
    val tracks: StateFlow<List<LibraryTrack>?> = combine(dao.observeLibrary(), scanner.unavailable) { tracks, unavailable ->
        // A folder that lost access keeps its index, but its tracks leave every view (SPEC §13)
        if (unavailable.isEmpty()) tracks else tracks.filter { it.folderUri !in unavailable }
    }
        .debounce { if (first) { first = false; 0L } else 400L }
        .stateIn(scope, SharingStarted.Eagerly, null)

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

    val albumsByKey: StateFlow<Map<String, Album>> = albums
        .map { list -> list.orEmpty().associateBy { it.key } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
}

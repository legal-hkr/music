package sh.hnet.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import sh.hnet.music.MusicApplication
import sh.hnet.music.library.AlbumSort
import sh.hnet.music.library.Albums
import sh.hnet.music.library.Artists
import sh.hnet.music.library.SearchIndex
import sh.hnet.music.library.SearchResults
import sh.hnet.music.library.TrackSort
import sh.hnet.music.library.Tracks
import java.util.Locale

/**
 * The Search destination's state. The query lives only here, in memory: nothing typed is stored
 * (SPEC §8), it just survives switching tabs.
 */
class SearchViewModel(app: MusicApplication) : ViewModel() {
    private val repository = app.library

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Bumped when the Search tab is selected; the screen focuses the field once per bump. */
    private val _focusRequest = MutableStateFlow(0)
    val focusRequest: StateFlow<Int> = _focusRequest.asStateFlow()
    private var focusHandled = 0

    /** Within each kind, items in the library's alphabetical order before ranking. */
    private val index: StateFlow<SearchIndex?> = combine(repository.albums, repository.artists, repository.tracks) { albums, artists, tracks ->
        if (albums == null || artists == null || tracks == null) return@combine null
        val locale = Locale.getDefault()
        SearchIndex(
            Albums.sort(albums, AlbumSort.TITLE, locale),
            Artists.sort(artists, locale).items,
            Tracks.sort(tracks, TrackSort.TITLE, locale).items,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(FlowPreview::class)
    val results: StateFlow<SearchResults> = combine(_query.debounce(150), index) { query, index ->
        index?.search(query) ?: SearchResults()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults())

    fun setQuery(text: String) {
        _query.value = text
    }

    fun clear() = setQuery("")

    fun requestFocus() {
        _focusRequest.value++
    }

    /** True once per request, so coming back from a result's page doesn't pop the keyboard up again. */
    fun takeFocusRequest(request: Int): Boolean {
        if (request <= focusHandled) return false
        focusHandled = request
        return true
    }
}

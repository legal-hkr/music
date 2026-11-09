@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.ui.components.EmptyState
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.components.TrackRow
import sh.hnet.music.ui.components.boldNamesResource
import sh.hnet.music.ui.components.formatCount
import sh.hnet.music.ui.library.AlbumRow
import sh.hnet.music.ui.library.ArtistRow
import sh.hnet.music.ui.library.BrowseViewModel
import sh.hnet.music.ui.library.trackLine

private const val SHOWN = 3

private enum class Group { ALBUMS, ARTISTS, TRACKS }

/** The Search destination (SPEC §8): one field over albums, artists and tracks. */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    browse: BrowseViewModel,
    navigation: TrackNavigation,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focusRequest by viewModel.focusRequest.collectAsStateWithLifecycle()
    val current by browse.playState.collectAsStateWithLifecycle(null to false)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Expanded groups are UI state only; a new query starts collapsed
    var expanded by rememberSaveable(query) { mutableStateOf(setOf<Group>()) }

    LaunchedEffect(focusRequest) {
        if (viewModel.takeFocusRequest(focusRequest)) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The input alone: a collapsed SearchBar takes no typing (it hands that to its expanded
        // form, which this screen doesn't use), so the field carries the bar's container itself
        SearchBarDefaults.InputField(
            textFieldState = viewModel.field,
            searchBarState = rememberSearchBarState(),
            onSearch = { keyboard?.hide() },
            placeholder = { Text(stringResource(R.string.hint_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear_search), viewModel::clear) }
            } else null,
            colors = SearchBarDefaults.inputFieldColors(
                focusedContainerColor = SearchBarDefaults.colors().containerColor,
                unfocusedContainerColor = SearchBarDefaults.colors().containerColor,
                disabledContainerColor = SearchBarDefaults.colors().containerColor,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                .focusRequester(focus),
        )

        when {
            query.isBlank() -> EmptyState(R.drawable.ic_search, stringResource(R.string.msg_search_empty), Modifier.imePadding())
            results.isEmpty -> EmptyState(R.drawable.ic_search_off, boldNamesResource(R.string.msg_search_no_results, query.trim()), Modifier.imePadding())
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                // The keyboard covers the bottom bar; the list pads by it so the last row can be reached
                modifier = Modifier.fillMaxSize().imePadding(),
            ) {
                group(Group.ALBUMS, results.albums.size, Group.ALBUMS in expanded, R.string.label_view_albums, R.plurals.button_show_all_albums, { expanded = expanded + it }) {
                    items((if (Group.ALBUMS in expanded) results.albums else results.albums.take(SHOWN)), key = { "a:" + it.key }) { album ->
                        AlbumRow(album) { onOpenAlbum(album.key) }
                    }
                }
                group(Group.ARTISTS, results.artists.size, Group.ARTISTS in expanded, R.string.label_view_artists, R.plurals.button_show_all_artists, { expanded = expanded + it }) {
                    items((if (Group.ARTISTS in expanded) results.artists else results.artists.take(SHOWN)), key = { "r:" + it.key }) { artist ->
                        ArtistRow(artist) { onOpenArtist(artist.name) }
                    }
                }
                group(Group.TRACKS, results.tracks.size, Group.TRACKS in expanded, R.string.label_view_tracks, R.plurals.button_show_all_tracks, { expanded = expanded + it }) {
                    items((if (Group.TRACKS in expanded) results.tracks else results.tracks.take(SHOWN)), key = { "t:" + it.documentUri }) { track ->
                        TrackRow(
                            track = track,
                            supporting = trackLine(track),
                            state = BrowseViewModel.stateOf(track, current),
                            navigation = navigation,
                            // Search results aren't a playlist: the tapped track alone (SPEC §5.6)
                            onClick = { keyboard?.hide(); browse.playFrom(listOf(track), 0) },
                        )
                    }
                }
            }
        }
    }
}

/** A group: header, its rows, then "Show all" while collapsed with more than three. */
private fun LazyListScope.group(
    group: Group,
    count: Int,
    isExpanded: Boolean,
    title: Int,
    showAll: Int,
    onExpand: (Group) -> Unit,
    rows: LazyListScope.() -> Unit,
) {
    if (count == 0) return
    item(key = "header:$group") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    rows()
    if (!isExpanded && count > SHOWN) {
        item(key = "more:$group") {
            TextButton(onClick = { onExpand(group) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(pluralStringResource(showAll, count, formatCount(count)))
            }
        }
    }
}


@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.library.Album
import sh.hnet.music.library.AlbumSort
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.EmptyState
import sh.hnet.music.ui.components.SortChip
import sh.hnet.music.ui.components.formatCount

enum class LibraryView(val label: Int) {
    ALBUMS(R.string.label_view_albums),
    ARTISTS(R.string.label_view_artists),
    TRACKS(R.string.label_view_tracks),
}

/** The Library destination: the view switch in the top bar and the chosen view (SPEC §3.2). */
@Composable
fun LibraryScreen(viewModel: BrowseViewModel, onOpenAlbum: (String) -> Unit) {
    val hasTracks by viewModel.hasTracks.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LibraryView.ALBUMS) }
    // Switching views is a step in history: Back returns to the previous view
    var history by rememberSaveable { mutableStateOf(listOf<LibraryView>()) }
    val albumsGrid = rememberLazyGridState()

    BackHandler(enabled = history.isNotEmpty()) {
        view = history.last()
        history = history.dropLast(1)
    }

    when (hasTracks) {
        null -> Unit
        false -> EmptyState(R.drawable.ic_library_music, stringResource(R.string.msg_library_filling), Modifier.statusBarsPadding())
        true -> Column(Modifier.fillMaxSize()) {
            val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            ViewSwitch(view) { chosen ->
                if (chosen != view) {
                    history = history + view
                    view = chosen
                }
            }
            AnimatedContent(
                targetState = view,
                transitionSpec = { fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec) },
                label = "libraryView",
            ) { shown ->
                when (shown) {
                    LibraryView.ALBUMS -> AlbumsView(viewModel, albumsGrid, onOpenAlbum)
                    // Artists and Tracks arrive in S3
                    LibraryView.ARTISTS, LibraryView.TRACKS ->
                        EmptyState(if (shown == LibraryView.ARTISTS) R.drawable.ic_person else R.drawable.ic_queue_music, stringResource(R.string.msg_view_coming))
                }
            }
        }
    }
}

@Composable
private fun ViewSwitch(selected: LibraryView, onSelect: (LibraryView) -> Unit) {
    val groupLabel = stringResource(R.string.content_description_library_view)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp)
            .selectableGroup()
            .semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val views = LibraryView.entries
        views.forEachIndexed { index, item ->
            ToggleButton(
                checked = item == selected,
                onCheckedChange = { if (it) onSelect(item) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    views.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // Selected: secondary container, fully round (SPEC §3.2); the shape morph is the toggle's own
                colors = ToggleButtonDefaults.colors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AlbumsView(viewModel: BrowseViewModel, state: LazyGridState, onOpenAlbum: (String) -> Unit) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val sort by viewModel.albumSort.collectAsStateWithLifecycle()
    val list = albums ?: return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.label_album_count, list.size, formatCount(list.size)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SortChip(
                    options = AlbumSort.entries,
                    selected = sort,
                    label = { stringResource(it.label()) },
                    onSelect = viewModel::setAlbumSort,
                )
            }
        }
        items(list, key = { it.key }) { album ->
            AlbumTile(album, Modifier.animateItem()) { onOpenAlbum(album.key) }
        }
    }
}

fun AlbumSort.label(): Int = when (this) {
    AlbumSort.ALBUM_ARTIST -> R.string.label_sort_album_artist
    AlbumSort.TITLE -> R.string.label_sort_title
    AlbumSort.YEAR -> R.string.label_sort_year
    AlbumSort.RECENTLY_ADDED -> R.string.label_sort_recently_added
}

@Composable
private fun AlbumTile(album: Album, modifier: Modifier, onClick: () -> Unit) {
    val artist = albumArtistText(album)
    val description = stringResource(R.string.content_description_album, album.title, artist)
    Column(
        modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Cover(album.coverTrack, RoundedCornerShape(12.dp), Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist.isNotEmpty()) {
            Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The album artists joined, or "Various artists" for a compilation without them (SPEC §4.1). */
@Composable
fun albumArtistText(album: Album): String =
    if (album.isVarious) stringResource(R.string.label_various_artists) else album.shownArtists.joinToString(", ")

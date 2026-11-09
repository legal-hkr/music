@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import sh.hnet.music.ui.components.FastScroller
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.components.SortChip
import sh.hnet.music.ui.components.formatCount

enum class LibraryView(val label: Int, val icon: Int, val selectedIcon: Int) {
    ALBUMS(R.string.label_view_albums, R.drawable.ic_album, R.drawable.ic_album_filled),
    ARTISTS(R.string.label_view_artists, R.drawable.ic_person, R.drawable.ic_person_filled),
    TRACKS(R.string.label_view_tracks, R.drawable.ic_music_note, R.drawable.ic_music_note_filled),
    PLAYLISTS(R.string.label_view_playlists, R.drawable.ic_playlist_play, R.drawable.ic_playlist_play_filled),
}

/** The Library destination: the view switch in the top bar and the chosen view (SPEC §3.2). */
@Composable
fun LibraryScreen(
    viewModel: BrowseViewModel,
    summary: sh.hnet.music.ui.settings.LibrarySummary,
    navigation: TrackNavigation,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onFolderPicked: (android.net.Uri) -> Unit,
    onSeeSkippedFiles: () -> Unit,
    snackbars: SnackbarHostState,
    onOpenPlaylist: (Long) -> Unit,
) {
    val hasTracks by viewModel.hasTracks.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LibraryView.ALBUMS) }
    // Switching views is a step in history: Back returns to the previous view
    var history by rememberSaveable { mutableStateOf(listOf<LibraryView>()) }
    val albumsGrid = rememberLazyGridState()
    val artistsList = rememberLazyListState()
    val tracksList = rememberLazyListState()
    val playlistsList = rememberLazyListState()

    BackHandler(enabled = history.isNotEmpty()) {
        view = history.last()
        history = history.dropLast(1)
    }

    when {
        hasTracks == null -> Unit
        // SPEC §12: no folder; folders being read; folders with nothing playable
        hasTracks == false && summary.folders == 0 -> WelcomeState(onFolderPicked)
        // Not in SPEC: every folder with tracks lost access, so "nothing playable" would be untrue
        hasTracks == false && summary.unavailable > 0 -> LostAccessState(onSeeSkippedFiles)
        hasTracks == false && summary.scanning > 0 -> EmptyState(R.drawable.ic_library_music, stringResource(R.string.msg_library_filling), Modifier.statusBarsPadding())
        hasTracks == false -> NothingPlayableState(onSeeSkippedFiles)
        else -> Column(Modifier.fillMaxSize()) {
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
                    LibraryView.ARTISTS -> ArtistsView(viewModel, artistsList, onOpenArtist)
                    LibraryView.TRACKS -> {
                        val tracks by viewModel.tracks.collectAsStateWithLifecycle()
                        tracks?.let { TrackList(viewModel, it, tracksList, navigation) }
                    }
                    LibraryView.PLAYLISTS -> PlaylistsView(viewModel, playlistsList, snackbars, onOpenPlaylist)
                }
            }
        }
    }
}

/**
 * The view switch (SPEC-playlists §3.2): connected toggle buttons, an icon on each, the label only on
 * the selected one, which takes the remaining width; switching animates the widths.
 */
@Composable
private fun ViewSwitch(selected: LibraryView, onSelect: (LibraryView) -> Unit) {
    val groupLabel = stringResource(R.string.content_description_library_view)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val views = LibraryView.entries
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween
        val compact = 56.dp
        val wide = maxWidth - (compact + gap) * (views.size - 1)
        val widthSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        Row(
            modifier = Modifier.selectableGroup().semantics { contentDescription = groupLabel },
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            views.forEachIndexed { index, item ->
                val isSelected = item == selected
                val label = stringResource(item.label)
                val width by animateDpAsState(if (isSelected) wide else compact, widthSpec, label = "viewWidth")
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(label) } },
                    state = rememberTooltipState(),
                ) {
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { if (it) onSelect(item) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            views.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // Selected: secondary container, fully round; the shape morph is the toggle's own
                        colors = ToggleButtonDefaults.colors(
                            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .width(width)
                            .heightIn(min = 40.dp)
                            .semantics {
                                role = Role.RadioButton
                                contentDescription = label
                            },
                    ) {
                        Icon(painterResource(if (isSelected) item.selectedIcon else item.icon), contentDescription = null, modifier = Modifier.size(20.dp))
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumsView(viewModel: BrowseViewModel, state: LazyGridState, onOpenAlbum: (String) -> Unit) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val sort by viewModel.albumSort.collectAsStateWithLifecycle()
    val sorted = albums ?: return
    val list = sorted.items
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                ListHeader(pluralStringResource(R.plurals.label_album_count, list.size, formatCount(list.size))) {
                    SortChip(AlbumSort.entries, sort, { stringResource(it.label()) }, viewModel::setAlbumSort)
                }
            }
            gridItems(list, key = { it.key }, contentType = { "album" }) { album ->
                AlbumTile(album, Modifier.animateItem()) { onOpenAlbum(album.key) }
            }
        }
        FastScroller(state, sorted.sections, firstItem = 1, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ArtistsView(viewModel: BrowseViewModel, state: LazyListState, onOpenArtist: (String) -> Unit) {
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val sorted = artists ?: return
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header", contentType = "header") {
                ListHeader(
                    pluralStringResource(R.plurals.label_artist_count, sorted.items.size, formatCount(sorted.items.size)),
                    Modifier.padding(horizontal = 16.dp),
                )
            }
            items(sorted.items, key = { it.key }, contentType = { "artist" }) { artist ->
                ArtistRow(artist) { onOpenArtist(artist.name) }
            }
        }
        FastScroller(state, sorted.sections, firstItem = 1, modifier = Modifier.align(Alignment.TopEnd))
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

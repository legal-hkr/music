@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.toShape
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.R
import sh.hnet.music.library.PlaylistSort
import sh.hnet.music.library.PlaylistSummary
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.EmptyState
import sh.hnet.music.ui.components.MenuItem
import sh.hnet.music.ui.components.NameDialog
import sh.hnet.music.ui.components.SortChip
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.durationText
import sh.hnet.music.ui.components.formatCount
import android.widget.Toast
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.core.text.htmlEncode

fun PlaylistSort.label(): Int = when (this) {
    PlaylistSort.RECENTLY_CHANGED -> R.string.label_sort_recently_changed
    PlaylistSort.RECENTLY_ADDED -> R.string.label_sort_recently_added
    PlaylistSort.NAME -> R.string.label_sort_name
}

/** The Library's Playlists view (SPEC-playlists §4) with its create menu (§3.3). */
@Composable
fun PlaylistsView(viewModel: BrowseViewModel, state: LazyListState, snackbars: SnackbarHostState, messageScope: CoroutineScope, onOpenPlaylist: (Long) -> Unit) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val sort by viewModel.playlistSort.collectAsStateWithLifecycle()
    val list = playlists ?: return
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    val deletedText = stringResource(R.string.msg_playlist_deleted)
    val undo = stringResource(R.string.button_undo)

    Box(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            EmptyState(R.drawable.ic_playlist_play, stringResource(R.string.msg_playlists_empty))
        } else {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp + 88.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header", contentType = "header") {
                    ListHeader(
                        pluralStringResource(R.plurals.label_playlist_count, list.size, formatCount(list.size)),
                        Modifier.padding(horizontal = 16.dp),
                    ) {
                        SortChip(PlaylistSort.entries, sort, { stringResource(it.label()) }, viewModel::setPlaylistSort)
                    }
                }
                items(list, key = { it.playlist.id }, contentType = { "playlist" }) { summary ->
                    PlaylistRow(
                        summary,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpenPlaylist(summary.playlist.id) },
                        onPlay = { viewModel.playPlaylist(summary) },
                        onPlayNext = { viewModel.playNext(summary.tracks) },
                        onAddToQueue = { viewModel.addToQueue(summary.tracks) },
                        onRename = { renaming = summary.playlist.id },
                        onDelete = {
                            messageScope.launch {
                                val deleted = viewModel.deletePlaylist(summary.playlist.id) ?: return@launch
                                snackbars.currentSnackbarData?.dismiss()
                                val text = AnnotatedString.fromHtml(deletedText.format(deleted.playlist.name.htmlEncode())).text
                                if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                    viewModel.restorePlaylist(deleted)
                                }
                            }
                        },
                    )
                }
            }
        }
        // Rises above a snackbar instead of hiding under it (§3.3)
        val lift by animateDpAsState(
            if (snackbars.currentSnackbarData != null) SNACKBAR_LIFT else 0.dp,
            MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "fabLift",
        )
        CreateMenu(
            visible = !(state.lastScrolledForward && state.canScrollBackward),
            onNew = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).offset { IntOffset(0, -lift.roundToPx()) },
        )
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.title_new_playlist),
            confirmLabel = stringResource(R.string.button_create),
            validate = { viewModel.playlistNameError(it) },
            onDismiss = { creating = false },
        ) { name ->
            creating = false
            // From the FAB: an empty playlist, opened at once (§7.4)
            scope.launch { onOpenPlaylist(viewModel.createPlaylist(name)) }
        }
    }
    renaming?.let { id ->
        val current = list.firstOrNull { it.playlist.id == id }?.playlist
        if (current == null) {
            renaming = null
        } else {
            val context = LocalContext.current
            val renamed = stringResource(R.string.msg_renamed)
            NameDialog(
                title = stringResource(R.string.title_rename_playlist),
                confirmLabel = stringResource(R.string.button_rename),
                initial = current.name,
                validate = { viewModel.playlistNameError(it, except = id) },
                onDismiss = { renaming = null },
            ) { name ->
                renaming = null
                if (name != current.name) {
                    viewModel.renamePlaylist(id, name)
                    Toast.makeText(context, renamed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** A one-line snackbar's height with its margin. */
private val SNACKBAR_LIFT = 72.dp

/** New playlist (and, from the import stage, Import playlist) behind one FAB (§3.3). */
@Composable
private fun CreateMenu(visible: Boolean, onNew: () -> Unit, modifier: Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.content_description_playlist_fab)
    FloatingActionButtonMenu(
        expanded = expanded,
        horizontalAlignment = Alignment.End,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                modifier = Modifier
                    .semantics { contentDescription = description }
                    .animateFloatingActionButton(visible = visible || expanded, alignment = Alignment.BottomEnd),
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) R.drawable.ic_close else R.drawable.ic_add } }
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onNew() },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.button_new_playlist)) },
        )
    }
}

@Composable
private fun PlaylistRow(
    summary: PlaylistSummary,
    modifier: Modifier,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = summary.playlist.name
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (menuOpen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        ),
        onClick = onClick,
        modifier = modifier,
        leadingContent = { PlaylistPicture(summary, 56.dp) },
        supportingContent = {
            Column {
                Text(playlistSummaryText(summary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (summary.missing > 0) MissingLine(summary.missing)
            }
        },
        trailingContent = {
            Box {
                TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.content_description_more_for, name), { menuOpen = true })
                PlaylistMenu(menuOpen, playable = summary.tracks.isNotEmpty(), { menuOpen = false }, onPlay, onPlayNext, onAddToQueue, onRename, onDelete)
            }
        },
    ) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "18 tracks – 1 h 12 min" of the playable entries, or "No tracks yet". */
@Composable
fun playlistSummaryText(summary: PlaylistSummary): String =
    if (summary.tracks.isEmpty()) {
        stringResource(R.string.label_playlist_no_tracks)
    } else {
        stringResource(
            R.string.label_playlist_summary,
            pluralStringResource(R.plurals.label_track_count, summary.tracks.size, formatCount(summary.tracks.size)),
            durationText(summary.durationMs),
        )
    }

@Composable
fun MissingLine(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_link_off), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(pluralStringResource(R.plurals.label_not_available_count, count, formatCount(count)), maxLines = 1)
    }
}

/** The row menu (§4.4): play actions while something can play, Rename, then Delete. */
@Composable
private fun PlaylistMenu(
    expanded: Boolean,
    playable: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val playsNext = stringResource(R.string.msg_plays_next)
    val addedToQueue = stringResource(R.string.msg_added_to_queue)
    val groups = if (playable) 3 else 2
    var group = 0
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        if (playable) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(group++, groups)) {
                MenuItem(stringResource(R.string.menu_play), R.drawable.ic_play_arrow, 0, 3) { onDismiss(); onPlay() }
                MenuItem(stringResource(R.string.menu_play_next), R.drawable.ic_queue_play_next, 1, 3) {
                    onDismiss(); onPlayNext(); Toast.makeText(context, playsNext, Toast.LENGTH_SHORT).show()
                }
                MenuItem(stringResource(R.string.menu_add_to_queue), R.drawable.ic_queue_music, 2, 3) {
                    onDismiss(); onAddToQueue(); Toast.makeText(context, addedToQueue, Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        }
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(group++, groups)) {
            MenuItem(stringResource(R.string.menu_rename), R.drawable.ic_edit, 0, 1) { onDismiss(); onRename() }
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(group, groups)) {
            MenuItem(stringResource(R.string.menu_delete), R.drawable.ic_delete, 0, 1, error = true) { onDismiss(); onDelete() }
        }
    }
}

/**
 * A playlist's picture (§4.2): a 2 × 2 mosaic of four albums' covers, one album's cover, or the
 * placeholder with the playlist icon when nothing can play.
 */
@Composable
fun PlaylistPicture(summary: PlaylistSummary, size: Dp) {
    val covers = summary.coverTracks
    val shape = RoundedCornerShape(if (size >= 96.dp) 28.dp else 12.dp)
    Box(Modifier.size(size).clip(shape)) {
        when {
            covers.size == 4 -> Column {
                for (row in 0..1) Row {
                    for (column in 0..1) {
                        val track = covers[row * 2 + column]
                        // A tile without a cover: the plain placeholder tile, not a shrunken cover placeholder
                        if (track.hasEmbeddedCover) {
                            Cover(track, RectangleShape, Modifier.size(size / 2))
                        } else {
                            Box(Modifier.size(size / 2).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_album), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (size >= 96.dp) 32.dp else 18.dp))
                            }
                        }
                    }
                }
            }
            covers.isNotEmpty() -> Cover(covers.first(), RectangleShape, Modifier.size(size))
            else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize(0.62f).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_playlist_play), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.3f))
                }
            }
        }
    }
}


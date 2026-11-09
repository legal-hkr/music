@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.library

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.R
import sh.hnet.music.data.shownTitle
import sh.hnet.music.library.PlaylistPage
import sh.hnet.music.library.PlaylistRow
import sh.hnet.music.ui.components.FastScroller
import sh.hnet.music.ui.components.MenuItem
import sh.hnet.music.ui.components.NameDialog
import sh.hnet.music.ui.components.PlayState
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.components.TrackRow
import sh.hnet.music.ui.components.boldNamesResource
import sh.hnet.music.ui.components.dragHandle
import sh.hnet.music.ui.components.formatDuration
import sh.hnet.music.ui.components.longPressDrag
import sh.hnet.music.ui.components.rememberReorderState
import sh.hnet.music.ui.components.reorderableItem

/** Rows above the entries: header, buttons (and the empty note). */
private const val HEAD_ITEMS = 2

/**
 * A playlist (SPEC-playlists §5): header, Play / Shuffle / actions, the entries in order. Reordering
 * is always on (handle or long press), a swipe removes with Undo; missing entries stay in place.
 */
@Composable
fun PlaylistScreen(
    viewModel: BrowseViewModel,
    id: Long,
    navigation: TrackNavigation,
    snackbars: SnackbarHostState,
    /** Outlives this page: a removal's or deletion's Undo stays after leaving it. */
    messageScope: CoroutineScope,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val page by remember(id) { viewModel.playlist(id) }.collectAsStateWithLifecycle(LOADING)
    val playing by viewModel.playingEntry.collectAsStateWithLifecycle(null to false)
    val list = rememberLazyListState()
    val titleShown by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removingMissing by rememberSaveable { mutableStateOf(false) }
    // Change order (owner's decision): the page reads like an album's, handles only in this mode;
    // [orderBefore] is what Cancel puts back (each drop is saved as it happens)
    var reordering by rememberSaveable { mutableStateOf(false) }
    var orderBefore by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    val export = rememberPlaylistExport(viewModel, snackbars, messageScope)
    var leaving by remember { mutableStateOf(false) }
    val gone = stringResource(R.string.msg_playlist_gone)
    val removedText = stringResource(R.string.msg_removed_from_playlist)
    val deletedText = stringResource(R.string.msg_playlist_deleted)
    val undo = stringResource(R.string.button_undo)

    // Deleted elsewhere (another tab's page, the Playlists view): close with a note
    LaunchedEffect(page) {
        if (page == null && !leaving) {
            leaving = true
            Toast.makeText(context, gone, Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    val shown = page?.takeIf { it !== LOADING }

    // Local order while dragging; the database leads otherwise
    val rows = remember { mutableStateListOf<PlaylistRow>() }
    val reorder = rememberReorderState(
        listState = list,
        // Starred keeps the order of starring (newest first): nothing moves there (SPEC-now-playing-v2 §6)
        canMove = { key -> key is Long && shown?.summary?.starred != true },
        onStart = {},
        onMove = { from, to ->
            val a = rows.indexOfFirst { it.entry.id == from }
            val b = rows.indexOfFirst { it.entry.id == to }
            if (a >= 0 && b >= 0) rows.add(b, rows.removeAt(a))
        },
        onDrop = { viewModel.reorderPlaylist(id, rows.map { it.entry.id }) },
    )
    LaunchedEffect(shown, reorder.draggedKey) {
        if (reorder.draggedKey == null && shown != null) {
            rows.clear()
            rows.addAll(shown.rows)
        }
    }

    val unstarredText = stringResource(R.string.msg_unstarred)
    fun remove(row: PlaylistRow) = messageScope.launch {
        // In Starred, a swipe unstars (§6); a missing entry there is only removed
        val track = row.track
        if (shown?.summary?.starred == true && track != null) {
            val entries = viewModel.unstar(track.documentUri)
            if (entries.isEmpty()) return@launch
            snackbars.currentSnackbarData?.dismiss()
            val message = AnnotatedString.fromHtml(unstarredText.format(track.displayTitle.htmlEncode())).text
            if (snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                entries.forEach { viewModel.restoreEntry(it) }
            }
            return@launch
        }
        val entry = viewModel.removeEntry(row.entry.id) ?: return@launch
        val name = shown?.playlist?.name.orEmpty()
        snackbars.currentSnackbarData?.dismiss()
        val message = AnnotatedString.fromHtml(removedText.format((row.track?.displayTitle ?: entry.shownTitle).htmlEncode(), name.htmlEncode())).text
        if (snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            viewModel.restoreEntry(entry)
        }
    }

    // Back ends the mode keeping the new order, as Done
    BackHandler(enabled = reordering) { reordering = false }
    if (reordering && (shown == null || shown.summary.starred || shown.rows.size < 2)) reordering = false

    Column(Modifier.fillMaxSize()) {
        if (reordering) {
            TopAppBar(
                title = { Text(stringResource(R.string.title_reorder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_cancel_reorder), {
                        if (orderBefore.isNotEmpty() && orderBefore != rows.map { it.entry.id }) viewModel.reorderPlaylist(id, orderBefore)
                        reordering = false
                    })
                },
                actions = { TextButton(onClick = { reordering = false }) { Text(stringResource(R.string.button_done)) } },
            )
        } else {
            TopAppBar(
                title = {
                    AnimatedVisibility(titleShown && shown != null, enter = fadeIn(), exit = fadeOut()) {
                        Text(shown?.playlist?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            )
        }
        if (shown == null) return@Column
        val summary = shown.summary
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header") { Header(shown) }
                item(key = "actions") {
                    Actions(
                        playable = summary.tracks.isNotEmpty(),
                        builtin = summary.starred,
                        canReorder = !summary.starred && rows.size > 1,
                        onReorder = {
                            orderBefore = rows.map { it.entry.id }
                            reordering = true
                        },
                        missing = summary.missing,
                        onRemoveMissing = { removingMissing = true },
                        onPlay = { viewModel.playPlaylist(summary, shuffle = false) },
                        onShuffle = { viewModel.playPlaylist(summary, shuffle = true) },
                        onRename = { renaming = true },
                        onExport = { export.start(id, shown.playlist.name) },
                        onDelete = {
                            leaving = true
                            onBack()
                            messageScope.launch {
                                val deleted = viewModel.deletePlaylist(id) ?: return@launch
                                snackbars.currentSnackbarData?.dismiss()
                                val text = AnnotatedString.fromHtml(deletedText.format(deleted.playlist.name.htmlEncode())).text
                                if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                    viewModel.restorePlaylist(deleted)
                                }
                            }
                        },
                    )
                }
                if (rows.isEmpty()) item(key = "empty") {
                    Text(
                        boldNamesResource(R.string.msg_playlist_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
                itemsIndexed(rows, key = { _, row -> row.entry.id }, contentType = { _, row -> if (row.track != null) "track" else "missing" }) { position, row ->
                    val key = row.entry.id
                    val moves = moveActions(position, rows.size) { to ->
                        val ids = rows.map { it.entry.id }.toMutableList()
                        ids.add(to, ids.removeAt(position))
                        viewModel.reorderPlaylist(id, ids)
                    }
                    Box(
                        Modifier
                            .then(if (reorder.draggedKey == key) Modifier else Modifier.animateItem())
                            .reorderableItem(reorder, key),
                    ) {
                        // Swipe removes outside Change order; there the row moves instead
                        SwipeToRemove(enabled = !reordering && reorder.draggedKey == null, onRemove = { remove(row) }) {
                            val starredList = summary.starred
                            val handle: @Composable () -> Unit = {
                                if (reordering) Box(Modifier.size(48.dp).dragHandle(reorder, key), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null)
                                }
                            }
                            val rowModifier = Modifier
                                .then(if (reordering) Modifier.longPressDrag(reorder, key) else Modifier)
                                .background(MaterialTheme.colorScheme.surface)
                                .semantics { if (reordering) customActions = moves }
                            val track = row.track
                            if (track != null) {
                                val state = when {
                                    playing.first != key -> PlayState.NONE
                                    playing.second -> PlayState.PLAYING
                                    else -> PlayState.PAUSED
                                }
                                TrackRow(
                                    track = track,
                                    supporting = playlistLine(track.artists, track.album),
                                    state = state,
                                    navigation = navigation,
                                    onClick = { viewModel.playPlaylist(summary, start = summary.entryIds.indexOf(key)) },
                                    modifier = rowModifier,
                                    // Positions as an album's track numbers; in Change order the handles take over
                                    number = if (reordering) null else position + 1,
                                    showNumberColumn = !reordering,
                                    // Starred: the track menu's Unstar does it
                                    onRemoveFromPlaylist = if (starredList) null else ({ remove(row) }),
                                    endContent = handle,
                                )
                            } else {
                                MissingRow(row, rowModifier, number = if (reordering) null else position + 1, onRemove = { remove(row) }, handle = handle)
                            }
                        }
                    }
                }
            }
            if (rows.size > 60 && reorder.draggedKey == null) {
                FastScroller(list, rows.indices.map { bucketOf(it) }, firstItem = HEAD_ITEMS, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }

    if (removingMissing && shown != null) {
        val missingIds = shown.rows.filter { it.track == null }.map { it.entry.id }
        val removed = pluralStringResource(R.plurals.msg_removed_n, missingIds.size, missingIds.size)
        // Confirmed, no Undo: what goes is the automatic comeback, which the text says (§6.4)
        AlertDialog(
            onDismissRequest = { removingMissing = false },
            icon = { Icon(painterResource(R.drawable.ic_link_off), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(pluralStringResource(R.plurals.title_remove_missing, missingIds.size, missingIds.size)) },
            text = { Text(boldNamesResource(R.string.msg_remove_missing, shown.playlist.name)) },
            dismissButton = { TextButton(onClick = { removingMissing = false }) { Text(stringResource(R.string.button_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        removingMissing = false
                        viewModel.removeEntries(id, missingIds)
                        Toast.makeText(context, removed, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.button_remove)) }
            },
        )
    }
    if (renaming && shown != null) {
        val renamed = stringResource(R.string.msg_renamed)
        NameDialog(
            title = stringResource(R.string.title_rename_playlist),
            confirmLabel = stringResource(R.string.button_rename),
            initial = shown.playlist.name,
            validate = { viewModel.playlistNameError(it, except = id) },
            onDismiss = { renaming = false },
        ) { name ->
            renaming = false
            if (name != shown.playlist.name) {
                viewModel.renamePlaylist(id, name)
                Toast.makeText(context, renamed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Marks "not loaded yet" apart from "doesn't exist" (null). */
private val LOADING = PlaylistPage(sh.hnet.music.data.Playlist(id = -1, name = "", createdAt = 0, changedAt = 0), emptyList())

/** "1–100", "101–200": a playlist has no sort key, so the fast scroller shows positions (§5.1). */
private fun bucketOf(index: Int): String {
    val start = index / 100 * 100 + 1
    return "$start–${start + 99}"
}

/** "artists – album", always both: a playlist mixes albums (§5.3). */
private fun playlistLine(artists: List<String>, album: String?): String? =
    listOfNotNull(artists.joinToString(", ").ifEmpty { null }, album?.ifBlank { null }).joinToString(" – ").ifEmpty { null }

@Composable
private fun moveActions(position: Int, count: Int, move: (Int) -> Unit): List<CustomAccessibilityAction> {
    val up = stringResource(R.string.label_move_up)
    val down = stringResource(R.string.label_move_down)
    val top = stringResource(R.string.label_move_to_top)
    val bottom = stringResource(R.string.label_move_to_bottom)
    return buildList {
        if (position > 0) {
            add(CustomAccessibilityAction(up) { move(position - 1); true })
            add(CustomAccessibilityAction(top) { move(0); true })
        }
        if (position < count - 1) {
            add(CustomAccessibilityAction(down) { move(position + 1); true })
            add(CustomAccessibilityAction(bottom) { move(count - 1); true })
        }
    }
}

/** End-to-start swipe with the error container behind, as in the queue (§5.5). */
@Composable
private fun SwipeToRemove(enabled: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove()
            // Saved per key: an entry put back by Undo (same id) would remove itself again
            dismiss.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun Header(page: PlaylistPage) {
    val summary = page.summary
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaylistPicture(summary, 136.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(
                page.playlist.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(playlistSummaryText(summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summary.missing > 0) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    MissingLine(summary.missing)
                }
            }
        }
    }
}

/** Play and Shuffle (equal width), then the page menu's button (§5.1, §5.2). */
@Composable
private fun Actions(
    playable: Boolean,
    /** Starred: Export (and Remove missing tracks) only (§6). */
    builtin: Boolean,
    /** Change order is offered (not in Starred, not with fewer than two entries). */
    canReorder: Boolean,
    onReorder: () -> Unit,
    missing: Int,
    onRemoveMissing: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val noTracks = stringResource(R.string.desc_no_tracks_to_play)
    val disabled = Modifier.semantics { if (!playable) stateDescription = noTracks }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlay, enabled = playable, modifier = Modifier.weight(1f).height(height).then(disabled), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
            Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Text(stringResource(R.string.button_play), style = ButtonDefaults.textStyleFor(height), modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = onShuffle, enabled = playable, modifier = Modifier.weight(1f).height(height).then(disabled), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Text(stringResource(R.string.button_shuffle), style = ButtonDefaults.textStyleFor(height), modifier = Modifier.padding(start = 8.dp))
        }
        Box {
            val label = stringResource(R.string.content_description_playlist_actions)
            FilledTonalIconButton(onClick = { menuOpen = true }, modifier = Modifier.size(height)) {
                Icon(painterResource(R.drawable.ic_more_horiz), contentDescription = label)
            }
            // Rename | Remove missing tracks (only while some are missing) | Delete (§5.2); Starred: no Rename, no Delete
            val groups = 1 + (if (missing > 0) 1 else 0) + (if (builtin) 0 else 1)
            DropdownMenuPopup(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, groups)) {
                    // Rename, Change order, Export – each only where it applies
                    val items = (if (builtin) 0 else 1) + (if (canReorder) 1 else 0) + 1
                    var index = 0
                    if (!builtin) MenuItem(stringResource(R.string.menu_rename), R.drawable.ic_edit, index++, items) { menuOpen = false; onRename() }
                    if (canReorder) MenuItem(stringResource(R.string.menu_reorder), R.drawable.ic_drag_indicator, index++, items) { menuOpen = false; onReorder() }
                    MenuItem(stringResource(R.string.menu_export), R.drawable.ic_file_export, index, items) { menuOpen = false; onExport() }
                }
                if (missing > 0) {
                    Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, groups)) {
                        MenuItem(stringResource(R.string.menu_remove_missing), R.drawable.ic_link_off, 0, 1) { menuOpen = false; onRemoveMissing() }
                    }
                }
                if (!builtin) {
                    Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(groups - 1, groups)) {
                        MenuItem(stringResource(R.string.menu_delete), R.drawable.ic_delete, 0, 1, error = true) { menuOpen = false; onDelete() }
                    }
                }
            }
        }
    }
}

/**
 * An entry the library doesn't have now (§6.2): the disabled headline colour, a readable "Not
 * available" line, not playable; its menu only removes it, and it still moves and swipes.
 */
@Composable
private fun MissingRow(row: PlaylistRow, modifier: Modifier, number: Int?, onRemove: () -> Unit, handle: @Composable () -> Unit) {
    val entry = row.entry
    val notAvailable = stringResource(R.string.desc_not_available)
    var menuOpen by remember { mutableStateOf(false) }
    val supporting = entry.artists.joinToString(", ").ifEmpty { null }
        ?.let { stringResource(R.string.label_not_available_artist, it) } ?: stringResource(R.string.label_not_available)
    ListItem(
        onClick = {},
        enabled = false,
        modifier = modifier.semantics { stateDescription = notAvailable },
        // The position, in the same 24 dp column as a track row's number
        leadingContent = number?.let {
            {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    Text(it.toString(), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_link_off), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.durationMs?.let { Text(formatDuration(it), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")) }
                // The buttons stay active on a disabled row
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    Box {
                        TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.content_description_more_for, entry.shownTitle), { menuOpen = true })
                        DropdownMenuPopup(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                                MenuItem(stringResource(R.string.menu_remove_from_playlist), R.drawable.ic_playlist_remove, 0, 1) { menuOpen = false; onRemove() }
                            }
                        }
                    }
                    handle()
                }
            }
        },
    ) {
        Text(entry.shownTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


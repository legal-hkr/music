@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.player

import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.media3.common.Player
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import kotlinx.coroutines.launch
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.playback.PlayerConnection
import sh.hnet.music.playback.QueueItem
import sh.hnet.music.playback.QueueSnapshot
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.dragHandle
import sh.hnet.music.ui.components.durationText
import sh.hnet.music.ui.components.longPressDrag
import sh.hnet.music.ui.components.reorderableItem
import sh.hnet.music.ui.components.rememberReorderState

/** A row of the sheet: a queue item with a key that stays with it while rows move. */
private data class Row(val key: String, val item: QueueItem)

/** Keys unique even when a track is queued twice. */
private fun rowsOf(queue: QueueSnapshot): List<Row> {
    val seen = HashMap<String, Int>()
    return queue.items.map { item ->
        val n = seen.merge(item.mediaId, 1, Int::plus)!!
        Row("${item.mediaId}#$n", item)
    }
}

/**
 * The queue over now playing (SPEC §10), the whole of it in play order (owner's decision): played
 * tracks muted above the current one (tap plays one again; they don't move), the current track,
 * which the sheet opens on, then what follows – reordered by drag (never
 * above the current track; not while shuffled, when the order shown is the shuffle's). Swipe to
 * remove with Undo, clear with a confirmation. Repeat one lists the current track only.
 */
@Composable
fun QueueSheet(
    queue: QueueSnapshot,
    shuffle: Boolean,
    /** Repeat one: only the current track is listed (it is the whole queue until switched off). */
    repeatMode: Int,
    tracks: Map<String, LibraryTrack>,
    player: PlayerConnection,
    onDismiss: () -> Unit,
    /** The current track and what follows, as the sheet shows them (SPEC-playlists §8). */
    onSaveAsPlaylist: (List<LibraryTrack>) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    // Played tracks are listed above the current one, which the sheet opens on (owner's decision)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (repeatMode == Player.REPEAT_MODE_ONE) 0 else queue.current.coerceAtLeast(0))
    // By key, not position: while a row is dragged the positions change but the current track doesn't
    val currentKey = remember(queue) { rowsOf(queue).getOrNull(queue.current)?.key }
    val currentKeyNow by rememberUpdatedState(currentKey)

    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    // The whole queue in play order: played tracks, the current one, what follows. Rows before the
    // current one (playedCount of them) can be played again but not moved, and nothing moves above
    // the current track. Drag is off while shuffled, so a row's position is the player's index.
    val rows = remember { mutableStateListOf<Row>() }
    var playedCount by remember { mutableStateOf(0) }
    val playedNow by rememberUpdatedState(playedCount)
    var dragFrom by remember { mutableStateOf(-1) }
    var confirmClear by remember { mutableStateOf(false) }
    val reorder = rememberReorderState(
        listState = listState,
        // Only what follows the current track moves, and only among itself
        canMove = { key -> key != currentKeyNow && rows.indexOfFirst { it.key == key } > playedNow },
        onStart = { key -> dragFrom = rows.indexOfFirst { it.key == key } },
        onMove = { from, to ->
            val a = rows.indexOfFirst { it.key == from }
            val b = rows.indexOfFirst { it.key == to }
            if (a >= 0 && b >= 0) rows.add(b, rows.removeAt(a))
        },
        onDrop = { key ->
            val to = rows.indexOfFirst { it.key == key }
            if (dragFrom >= 0 && to >= 0) player.move(dragFrom, to)
            dragFrom = -1
        },
    )
    // The player leads, except while a row is being dragged
    LaunchedEffect(queue, repeatMode, reorder.draggedKey) {
        if (reorder.draggedKey == null) {
            val currentBefore = rows.getOrNull(playedCount)?.key
            val all = rowsOf(queue)
            val current = queue.current.coerceAtLeast(0)
            rows.clear()
            if (repeatMode == Player.REPEAT_MODE_ONE) {
                // Repeat one: the current track is all that plays; the rest comes back when it's off
                rows.addAll(all.drop(current).take(1))
                playedCount = 0
            } else {
                rows.addAll(all)
                playedCount = current
            }
            // A new current track (Next, the end of one, a played one tapped) comes to the top
            if (rows.getOrNull(playedCount)?.key != currentBefore) {
                // After the next frame, when the room at the list's end is laid out too
                withFrameNanos { }
                listState.scrollToItem(playedCount)
            }
        }
    }
    val removedText = stringResource(R.string.msg_removed_from_queue)
    val undo = stringResource(R.string.button_undo)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Header(queue, repeatMode, tracks) {
                    onSaveAsPlaylist(queue.items.drop(queue.current.coerceAtLeast(0)).mapNotNull { tracks[it.mediaId] })
                }
                // The list fills the sheet, and with played tracks above the current one it ends in
                // enough room for the current track to scroll to the top: the sheet opens halfway and
                // shows the list's top, so the current track must be able to stand there however few
                // tracks follow it. Rows are at least ROW_MIN tall, so the estimate of what follows
                // errs on the small side and the room on the large one.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                val following = rows.size - playedCount
                val room = if (playedCount == 0) 0.dp else (maxHeight - ROW_MIN * following - CLEAR_MIN).coerceAtLeast(0.dp)
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    val entry: @Composable LazyItemScope.(Int, Row) -> Unit = { position, row ->
                        val isCurrent = row.key == currentKey
                        val played = position < playedCount
                        // Played rows and the current one stay put; nothing moves above the current one
                        val movable = !shuffle && !isCurrent && !played
                        val track = tracks[row.item.mediaId]
                        val title = track?.displayTitle ?: row.item.title.orEmpty()
                        val rowModifier = Modifier
                            .then(if (reorder.draggedKey == row.key) Modifier else Modifier.animateItem())
                            .reorderableItem(reorder, row.key)
                        val content: @Composable () -> Unit = {
                            QueueRow(
                                row = row,
                                track = track,
                                isCurrent = isCurrent,
                                played = played,
                                // The current track stays where it is: no handle, no long-press drag
                                reorderable = movable,
                                onClick = { player.jumpTo(row.item.index) },
                                onMoveUp = if (movable && position > playedCount + 1) ({ player.move(row.item.index, row.item.index - 1) }) else null,
                                onMoveDown = if (movable && position < rows.lastIndex) ({ player.move(row.item.index, row.item.index + 1) }) else null,
                                onRemove = if (!isCurrent) ({ removeWithUndo(player, row.item.index, track, title, removedText, undo, snackbars, scope) { sheetState.expand() } }) else null,
                                modifier = if (movable) Modifier.longPressDrag(reorder, row.key) else Modifier,
                                handleModifier = Modifier.dragHandle(reorder, row.key),
                            )
                        }
                        Box(rowModifier) {
                            // One structure for every row, swiping only switched off: replacing the
                            // row's content when a drag starts would drop the drag's own gesture
                            val swipeable = !isCurrent && reorder.draggedKey == null
                            val dismiss = rememberSwipeToDismissBoxState()
                            LaunchedEffect(dismiss.currentValue) {
                                if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    removeWithUndo(player, row.item.index, track, title, removedText, undo, snackbars, scope) { sheetState.expand() }
                                    // The state is saved per row key: left as dismissed, a row put back
                                    // by Undo (same key) would remove itself again at once. Last, as
                                    // the change restarts this effect.
                                    dismiss.snapTo(SwipeToDismissBoxValue.Settled)
                                }
                            }
                            SwipeToDismissBox(
                                state = dismiss,
                                enableDismissFromStartToEnd = false,
                                // The current track can't be swiped away (Next moves on)
                                enableDismissFromEndToStart = swipeable,
                                backgroundContent = {
                                    // Only while swiping: behind the inset, rounded current row it would show
                                    if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                },
                            ) { content() }
                        }
                    }
                    val currentRow = rows.getOrNull(playedCount)
                    itemsIndexed(rows.take(playedCount), key = { _, row -> row.key }) { position, row -> entry(position, row) }
                    // The current track scrolls with the list (owner's decision; the sheet opens on it)
                    if (currentRow != null) item(key = currentRow.key) { entry(playedCount, currentRow) }
                    itemsIndexed(rows.drop(playedCount + 1), key = { _, row -> row.key }) { i, row -> entry(playedCount + 1 + i, row) }
                    if (rows.size > 1) {
                        item(key = "clear") {
                            OutlinedButton(
                                onClick = { confirmClear = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_clear_all), contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.button_clear_queue), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (room > 0.dp) item(key = "room") { Spacer(Modifier.height(room)) }
                }
                }
            }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter)) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }

    if (confirmClear) {
        val following = (rows.size - 1).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(painterResource(R.drawable.ic_clear_all), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.title_clear_queue)) },
            text = { Text(pluralStringResource(R.plurals.msg_clear_queue, following, following)) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.button_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; player.clearAroundCurrent() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.button_clear)) }
            },
        )
    }
}

/** Removes at once and offers Undo, which puts the track back where it was. */
private fun removeWithUndo(
    player: PlayerConnection,
    index: Int,
    track: LibraryTrack?,
    title: String,
    template: String,
    undo: String,
    snackbars: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    /** Brings the sheet's bottom, where the snackbar shows, onto the screen. */
    reveal: suspend () -> Unit,
) {
    if (!player.remove(index)) return
    scope.launch {
        snackbars.currentSnackbarData?.dismiss()
        reveal()
        val message = AnnotatedString.fromHtml(template.format(title.htmlEncode())).text
        // A track no longer in the library can't be queued again, so it gets no Undo
        val result = snackbars.showSnackbar(message, actionLabel = track?.let { undo }, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed && track != null) player.insert(index, track)
    }
}

@Composable
private fun Header(queue: QueueSnapshot, repeatMode: Int, tracks: Map<String, LibraryTrack>, onSaveAsPlaylist: () -> Unit) {
    val following = if (queue.current < 0) emptyList() else queue.items.drop(queue.current + 1)
    // The button sits beside the title, or under it when the two don't fit (Polish at 1.3)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
    Column {
        Text(stringResource(R.string.title_queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        val summary = if (repeatMode == Player.REPEAT_MODE_ONE) {
            stringResource(R.string.label_queue_repeat_one)
        } else if (following.isEmpty()) {
            stringResource(R.string.label_queue_none_remaining)
        } else {
            val duration = following.sumOf { tracks[it.mediaId]?.durationMs ?: 0L }
            pluralStringResource(R.plurals.label_queue_remaining, following.size, following.size, durationText(duration))
        }
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (queue.items.isNotEmpty()) {
        FilledTonalButton(onClick = onSaveAsPlaylist, contentPadding = ButtonDefaults.SmallContentPadding, modifier = Modifier.heightIn(min = 40.dp)) {
            Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.button_save_as_playlist), modifier = Modifier.padding(start = 8.dp))
        }
    }
    }
}

@Composable
private fun QueueRow(
    row: Row,
    track: LibraryTrack?,
    isCurrent: Boolean,
    /** Before the current track: its text muted (played; tap plays it again). */
    played: Boolean,
    reorderable: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    /** The row's drag (long press anywhere on it). */
    modifier: Modifier = Modifier,
    /** The handle's drag. */
    handleModifier: Modifier = Modifier,
) {
    val title = track?.displayTitle ?: row.item.title.orEmpty()
    val artists = track?.artists?.joinToString(", ") ?: row.item.artists.orEmpty()
    val upLabel = stringResource(R.string.label_move_up)
    val downLabel = stringResource(R.string.label_move_down)
    val removeLabel = stringResource(R.string.label_remove_from_queue)
    val reorderLabel = stringResource(R.string.content_description_reorder, title)
    // Played rows on the sheet's own background, only their text muted (owner trying it out)
    val container = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val content = when {
        isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
        played -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = container,
        contentColor = content,
        shape = if (isCurrent) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isCurrent) 8.dp else 0.dp)
            .then(modifier)
            .semantics {
                customActions = listOfNotNull(
                    onMoveUp?.let { CustomAccessibilityAction(upLabel) { it(); true } },
                    onMoveDown?.let { CustomAccessibilityAction(downLabel) { it(); true } },
                    onRemove?.let { CustomAccessibilityAction(removeLabel) { it(); true } },
                )
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(start = if (isCurrent) 8.dp else 16.dp, end = 4.dp),
        ) {
            if (isCurrent) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            } else {
                Cover(track, RoundedCornerShape(12.dp), Modifier.size(40.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artists.isNotEmpty()) {
                    Text(
                        artists,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) content else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (reorderable) {
                Box(
                    Modifier
                        .size(48.dp)
                        .then(handleModifier)
                        .semantics { contentDescription = reorderLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null)
                }
            }
        }
    }
}

/** The least height of a queue row and of the Clear queue button with its padding (QueueRow, the clear item). */
private val ROW_MIN = 64.dp
private val CLEAR_MIN = 72.dp

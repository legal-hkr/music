@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import sh.hnet.music.ui.components.moveActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.ui.components.LazySettingsPage
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SETTINGS_GROUP_GAP
import sh.hnet.music.ui.components.SETTINGS_ROW_GAP
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsGroupHeader
import sh.hnet.music.ui.components.SettingsRadioRow
import sh.hnet.music.ui.components.dragHandle
import sh.hnet.music.ui.components.groupShapes
import sh.hnet.music.ui.components.rememberReorderState
import sh.hnet.music.ui.components.reorderableItem
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.library.BrowseViewModel
import sh.hnet.music.ui.library.LibraryView
import sh.hnet.music.ui.library.LibraryViewSwitch

/**
 * Settings › Library views (SPEC-settings-v2 §4): the order of the four views, reordered by
 * dragging (the queue's and playlists' own component) and saved on drop, under a preview of the
 * switch in that order, then the view the Library opens on.
 */
@Composable
fun LibraryViewsScreen(viewModel: BrowseViewModel, onBack: () -> Unit) {
    val preferences = (LocalContext.current.applicationContext as MusicApplication).preferences
    val scope = rememberCoroutineScope()
    val order by viewModel.viewOrder.collectAsStateWithLifecycle()
    val startView by preferences.startView.collectAsStateWithLifecycle(LibraryView.ALBUMS)
    val list = rememberLazyListState()

    // Local order while dragging; the saved order leads otherwise
    val rows = remember { mutableStateListOf<LibraryView>() }
    val reorder = rememberReorderState(
        listState = list,
        canMove = { key -> key is LibraryView },
        onStart = {},
        onMove = { from, to ->
            val a = rows.indexOf(from)
            val b = rows.indexOf(to)
            if (a >= 0 && b >= 0) rows.add(b, rows.removeAt(a))
        },
        onDrop = { viewModel.setViewOrder(rows.toList()) },
    )
    LaunchedEffect(order, reorder.draggedKey) {
        if (reorder.draggedKey == null) {
            rows.clear()
            rows.addAll(order)
        }
    }

    LazySettingsPage(title = stringResource(R.string.title_library_views), onBack = onBack, state = list) {
        // A preview of the Library's tabs in the current order, on a canvas at the top (owner's decision)
        item(key = "preview", contentType = "preview") {
            Surface(
                shape = MaterialTheme.shapes.large,
                // Lowest, not the groups' surfaceContainer: the unselected tabs are surfaceContainer themselves
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().padding(bottom = SETTINGS_GROUP_GAP),
            ) {
                Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                    Text(
                        stringResource(R.string.label_views_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    LibraryViewSwitch(
                        views = rows,
                        selected = startView ?: rows.firstOrNull() ?: LibraryView.ALBUMS,
                        onSelect = {},
                        // Only a picture: TalkBack reads the order from the list below, touches go nowhere
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            },
                    )
                }
            }
        }
        item(key = "order-header", contentType = "header") { SettingsGroupHeader(stringResource(R.string.title_views_order)) }
        itemsIndexed(rows, key = { _, view -> view }, contentType = { _, _ -> "view" }) { position, view ->
            val moves = moveActions(position, rows.size) { to ->
                val moved = rows.toMutableList()
                moved.add(to, moved.removeAt(position))
                viewModel.setViewOrder(moved)
            }
            // The gap goes outside the lifted layer, so the shadow falls only under the row
            Box(
                Modifier
                    .then(if (reorder.draggedKey == view) Modifier else Modifier.animateItem())
                    .padding(bottom = if (position < rows.lastIndex) SETTINGS_ROW_GAP else 0.dp)
                    .reorderableItem(reorder, view, liftedShape = LIFTED_SHAPE, liftedElevation = 1.dp),
            ) {
                ViewRow(
                    view, position, rows.size, dragged = reorder.draggedKey == view, moves = moves,
                    handleModifier = Modifier.dragHandle(reorder, view),
                )
            }
        }
        item(key = "opens-on", contentType = "group") {
            Column(Modifier.padding(top = SETTINGS_GROUP_GAP).selectableGroup()) {
                SettingsGroup(stringResource(R.string.title_opens_on)) {
                    row { shapes ->
                        SettingsRadioRow(shapes, stringResource(R.string.label_last_used), selected = startView == null, onSelect = {
                            scope.launch { preferences.setStartView(null) }
                        })
                    }
                    rows.forEach { view ->
                        row { shapes ->
                            SettingsRadioRow(shapes, stringResource(view.label), selected = startView == view, onSelect = {
                                scope.launch { preferences.setStartView(view) }
                            })
                        }
                    }
                }
            }
        }
    }
}

/** One view in the Order group: icon box, name, the drag handle; lifted while dragged (§4.1). */
@Composable
private fun ViewRow(
    view: LibraryView,
    position: Int,
    count: Int,
    dragged: Boolean,
    moves: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
) {
    val name = stringResource(view.label)
    val lifted = LIFTED_SHAPE
    SegmentedListItem(
        onClick = {},
        shapes = if (dragged) {
            ListItemDefaults.shapes(shape = lifted, selectedShape = lifted, pressedShape = lifted, focusedShape = lifted, hoveredShape = lifted, draggedShape = lifted)
        } else groupShapes(position, count),
        colors = if (dragged) ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) else settingsRowColors(),
        leadingContent = { RowIcon(view.icon) },
        trailingContent = {
            Box(handleModifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = stringResource(R.string.content_description_reorder, name))
            }
        },
        modifier = modifier.fillMaxWidth().semantics { customActions = moves },
    ) {
        Text(name)
    }
}

/** A lifted row (§4.1): radius 16, elevation 1. */
private val LIFTED_SHAPE = RoundedCornerShape(16.dp)

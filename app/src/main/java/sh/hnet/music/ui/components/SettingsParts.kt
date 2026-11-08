@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.music.R

/**
 * A settings page: on the Settings root a large flexible bar collapsing on scroll, on the pages it
 * opens a small bar with Back; under it a scrolling column of groups.
 *
 * @param onBack Null on the root (a tab has no Back)
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    /** False when the page is drawn inside something that already took the status bar's inset. */
    topInsets: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = if (onBack == null) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize()) {
        if (onBack == null) {
            LargeFlexibleTopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                scrollBehavior = scrollBehavior,
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, modifier = Modifier.semantics { heading() })
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                windowInsets = if (topInsets) TopAppBarDefaults.windowInsets else androidx.compose.foundation.layout.WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** An icon-only button with its label as content description and as a tooltip on long press. */
@Composable
fun TooltipIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}

/** Rows of a [SettingsGroup]; each gets the shapes of its place in the group. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (shapes: ListItemShapes) -> Unit) {
        rows += content
    }
}

/** A group of segmented rows, 2 dp apart, round outer and small inner corners; optionally titled. */
@Composable
fun SettingsGroup(title: String? = null, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    if (scope.rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
        ) {
            scope.rows.forEachIndexed { index, row -> row(groupShapes(index, scope.rows.size)) }
        }
    }
}

/**
 * Shapes of a row by its place in the group. A group of one row gets the group's outer corners
 * (taken from the library's first and last segments) rather than the single-item shape.
 */
@Composable
private fun groupShapes(index: Int, count: Int): ListItemShapes {
    val segmented = ListItemDefaults.segmentedShapes(index, count)
    if (count > 1) return segmented
    val first = ListItemDefaults.segmentedShapes(0, 2).shape as? CornerBasedShape ?: return segmented
    val last = ListItemDefaults.segmentedShapes(1, 2).shape as? CornerBasedShape ?: return segmented
    val outer = first.copy(bottomStart = last.bottomStart, bottomEnd = last.bottomEnd)
    return ListItemDefaults.shapes(
        shape = outer,
        selectedShape = outer,
        pressedShape = segmented.pressedShape,
        focusedShape = segmented.focusedShape,
        hoveredShape = segmented.hoveredShape,
        draggedShape = segmented.draggedShape,
    )
}

/** Segments on surfaceContainer, so a group stands out from the page as one block. */
@Composable
fun settingsRowColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** The tonal leading icon box of a row; [error] for a row reporting a problem. */
@Composable
fun RowIcon(@DrawableRes icon: Int, error: Boolean = false) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content)
    }
}

/** A row opening a page. */
@Composable
fun SettingsNavigationRow(
    shapes: ListItemShapes,
    @DrawableRes icon: Int,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** A value to read: a label above, the value under it. */
@Composable
fun SettingsInfoRow(shapes: ListItemShapes, label: String, value: String, @DrawableRes icon: Int? = null, onLongClick: (() -> Unit)? = null) {
    SegmentedListItem(
        onClick = {},
        onLongClick = onLongClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = icon?.let { { RowIcon(it) } },
        supportingContent = { Text(value) },
    ) {
        Text(label)
    }
}

/** Secondary text under a group. */
@Composable
fun SettingsNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp))
}

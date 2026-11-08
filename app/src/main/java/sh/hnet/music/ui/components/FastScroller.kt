package sh.hnet.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 48.dp
private val TOUCH_WIDTH = 48.dp
private val BUBBLE_SIZE = 64.dp

/** Fast scroller over a [LazyListState]; data items start at [firstItem] (after headers). */
@Composable
fun FastScroller(state: LazyListState, sections: List<String>, firstItem: Int, modifier: Modifier = Modifier) = FastScroller(
    itemCount = sections.size,
    sections = sections,
    firstVisible = { (state.firstVisibleItemIndex - firstItem).coerceAtLeast(0) },
    visibleCount = { state.layoutInfo.visibleItemsInfo.size },
    isScrolling = { state.isScrollInProgress },
    scrollTo = { state.scrollToItem(it + firstItem) },
    modifier = modifier,
)

@Composable
fun FastScroller(state: LazyGridState, sections: List<String>, firstItem: Int, modifier: Modifier = Modifier) = FastScroller(
    itemCount = sections.size,
    sections = sections,
    firstVisible = { (state.firstVisibleItemIndex - firstItem).coerceAtLeast(0) },
    visibleCount = { state.layoutInfo.visibleItemsInfo.size },
    isScrolling = { state.isScrollInProgress },
    scrollTo = { state.scrollToItem(it + firstItem) },
    modifier = modifier,
)

/**
 * The app's one custom control (SPEC §5.5): a thumb on the end edge, shown while the list scrolls
 * and 1.5 s after; dragging maps its position proportionally to the list (not to the content's
 * height) and shows the section of that place in a bubble. Hidden from TalkBack, which pages.
 */
@Composable
private fun FastScroller(
    itemCount: Int,
    sections: List<String>,
    firstVisible: () -> Int,
    visibleCount: () -> Int,
    isScrolling: () -> Boolean,
    scrollTo: suspend (Int) -> Unit,
    modifier: Modifier,
) {
    if (itemCount < 2) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = isScrolling()
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    // Read by the gesture when it happens, not captured when it started
    val count by rememberUpdatedState(itemCount)
    val scrollToState by rememberUpdatedState(scrollTo)

    BoxWithConstraints(modifier.fillMaxHeight().clearAndSetSemantics {}) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - THUMB_HEIGHT).toPx() }.coerceAtLeast(1f)
        val listFraction = (firstVisible().toFloat() / (itemCount - visibleCount()).coerceAtLeast(1)).coerceIn(0f, 1f)
        val fraction = if (dragging) dragFraction else listFraction
        val thumbY = (fraction * trackPx).roundToInt()
        val index = (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
        val trackPxNow by rememberUpdatedState(trackPx)
        val listFractionNow by rememberUpdatedState(listFraction)

        AnimatedVisibility(
            visible = shown || dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) },
        ) {
            Box(Modifier.size(TOUCH_WIDTH + BUBBLE_SIZE + 24.dp, THUMB_HEIGHT)) {
                if (dragging) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .size(BUBBLE_SIZE)
                            .offset(y = (THUMB_HEIGHT - BUBBLE_SIZE) / 2)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 6.dp, bottomStart = 32.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            sections.getOrElse(index) { "" },
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(TOUCH_WIDTH, THUMB_HEIGHT)
                        .pointerInput(Unit) {
                            var job: Job? = null
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragFraction = listFractionNow
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragFraction = (dragFraction + amount / trackPxNow).coerceIn(0f, 1f)
                                val target = (dragFraction * (count - 1)).roundToInt()
                                job?.cancel()
                                job = scope.launch { scrollToState(target) }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(8.dp, THUMB_HEIGHT)
                            .clip(CircleShape)
                            .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

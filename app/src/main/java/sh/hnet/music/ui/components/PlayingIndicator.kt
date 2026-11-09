package sh.hnet.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The now-playing mark: Material Symbols' graphic_eq drawn as five rounded bars, which rise and fall
 * out of step while [playing] (owner's decision) and stand in the symbol's own shape while paused –
 * gliding out of that shape when playing starts and back into it when it stops. Drawn, not an icon,
 * so the bars can move; same proportions as the symbol (24-unit grid, bars 2 wide with 2 between,
 * heights 4, 12, 20, 12, 4).
 */
@Composable
fun PlayingIndicator(playing: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val bars = remember { BARS.map { Animatable(it) } }
    bars.forEachIndexed { i, bar ->
        LaunchedEffect(bar, playing) {
            if (playing) {
                // Out of step: each bar waits its own moment, then goes up and down at its own pace
                kotlinx.coroutines.delay(OFFSETS[i].toLong())
                while (true) {
                    bar.animateTo(1f, tween(DURATIONS[i], easing = FastOutSlowInEasing))
                    bar.animateTo(LOW, tween(DURATIONS[i], easing = FastOutSlowInEasing))
                }
            } else {
                bar.animateTo(BARS[i], tween(SETTLE_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    Canvas(modifier.size(size)) {
        val unit = this.size.width / GRID
        val width = BAR_WIDTH * unit
        val center = this.size.height / 2
        bars.forEachIndexed { i, bar ->
            val height = bar.value * MAX_HEIGHT * unit
            drawRoundRect(
                color = color,
                topLeft = Offset((FIRST_X + i * STEP) * unit, center - height / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}

private const val GRID = 24f
private const val FIRST_X = 3f
private const val STEP = 4f
private const val BAR_WIDTH = 2f
private const val MAX_HEIGHT = 20f

/** The still shape (the symbol's), as shares of the tallest bar. */
private val BARS = listOf(0.2f, 0.6f, 1f, 0.6f, 0.2f)

/** The shortest a moving bar gets: still a rounded bar, not a dot. */
private const val LOW = 0.2f

/** Each bar's own rhythm, so they move out of step. */
private val DURATIONS = listOf(520, 680, 450, 610, 560)
private val OFFSETS = listOf(0, 260, 120, 380, 200)

/** How long the bars take to settle back into the still shape. */
private const val SETTLE_MS = 300

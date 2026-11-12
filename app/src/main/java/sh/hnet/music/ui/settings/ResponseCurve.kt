@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.log10
import sh.hnet.music.R
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.ui.player.decibels

/**
 * The equalizer's response curve (SPEC-equalizer §4): the combined response of the enabled filters
 * and the pre-amp, 20 Hz to 20 kHz on a logarithmic axis, ±12 dB widening to ±20 dB when the curve
 * or the edited filter's marker goes beyond. A picture only, drawn from the coefficients the audio
 * path uses; theme color roles, no fills.
 *
 * @param applied false while the equalizer is off: the curve keeps its shape, muted, under [tag]
 * @param edited the filter being edited: its own response dashed, its marker at (frequency, gain)
 */
@Composable
fun ResponseCurve(settings: EqSettings, applied: Boolean, edited: EqFilter?, tag: String?, modifier: Modifier = Modifier) {
    val frequencies = remember { Equalizer.curveFrequencies() }
    val total = remember(settings) { Equalizer.response(settings, frequencies) }
    val own = remember(edited) { edited?.let { Equalizer.response(it, frequencies) } }
    val farthest = maxOf(total.maxOf { abs(it) }, own?.maxOf { abs(it) } ?: 0f)
    val wide = farthest > NARROW_DB
    val range by animateFloatAsState(if (wide) WIDE_DB else NARROW_DB, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "curveRange")
    val gridDb = if (wide) WIDE_DB / 2 else NARROW_DB / 2

    val curveColor = if (applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val zeroColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val editedColor = MaterialTheme.colorScheme.tertiary
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tagStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    // Whole numbers with a real minus sign
    val plus = "+${gridDb.toInt()}"
    val minus = "\u2212${gridDb.toInt()}"
    val enabledCount = settings.filters.count { it.enabled }
    val description = pluralStringResource(R.plurals.desc_curve, enabledCount, enabledCount, decibels(total.max()))

    Surface(shape = RoundedCornerShape(20.dp), color = containerColor, modifier = modifier.fillMaxWidth().height(HEIGHT)) {
        Canvas(
            Modifier.fillMaxSize().padding(8.dp).semantics {
                role = Role.Image
                contentDescription = description
            },
        ) {
            val left = Y_LABELS.toPx()
            val bottom = size.height - X_LABELS.toPx()
            val width = size.width - left
            fun x(frequency: Double) = left + (log10(frequency / Equalizer.MIN_FREQUENCY) / DECADES).toFloat() * width
            fun y(db: Float) = bottom * (1 - (db + range) / (2 * range))
            val line = 1.dp.toPx()

            GRID_FREQUENCIES.forEach { f -> drawLine(gridColor, Offset(x(f), 0f), Offset(x(f), bottom), line) }
            listOf(gridDb, -gridDb).forEach { db -> drawLine(gridColor, Offset(left, y(db)), Offset(size.width, y(db)), line) }
            drawLine(zeroColor, Offset(left, y(0f)), Offset(size.width, y(0f)), line)

            // Labels: dB at the start, frequencies under the plot
            listOf(plus to gridDb, "0" to 0f, minus to -gridDb).forEach { (text, db) ->
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), y(db) - layout.size.height / 2f))
            }
            LABELED_FREQUENCIES.forEach { (f, text) ->
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(x(f) - layout.size.width / 2f, bottom + 2.dp.toPx()))
            }
            tag?.let {
                val layout = measurer.measure(it, tagStyle)
                drawText(layout, topLeft = Offset(size.width - layout.size.width, 0f))
            }

            clipRect(left = left, top = 0f, right = size.width, bottom = bottom) {
                own?.let { curve(it, frequencies, ::x, ::y, editedColor, 2.dp.toPx(), dashed = true) }
                curve(total, frequencies, ::x, ::y, curveColor, 2.5.dp.toPx(), dashed = false)
                edited?.let { filter ->
                    val center = Offset(x(filter.frequencyHz.toDouble()), y(filter.gainDb))
                    drawCircle(containerColor, radius = 10.dp.toPx(), center = center)
                    drawCircle(editedColor, radius = 7.dp.toPx(), center = center)
                }
            }
        }
    }
}

private fun DrawScope.curve(
    values: FloatArray,
    frequencies: DoubleArray,
    x: (Double) -> Float,
    y: (Float) -> Float,
    color: Color,
    width: Float,
    dashed: Boolean,
) {
    val path = Path()
    values.forEachIndexed { i, db -> if (i == 0) path.moveTo(x(frequencies[i]), y(db)) else path.lineTo(x(frequencies[i]), y(db)) }
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private val HEIGHT = 156.dp
private val Y_LABELS = 28.dp
private val X_LABELS = 16.dp
private const val NARROW_DB = 12f
private const val WIDE_DB = 20f

/** 20 Hz to 20 kHz. */
private const val DECADES = 3.0
private val GRID_FREQUENCIES = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0)
private val LABELED_FREQUENCIES = listOf(100.0 to "100", 1000.0 to "1k", 10_000.0 to "10k")

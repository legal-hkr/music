package sh.hnet.music.playback

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A parametric filter's kind (SPEC-equalizer §5): the names are stored, in settings and backups. */
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF }

/** One filter of the equalizer; a switched-off one stays in the list and out of the sound. */
@Serializable
data class EqFilter(
    val enabled: Boolean = true,
    val type: FilterType = FilterType.PEAK,
    val frequencyHz: Int = 1000,
    val gainDb: Float = 0f,
    val q: Float = 1f,
)

/** What the equalizer applies: a pre-amp and the filters, in the user's order (which doesn't change the sound). */
@Serializable
data class EqSettings(val preampDb: Float = 0f, val filters: List<EqFilter> = emptyList())

/**
 * The equalizer's arithmetic (SPEC-equalizer §4, §5): RBJ biquads, their combined response for the
 * curve, and the limits of every value. Pure Kotlin, unit-tested; the curve and the audio path use
 * the same coefficients.
 */
object Equalizer {
    const val MAX_FILTERS = 20
    const val MIN_FREQUENCY = 20
    const val MAX_FREQUENCY = 20_000
    const val MAX_GAIN = 20f
    const val MIN_Q = 0.1f
    const val MAX_Q = 10f

    /** The rate the curve is drawn for. */
    const val CURVE_RATE = 48_000

    /** What a new filter starts as: no audible change. */
    val NEW_FILTER = EqFilter()

    /** b0, b1, b2, a1, a2 of [filter] at [sampleRate], normalised by a0. */
    fun coefficients(filter: EqFilter, sampleRate: Int): DoubleArray {
        val a = 10.0.pow(filter.gainDb / 40.0)
        // Below Nyquist with some room, whatever the stream's rate
        val frequency = filter.frequencyHz.toDouble().coerceIn(1.0, sampleRate * 0.45)
        val w = 2 * PI * frequency / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2 * filter.q.coerceIn(MIN_Q, MAX_Q))
        val root = 2 * sqrt(a) * alpha
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (filter.type) {
            FilterType.PEAK -> {
                b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cosW + root); b1 = 2 * a * ((a - 1) - (a + 1) * cosW); b2 = a * ((a + 1) - (a - 1) * cosW - root)
                a0 = (a + 1) + (a - 1) * cosW + root; a1 = -2 * ((a - 1) + (a + 1) * cosW); a2 = (a + 1) + (a - 1) * cosW - root
            }
            FilterType.HIGH_SHELF -> {
                b0 = a * ((a + 1) + (a - 1) * cosW + root); b1 = -2 * a * ((a - 1) + (a + 1) * cosW); b2 = a * ((a + 1) + (a - 1) * cosW - root)
                a0 = (a + 1) - (a - 1) * cosW + root; a1 = 2 * ((a - 1) - (a + 1) * cosW); a2 = (a + 1) - (a - 1) * cosW - root
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** The gain in dB of a filter with [coefficients] at [frequency]. */
    fun gainAt(coefficients: DoubleArray, frequency: Double, sampleRate: Int): Double {
        val (b0, b1, b2, a1, a2) = coefficients
        val w = 2 * PI * frequency / sampleRate
        val c1 = cos(w)
        val c2 = cos(2 * w)
        val numerator = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
        val denominator = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * c1 + 2 * a2 * c2
        return 10 * log10((numerator / denominator).coerceAtLeast(1e-12))
    }

    /** [points] frequencies from 20 Hz to 20 kHz, evenly spaced on a logarithmic axis. */
    fun curveFrequencies(points: Int = 160): DoubleArray =
        DoubleArray(points) { MIN_FREQUENCY * (MAX_FREQUENCY.toDouble() / MIN_FREQUENCY).pow(it / (points - 1.0)) }

    /** The combined response in dB at [frequencies]: every enabled filter and, with [withPreamp], the pre-amp. */
    fun response(settings: EqSettings, frequencies: DoubleArray, withPreamp: Boolean = true, sampleRate: Int = CURVE_RATE): FloatArray {
        val all = settings.filters.filter { it.enabled }.map { coefficients(it, sampleRate) }
        val base = if (withPreamp) settings.preampDb.toDouble() else 0.0
        return FloatArray(frequencies.size) { i -> (base + all.sumOf { gainAt(it, frequencies[i], sampleRate) }).toFloat() }
    }

    /** One filter's own response at [frequencies], whether it is enabled or not (the edited filter on the curve). */
    fun response(filter: EqFilter, frequencies: DoubleArray, sampleRate: Int = CURVE_RATE): FloatArray {
        val c = coefficients(filter, sampleRate)
        return FloatArray(frequencies.size) { gainAt(c, frequencies[it], sampleRate).toFloat() }
    }

    /** The curve's highest point with the pre-amp, to 0.1 dB: above 0 loud passages can distort (§6). */
    fun peakDb(settings: EqSettings): Float = roundTo(response(settings, curveFrequencies(PEAK_POINTS)).max(), 1)

    /** The pre-amp that brings the curve's peak to 0.0 dB. */
    fun safePreamp(settings: EqSettings): Float = roundTo(settings.preampDb - peakDb(settings), 1).coerceIn(-MAX_GAIN, MAX_GAIN)

    /** A filter's frequency a 24th of an octave up or down, at least 1 Hz away. */
    fun stepFrequency(frequency: Int, up: Boolean): Int {
        val stepped = (frequency * 2.0.pow((if (up) 1 else -1) / 24.0)).roundToInt()
        val moved = if (stepped == frequency) frequency + (if (up) 1 else -1) else stepped
        return moved.coerceIn(MIN_FREQUENCY, MAX_FREQUENCY)
    }

    fun roundTo(value: Float, decimals: Int): Float {
        val scale = 10.0.pow(decimals)
        return (Math.round(value * scale) / scale).toFloat()
    }

    /** Fine enough for the narrowest filter's top (Q 10) not to fall between two points. */
    private const val PEAK_POINTS = 1200
}

/**
 * The equalizer in the audio path: every sample of every channel through the pre-amp and the enabled
 * filters (transposed direct form II, in double precision: low frequencies at high sample rates need
 * it). Used on the playback thread only; [set] swaps the settings between samples and keeps the
 * filters' memory when their number doesn't change, so moving a slider doesn't click.
 */
class EqProcessor {
    private var sampleRate = 0
    private var channels = 0
    private var applied: EqSettings? = null
    private var preamp = 1.0
    private var count = 0
    private var coefficients = DoubleArray(0)
    private var state = DoubleArray(0)

    /** Whether samples pass through unchanged. */
    val idle: Boolean get() = applied == null

    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate == this.sampleRate && channels == this.channels) return
        this.sampleRate = sampleRate
        this.channels = channels
        val settings = applied
        applied = null
        state = DoubleArray(0)
        set(settings)
    }

    /** [settings] null: off. The same object as before: nothing to do. */
    fun set(settings: EqSettings?) {
        if (settings === applied) return
        applied = settings
        if (settings == null || sampleRate <= 0 || channels <= 0) return
        val enabled = settings.filters.filter { it.enabled }
        preamp = Math.pow(10.0, settings.preampDb / 20.0)
        coefficients = DoubleArray(enabled.size * 5)
        enabled.forEachIndexed { i, filter -> Equalizer.coefficients(filter, sampleRate).copyInto(coefficients, i * 5) }
        if (enabled.size != count || state.size != enabled.size * channels * 2) state = DoubleArray(enabled.size * channels * 2)
        count = enabled.size
    }

    /** After a seek or a new stream: the filters start from silence. */
    fun reset() = state.fill(0.0)

    fun process(channel: Int, sample: Float): Float {
        var x = sample * preamp
        var c = 0
        var s = channel * 2
        val stride = channels * 2
        repeat(count) {
            val y = coefficients[c] * x + state[s]
            state[s] = coefficients[c + 1] * x - coefficients[c + 3] * y + state[s + 1]
            state[s + 1] = coefficients[c + 2] * x - coefficients[c + 4] * y
            x = y
            c += 5
            s += stride
        }
        return x.toFloat()
    }
}

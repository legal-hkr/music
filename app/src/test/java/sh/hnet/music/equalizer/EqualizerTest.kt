package sh.hnet.music.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqProcessor
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    private fun gain(filter: EqFilter, frequency: Double, rate: Int = 48_000) =
        Equalizer.gainAt(Equalizer.coefficients(filter, rate), frequency, rate)

    @Test
    fun `a peak filter has its gain at its frequency and none far from it`() {
        val filter = EqFilter(type = FilterType.PEAK, frequencyHz = 1492, gainDb = -7.7f, q = 2.39f)
        assertEquals(-7.7, gain(filter, 1492.0), 0.01)
        assertEquals(0.0, gain(filter, 50.0), 0.05)
        assertEquals(0.0, gain(filter, 18_000.0), 0.05)
    }

    @Test
    fun `shelves reach their gain on their side and nothing on the other`() {
        val low = EqFilter(type = FilterType.LOW_SHELF, frequencyHz = 105, gainDb = 6.3f, q = 0.7f)
        assertEquals(6.3, gain(low, 20.0), 0.15)
        assertEquals(0.0, gain(low, 5_000.0), 0.05)
        val high = EqFilter(type = FilterType.HIGH_SHELF, frequencyHz = 10_000, gainDb = -3.6f, q = 0.7f)
        assertEquals(-3.6, gain(high, 20_000.0, 96_000), 0.4)
        assertEquals(0.0, gain(high, 200.0), 0.05)
    }

    @Test
    fun `a filter without gain changes nothing`() {
        assertEquals(0.0, gain(Equalizer.NEW_FILTER, 1000.0), 1e-9)
        assertEquals(0.0, gain(Equalizer.NEW_FILTER, 37.0), 1e-9)
    }

    @Test
    fun `the iSine 10 preset peaks at 0 dB with its pre-amp`() {
        val settings = BuiltInPresets.AUDEZE_ISINE_10.settings
        assertEquals(0.0f, Equalizer.peakDb(settings), 0.11f)
        // Without the pre-amp the peak is what the pre-amp takes away
        assertEquals(6.3f, Equalizer.peakDb(settings.copy(preampDb = 0f)), 0.11f)
        assertEquals(-6.3f, Equalizer.safePreamp(settings.copy(preampDb = 0f)), 0.11f)
    }

    @Test
    fun `switched-off filters are left out of the response`() {
        val on = EqSettings(filters = listOf(EqFilter(gainDb = 6f)))
        val off = EqSettings(filters = listOf(EqFilter(gainDb = 6f, enabled = false)))
        assertEquals(6f, Equalizer.response(on, doubleArrayOf(1000.0))[0], 0.01f)
        assertEquals(0f, Equalizer.response(off, doubleArrayOf(1000.0))[0], 0.01f)
    }

    @Test
    fun `frequency steps are a 24th of an octave and at least 1 Hz`() {
        assertEquals(1029, Equalizer.stepFrequency(1000, up = true))
        assertEquals(972, Equalizer.stepFrequency(1000, up = false))
        assertEquals(21, Equalizer.stepFrequency(20, up = true))
        assertEquals(20, Equalizer.stepFrequency(20, up = false))
        assertEquals(20_000, Equalizer.stepFrequency(20_000, up = true))
    }

    /** The level in dB of a sine at [frequency] after [settings], measured once the filters have settled. */
    private fun measured(settings: EqSettings, frequency: Double, rate: Int = 48_000): Double {
        val processor = EqProcessor().apply { configure(rate, 2); set(settings) }
        var sum = 0.0
        var counted = 0
        for (i in 0 until rate) {
            val x = sin(2 * PI * frequency * i / rate).toFloat()
            val left = processor.process(0, x)
            val right = processor.process(1, x)
            assertEquals(left, right, 0f)
            if (i >= rate / 2) { sum += left * left; counted++ }
        }
        return 20 * log10(sqrt(sum / counted) * sqrt(2.0))
    }

    @Test
    fun `the audio path gives the response the curve shows`() {
        val settings = BuiltInPresets.AUDEZE_ISINE_10.settings
        for (frequency in doubleArrayOf(60.0, 320.0, 1000.0, 1492.0, 3000.0, 8000.0)) {
            val expected = Equalizer.response(settings, doubleArrayOf(frequency))[0]
            assertTrue("at $frequency Hz", abs(measured(settings, frequency) - expected) < 0.05)
        }
    }

    @Test
    fun `the processor passes samples through while off and keeps running when settings change`() {
        val processor = EqProcessor().apply { configure(44_100, 2) }
        assertTrue(processor.idle)
        processor.set(EqSettings(preampDb = -6f))
        assertFalse(processor.idle)
        assertEquals(0.5f, processor.process(0, 1f), 0.002f)
        processor.set(null)
        assertTrue(processor.idle)
    }

    @Test
    fun `state changes keep the values in range and tell modified from saved`() {
        var state = EqualizerState().withBuiltIn(BuiltInPresets.AUDEZE_ISINE_10)
        assertFalse(state.modified)
        assertEquals(null, state.applied)
        state = state.copy(enabled = true).withPreamp(-40f)
        assertEquals(-20f, state.current.preampDb, 0f)
        assertTrue(state.modified)
        assertEquals(state.current, state.applied)
        state = state.withPreamp(-6.3f)
        assertFalse(state.modified)
        val removed = state.withoutFilter(1)
        assertEquals(9, removed.current.filters.size)
        assertEquals(listOf(66, 105, 320, 962), state.sortedByFrequency().current.filters.take(4).map { it.frequencyHz })
        var full = EqualizerState()
        repeat(30) { full = full.withNewFilter() }
        assertEquals(Equalizer.MAX_FILTERS, full.current.filters.size)
    }
}

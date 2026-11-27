package sh.hnet.music.equalizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqProcessor
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType

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
    fun `the safe pre-amp brings a correction's peak to 0 dB`() {
        val raw = TestPresets.CORRECTION.copy(preampDb = 0f)
        val peak = Equalizer.peakDb(raw)
        assertTrue(peak > 0f)
        // The safe pre-amp is what the peak asks for, and with it the peak is gone
        assertEquals(-peak, Equalizer.safePreamp(raw), 0.11f)
        assertEquals(0f, Equalizer.peakDb(raw.copy(preampDb = Equalizer.safePreamp(raw))), 0.11f)
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
        val settings = TestPresets.CORRECTION
        for (frequency in doubleArrayOf(60.0, 320.0, 1000.0, 1492.0, 3000.0, 8000.0)) {
            val expected = Equalizer.response(settings, doubleArrayOf(frequency))[0]
            assertTrue("at $frequency Hz", abs(measured(settings, frequency) - expected) < 0.05)
        }
    }

    @Test
    fun `switching on and off glides between the sound before and after`() {
        val rate = 48_000
        val processor = EqProcessor().apply { configure(rate, 2) }
        assertTrue(processor.idle)
        assertEquals(1f, processor.process(0, 1f), 0f)
        // On: from the plain sample towards half of it (−6 dB), never jumping
        processor.set(EqSettings(preampDb = -6.02f))
        assertFalse(processor.idle)
        var previous = 1f
        var largestStep = 0f
        repeat(rate / 10) {
            val out = processor.process(0, 1f)
            processor.process(1, 1f)
            largestStep = maxOf(largestStep, abs(out - previous))
            previous = out
        }
        assertEquals(0.5f, previous, 0.002f)
        assertTrue("no jump on the way: $largestStep", largestStep < 0.001f)
        // Off: back the same way, and only then idle
        processor.set(null)
        assertFalse(processor.idle)
        assertEquals(0.5f, processor.process(0, 1f), 0.002f)
        repeat(rate / 10) { processor.process(0, 1f); processor.process(1, 1f) }
        assertTrue(processor.idle)
        assertEquals(1f, processor.process(0, 1f), 0f)
    }

    @Test
    fun `state changes keep the values in range and tell modified from saved`() {
        var state = EqualizerState().savedAsNew("Headphones", 1, TestPresets.CORRECTION)
        assertFalse(state.modified)
        assertEquals(null, state.applied)
        state = state.copy(enabled = true).withPreamp(-40f)
        assertEquals(-20f, state.current.preampDb, 0f)
        assertTrue(state.modified)
        assertEquals(state.current, state.applied)
        state = state.withPreamp(-6f)
        assertFalse(state.modified)
        val removed = state.withoutFilter(1)
        assertEquals(5, removed.current.filters.size)
        assertEquals(listOf(105, 320, 962, 1500), state.sortedByFrequency().current.filters.take(4).map { it.frequencyHz })
        var full = EqualizerState()
        repeat(30) { full = full.withNewFilter() }
        assertEquals(Equalizer.MAX_FILTERS, full.current.filters.size)
    }

    @Test
    fun `own presets are saved, renamed and deleted, and built-in ones can't be saved over`() {
        var state = EqualizerState().withBuiltIn(BuiltInPresets.FLAT).withPreamp(-8f)
        assertTrue(state.modified)
        // Save does nothing on a built-in preset
        assertEquals(state, state.saved(1))
        state = state.savedAsNew(" Mine ", 10)
        assertEquals("Mine", state.preset?.own)
        assertFalse(state.modified)
        state = state.withPreamp(-9f)
        assertTrue(state.modified)
        assertEquals(-8f, state.discarded().current.preampDb, 0f)
        state = state.saved(20)
        assertFalse(state.modified)
        assertEquals(-9f, state.presets.single().settings.preampDb, 0f)
        assertEquals(20L, state.presets.single().changedAt)

        state = state.renamed("Mine", "Evening", 30)
        assertEquals("Evening", state.preset?.own)
        assertFalse(state.modified)
        val lower = { name: String -> name.lowercase() }
        assertTrue(state.nameTaken("evening", listOf("Flat"), lower))
        assertTrue(state.nameTaken("FLAT", listOf("Flat"), lower))
        assertFalse(state.nameTaken("evening", listOf("Flat"), lower, except = "Evening"))

        val other = state.withBuiltIn(BuiltInPresets.FLAT)
        assertEquals(-9f, other.withOwn("Evening").current.preampDb, 0f)
        // Deleting the preset in use keeps its values, as unsaved settings
        val deleted = state.withoutPreset("Evening")
        assertEquals(null, deleted.preset)
        assertEquals(null, deleted.saved)
        assertFalse(deleted.modified)
        assertEquals(-9f, deleted.current.preampDb, 0f)
        // Deleting another one changes nothing in use
        assertEquals(PresetRef(builtIn = "flat"), other.withoutPreset("Evening").preset)
    }

    @Test
    fun `each device uses its own preset or the Other devices one, and the one playing now is heard`() {
        val buds = "bluetooth:Buds"
        val dac = "usb:DAC"
        val headphones = PresetRef(own = "Headphones")
        // Before a device is known the slot stands for it: what an earlier version's one preset becomes
        var state = EqualizerState(enabled = true).savedAsNew("Headphones", 1, TestPresets.CORRECTION)
        assertEquals(headphones, state.other)
        state = state.withDevice(buds)
        assertEquals(headphones, state.preset)
        // A choice is the device's playing now; the slot and other devices keep theirs
        state = state.withBuiltIn(BuiltInPresets.FLAT)
        assertEquals(PresetRef.FLAT, state.refFor(buds))
        assertEquals(headphones, state.refFor(dac))
        assertEquals(EqSettings(), state.applied)
        state = state.withDevice(dac)
        assertEquals(TestPresets.CORRECTION, state.applied)

        // Ticked in Applies to: moved from its previous preset; unticked: back on the slot's
        state = state.assigned(buds, headphones)
        assertEquals(setOf(buds), state.usedBy(headphones))
        assertEquals(emptySet<String>(), state.usedBy(PresetRef.FLAT))
        state = state.unassigned(buds).withOther(PresetRef.FLAT)
        assertEquals(PresetRef.FLAT, state.refFor(buds))
        assertEquals(PresetRef.FLAT, state.refFor(dac))
        // No such preset: nothing changes
        assertEquals(state, state.assigned(buds, PresetRef(own = "Gone")))
    }

    @Test
    fun `changes not saved yet stay with their preset for every device using it`() {
        val buds = "bluetooth:Buds"
        val dac = "usb:DAC"
        var state = EqualizerState().withDevice(buds).savedAsNew("Headphones", 1, TestPresets.CORRECTION).assigned(dac, PresetRef(own = "Headphones"))
        state = state.withPreamp(-9f)
        assertTrue(state.modified)
        // The other device hears them too, and they outlive this device's choice of another preset
        assertEquals(-9f, state.withDevice(dac).current.preampDb, 0f)
        val away = state.withBuiltIn(BuiltInPresets.FLAT)
        assertFalse(away.modified)
        assertEquals(-9f, away.withDevice(dac).current.preampDb, 0f)
        // With no device left on the preset they go, as they always did; Undo puts the view back
        val alone = away.withDevice(dac).withBuiltIn(BuiltInPresets.FLAT)
        assertTrue(alone.edits.isEmpty())
        val undone = alone.undone(state.withDevice(dac))
        assertEquals(-9f, undone.current.preampDb, 0f)
        assertTrue(undone.modified)
    }

    @Test
    fun `deleting a preset moves its devices to the Other devices preset and Undo moves them back`() {
        val buds = "bluetooth:Buds"
        val dac = "usb:DAC"
        val mine = PresetRef(own = "Mine")
        var state = EqualizerState().withDevice(buds).savedAsNew("Mine", 1, EqSettings(preampDb = -3f)).assigned(dac, mine).withOther(mine)
        val before = state
        state = state.withoutPreset("Mine")
        // The slot is on Flat, the other device follows it, and what was heard stays heard
        assertEquals(PresetRef.FLAT, state.other)
        assertEquals(PresetRef.FLAT, state.refFor(dac))
        assertEquals(null, state.preset)
        assertEquals(-3f, state.current.preampDb, 0f)
        val undone = state.restoredPreset(before.presets.single(), before)
        assertEquals(before, undone)
        // Unsaved settings go once another preset is chosen
        assertEquals(null, state.withBuiltIn(BuiltInPresets.FLAT).unsaved)
        // A rename is followed everywhere
        val renamed = before.withPreamp(-5f).renamed("Mine", "Evening", 2)
        assertEquals(PresetRef(own = "Evening"), renamed.other)
        assertEquals(PresetRef(own = "Evening"), renamed.refFor(dac))
        assertEquals(-5f, renamed.current.preampDb, 0f)
    }

    @Test
    fun `an earlier version's equalizer becomes the Other devices preset and Restore defaults clears every device`() {
        val old = LegacyEqualizerState(enabled = true, preset = PresetRef.FLAT, current = EqSettings(preampDb = -7f)).migrated()
        assertTrue(old.assigned.isEmpty())
        assertTrue(old.modified)
        assertEquals(-7f, old.withDevice("speaker").applied!!.preampDb, 0f)
        // Unsaved settings stay unsaved settings
        val unsaved = LegacyEqualizerState(preset = null, current = EqSettings(preampDb = -2f)).migrated()
        assertEquals(null, unsaved.preset)
        assertEquals(-2f, unsaved.current.preampDb, 0f)
        val reset = old.withDevice("speaker").withBuiltIn(BuiltInPresets.FLAT).savedAsNew("Mine", 1).restoredDefaults()
        assertFalse(reset.enabled)
        assertEquals(PresetRef.FLAT, reset.preset)
        assertEquals(1, reset.presets.size)
    }

    @Test
    fun `Undo leaves a device that follows the Other devices slot following it`() {
        // As after an update from a version with one preset: the slot holds it, no device has its own
        val before = EqualizerState(enabled = true).savedAsNew("Mine", 1, EqSettings(preampDb = -3f)).withDevice("speaker")
        assertTrue(before.assigned.isEmpty())
        // A change undone: the values are back and the device still has no preset of its own
        val undone = before.withPreamp(-9f).undone(before)
        assertEquals(before, undone)
        // Another preset chosen for the device, then undone
        val chosen = before.withBuiltIn(BuiltInPresets.FLAT)
        assertEquals(setOf("speaker"), chosen.assigned.keys)
        assertEquals(before, chosen.undone(before))
        // After a change of output there is nothing of this device's to put back
        assertEquals(chosen.withDevice("usb:DAC"), chosen.withDevice("usb:DAC").undone(before))

        // The slot's preset deleted while this device plays: unsaved settings; Undo puts everything back
        val deleted = before.withoutPreset("Mine")
        assertEquals(null, deleted.preset)
        assertEquals(PresetRef.FLAT, deleted.other)
        assertEquals(before, deleted.restoredPreset(before.presets.single(), before))
    }

    @Test
    fun `forgetting a device and Undo give back its preset with the changes not saved yet`() {
        val dac = "usb:DAC"
        val headphones = PresetRef(own = "Headphones")
        // The device's own preset with changes not saved yet
        val before = EqualizerState().withDevice(dac).savedAsNew("Headphones", 1, TestPresets.CORRECTION).withPreamp(-9f).withDevice("speaker")
        val forgotten = before.unassigned(dac)
        assertTrue(forgotten.edits.isEmpty())
        val back = forgotten.restoredDevice(dac, before)
        assertEquals(headphones, back.refFor(dac))
        assertEquals(-9f, back.valuesOf(headphones).preampDb, 0f)
        // Unsaved settings come back too
        val unsaved = EqualizerState().withDevice(dac).savedAsNew("Mine", 1, EqSettings(preampDb = -2f)).withoutPreset("Mine").withDevice("speaker")
        assertEquals(-2f, unsaved.unassigned(dac).restoredDevice(dac, unsaved).withDevice(dac).current.preampDb, 0f)
        // Something else chosen for it meanwhile: left alone
        val other = forgotten.assigned(dac, PresetRef.FLAT)
        assertEquals(other, other.restoredDevice(dac, before))
    }

    @Test
    fun `a built-in preset this version doesn't have gives way to Flat`() {
        val gone = PresetRef(builtIn = "audeze_isine_10")
        val mine = PresetRef(own = "Mine")
        val state = EqualizerState(enabled = true).savedAsNew("Mine", 1, EqSettings(preampDb = -3f))
            .copy(other = gone, assigned = mapOf("usb:DAC" to gone, "speaker" to mine), edits = mapOf(gone.id to EqSettings(preampDb = -9f)))
        val clean = state.withoutMissingBuiltIns()
        assertEquals(PresetRef.FLAT, clean.other)
        assertEquals(PresetRef.FLAT, clean.refFor("usb:DAC"))
        assertEquals(mine, clean.refFor("speaker"))
        assertTrue(clean.edits.isEmpty())
        assertEquals(EqSettings(), clean.withDevice("usb:DAC").current)
        // Nothing to do: the same state
        assertEquals(clean, clean.withoutMissingBuiltIns())
    }
}

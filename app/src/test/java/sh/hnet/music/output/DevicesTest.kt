package sh.hnet.music.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.playback.OutputKind

class DevicesTest {
    private val speaker = AudioOutput(OutputKind.SPEAKER, "")
    private val dac = AudioOutput(OutputKind.USB, "HiBy R4")
    private val buds = AudioOutput(OutputKind.BLUETOOTH, "Beoplay EX")

    @Test
    fun `a device is remembered when music first plays on it, once`() {
        val state = DevicesState().played(dac, 10).played(speaker, 20).played(dac, 30)
        assertEquals(listOf(Device(OutputKind.USB, "HiBy R4", 30), Device(OutputKind.SPEAKER, "", 20)), state.devices)
    }

    @Test
    fun `a new device starts with bit-perfect off`() {
        assertFalse(DevicesState().deviceFor(dac).bitPerfect)
        assertFalse(DevicesState().played(dac, 1).byKey(dac.key)!!.bitPerfect)
    }

    @Test
    fun `bit-perfect is each USB device's own and no other kind's`() {
        val other = AudioOutput(OutputKind.USB, "KA11")
        val state = DevicesState().played(other, 1).withBitPerfect(dac, true).withBitPerfect(buds, true)
        assertEquals(setOf(dac.key), state.bitPerfectKeys)
        assertNull(state.byKey(buds.key))
        assertEquals(emptySet<String>(), state.withBitPerfect(dac, false).bitPerfectKeys)
        // Playing keeps the setting
        assertTrue(state.played(dac, 5).byKey(dac.key)!!.bitPerfect)
    }

    @Test
    fun `forgetting removes a device with its settings and Undo brings it back as it was`() {
        val state = DevicesState().played(dac, 1).withBitPerfect(dac, true).played(speaker, 2)
        val device = state.byKey(dac.key)!!
        val without = state.without(dac.key)
        assertNull(without.byKey(dac.key))
        assertFalse(without.deviceFor(dac).bitPerfect)
        assertEquals(device, without.with(device).byKey(dac.key))
        // Connected and played on again meanwhile: the new one stays
        val again = without.played(dac, 9)
        assertEquals(again, again.with(device))
    }

    @Test
    fun `connected devices come first, then the most recently played`() {
        val state = DevicesState().played(speaker, 1).played(dac, 2).played(buds, 3)
        assertEquals(listOf(speaker.key, buds.key, dac.key), state.ordered(setOf(speaker.key)).map { it.key })
        assertEquals(listOf(buds.key, dac.key, speaker.key), state.ordered(emptySet()).map { it.key })
    }
}

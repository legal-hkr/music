package sh.hnet.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.playback.BitPerfectController.Stream
import sh.hnet.music.playback.SoundPath.Dsd
import sh.hnet.music.playback.SoundPath.Equalizer
import sh.hnet.music.playback.SoundPath.Gain
import sh.hnet.music.playback.SoundPath.Route
import sh.hnet.music.playback.SoundPath.Volume
import sh.hnet.music.playback.SoundPath.Why

class SoundPathTest {
    private val pcm = SoundPath.Input(
        dsdRate = null, sampleRate = 96_000, bits = 24, channels = 2, usbSwitchOn = false, stream = null,
        gainMode = ReplayGainMode.ALBUM, gain = AppliedGain(-3.4f, album = true), hasGainTags = true,
        equalizerOn = true, equalizerChanges = true, dsdPcmRate = 88_200, dsdBoost = true,
    )
    private val dsd64 = 2_822_400

    @Test
    fun `bit-perfect skips the app's stages and names what the device gets`() {
        val path = SoundPath.of(pcm.copy(usbSwitchOn = true, stream = Stream(true, 24, 96_000)))
        assertEquals(Route.BitPerfect(24, 96_000), path.route)
        assertEquals(Gain.Skipped, path.gain)
        assertEquals(Equalizer.SKIPPED, path.equalizer)
        assertEquals(Volume.DEVICE, path.volume)
        assertNull(path.why)
        assertFalse(path.changes)
    }

    @Test
    fun `through the mixer every stage applies and the device page's switch says why`() {
        val path = SoundPath.of(pcm.copy(sampleRate = 88_200, usbSwitchOn = true, stream = Stream(false, 24, 88_200, Refusal.FORMAT)))
        assertEquals(Route.Mixer(88_200), path.route)
        assertEquals(Gain.Tagged(-3.4f, album = true), path.gain)
        assertEquals(Equalizer.ON, path.equalizer)
        assertEquals(Volume.SYSTEM, path.volume)
        assertEquals(Why.Format(24, 88_200), path.why)
        assertTrue(path.changes)
    }

    @Test
    fun `Music's own limits are told apart from the device's`() {
        val depth = SoundPath.of(pcm.copy(bits = 32, usbSwitchOn = true, stream = Stream(false, 0, 96_000, Refusal.DEPTH)))
        assertEquals(Why.Depth, depth.why)
        val channels = SoundPath.of(pcm.copy(channels = 6, usbSwitchOn = true, stream = Stream(false, 24, 96_000, Refusal.CHANNELS)))
        assertEquals(Why.Channels, channels.why)
        // A decoder's float output, where the file gives no depth
        val decoded = SoundPath.of(pcm.copy(bits = null, usbSwitchOn = true, stream = Stream(false, 0, 96_000, Refusal.DEPTH)))
        assertEquals(Why.Depth, decoded.why)
    }

    @Test
    fun `no why without the switch, or for a decision about another stream`() {
        assertNull(SoundPath.of(pcm.copy(stream = Stream(false, 24, 96_000, Refusal.FORMAT))).why)
        // The controller's word is about the previous track (44.1 kHz) a moment after a change
        val stale = SoundPath.of(pcm.copy(usbSwitchOn = true, stream = Stream(true, 16, 44_100)))
        assertEquals(Route.Mixer(96_000), stale.route)
        assertNull(stale.why)
    }

    @Test
    fun `DSD goes as DoP when bit-perfect, else converted to PCM with the reason`() {
        val dop = SoundPath.of(pcm.copy(dsdRate = dsd64, sampleRate = dsd64, bits = null, usbSwitchOn = true, stream = Stream(true, 24, 176_400)))
        assertEquals(Route.Dop(dsd64, 176_400, 24), dop.route)
        assertEquals(Dsd.AsDop, dop.dsd)
        assertFalse(dop.dsdChanges)

        val converted = SoundPath.of(pcm.copy(dsdRate = dsd64, sampleRate = dsd64, bits = null, usbSwitchOn = true, stream = Stream(false, 0, 88_200, Refusal.DEPTH)))
        assertEquals(Route.Mixer(88_200), converted.route)
        assertEquals(Dsd.ToPcm(88_200, boost = true), converted.dsd)
        // DoP's reason, not the PCM's depth
        assertEquals(Why.NoDop(176_400), converted.why)
        assertTrue(converted.dsdChanges)

        val bluetooth = SoundPath.of(pcm.copy(dsdRate = dsd64, sampleRate = dsd64, bits = null, dsdBoost = false))
        assertEquals(Dsd.ToPcm(88_200, boost = false), bluetooth.dsd)
        assertNull(bluetooth.why)
    }

    @Test
    fun `multichannel DSD is told by its channels, not by the device`() {
        val path = SoundPath.of(pcm.copy(dsdRate = dsd64, sampleRate = dsd64, bits = null, channels = 6, usbSwitchOn = true, stream = Stream(false, 0, 88_200, Refusal.CHANNELS)))
        assertEquals(Why.Channels, path.why)
    }

    @Test
    fun `a file Music can't carry exactly is never shown bit-perfect, whatever the controller last said`() {
        // A 32-bit file right after a 24/96 one that went out bit-perfect
        val wide = SoundPath.of(pcm.copy(bits = 32, usbSwitchOn = true, stream = Stream(true, 24, 96_000)))
        assertEquals(Route.Mixer(96_000), wide.route)
        assertEquals(Why.Depth, wide.why)
        assertEquals(Gain.Tagged(-3.4f, album = true), wide.gain)
        val surround = SoundPath.of(pcm.copy(channels = 6, usbSwitchOn = true, stream = Stream(true, 24, 96_000)))
        assertEquals(Route.Mixer(96_000), surround.route)
        assertEquals(Why.Channels, surround.why)
    }

    @Test
    fun `a refusal about another stream says nothing`() {
        // The controller already refused the next track (16 bit) while this 24-bit one plays through the mixer
        assertNull(SoundPath.of(pcm.copy(usbSwitchOn = true, stream = Stream(false, 16, 96_000, Refusal.FORMAT))).why)
        // A lossy file has no depth of its own: the decoder's counts
        val lossy = SoundPath.of(pcm.copy(bits = null, usbSwitchOn = true, stream = Stream(false, 16, 96_000, Refusal.FORMAT)))
        assertEquals(Why.Format(16, 96_000), lossy.why)
    }

    @Test
    fun `ReplayGain off, untagged with and without the pre-amp`() {
        assertEquals(Gain.Off, SoundPath.of(pcm.copy(gainMode = ReplayGainMode.OFF, gain = null)).gain)
        assertEquals(Gain.NoTags, SoundPath.of(pcm.copy(gain = null, hasGainTags = false)).gain)
        val preamp = SoundPath.of(pcm.copy(gain = AppliedGain(-6f, album = true), hasGainTags = false))
        assertEquals(Gain.PreAmp(-6f), preamp.gain)
        assertTrue(preamp.gainChanges)
        // Off stays off under bit-perfect too: it says what the setting is
        assertEquals(Gain.Off, SoundPath.of(pcm.copy(gainMode = ReplayGainMode.OFF, gain = null, usbSwitchOn = true, stream = Stream(true, 24, 96_000))).gain)
    }

    @Test
    fun `a flat equalizer is on but changes nothing`() {
        val flat = SoundPath.of(pcm.copy(equalizerChanges = false))
        assertEquals(Equalizer.ON, flat.equalizer)
        assertFalse(flat.equalizerChanges)
        assertEquals(Equalizer.OFF, SoundPath.of(pcm.copy(equalizerOn = false)).equalizer)
        assertFalse(SoundPath.changes(EqSettings()))
        assertFalse(SoundPath.changes(EqSettings(filters = listOf(EqFilter(gainDb = 0f)))))
        assertFalse(SoundPath.changes(EqSettings(filters = listOf(EqFilter(enabled = false, gainDb = 3f)))))
        assertTrue(SoundPath.changes(EqSettings(preampDb = -2f)))
        assertTrue(SoundPath.changes(EqSettings(filters = listOf(EqFilter(gainDb = 3f)))))
    }

    @Test
    fun `refusals follow the same checks as the choice`() {
        assertEquals(Refusal.CHANNELS, BitPerfect.refusal(24, 6))
        assertEquals(Refusal.DEPTH, BitPerfect.refusal(32, 2))
        assertEquals(Refusal.DEPTH, BitPerfect.refusal(0, 2))
        assertEquals(Refusal.FORMAT, BitPerfect.refusal(24, 2))
    }
}

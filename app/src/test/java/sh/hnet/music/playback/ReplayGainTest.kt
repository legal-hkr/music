package sh.hnet.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReplayGainTest {
    private val tagged = GainTags(trackGainDb = -8f, trackPeak = 0.9f, albumGainDb = -6f, albumPeak = 0.95f)
    private val none = GainTags(null, null, null, null)

    @Test
    fun offAppliesNothing() {
        assertNull(ReplayGain.compute(tagged, GainSettings(ReplayGainMode.OFF), albumInOrder = true))
    }

    @Test
    fun modesPickTheirGainAndFallBack() {
        assertEquals(AppliedGain(-8f, false), ReplayGain.compute(tagged, GainSettings(ReplayGainMode.TRACK), false))
        assertEquals(AppliedGain(-6f, true), ReplayGain.compute(tagged, GainSettings(ReplayGainMode.ALBUM), false))
        assertEquals(AppliedGain(-6f, true), ReplayGain.compute(tagged, GainSettings(ReplayGainMode.AUTO), albumInOrder = true))
        assertEquals(AppliedGain(-8f, false), ReplayGain.compute(tagged, GainSettings(ReplayGainMode.AUTO), albumInOrder = false))
        val trackOnly = tagged.copy(albumGainDb = null, albumPeak = null)
        assertEquals(AppliedGain(-8f, false), ReplayGain.compute(trackOnly, GainSettings(ReplayGainMode.ALBUM), false))
    }

    @Test
    fun untaggedFilesUseThePreamp() {
        assertEquals(AppliedGain(-6f, false), ReplayGain.compute(none, GainSettings(ReplayGainMode.TRACK, preampDb = -6f), false))
        assertNull(ReplayGain.compute(none, GainSettings(ReplayGainMode.TRACK, preampDb = 0f), false))
    }

    @Test
    fun clippingPreventionKeepsThePeakAtFullScale() {
        // A peak above full scale (1.2) needs about -1.58 dB; the tag's -1 dB would clip
        val loudPeak = GainTags(trackGainDb = -1f, trackPeak = 1.2f, albumGainDb = null, albumPeak = null)
        val applied = ReplayGain.compute(loudPeak, GainSettings(ReplayGainMode.TRACK), false)!!
        assertTrue(abs(applied.db - (-20f * kotlin.math.log10(1.2f))) < 0.01f)
        assertTrue(1.2f * applied.linear <= 1.0001f)
        val withoutPrevention = ReplayGain.compute(loudPeak, GainSettings(ReplayGainMode.TRACK, preventClipping = false), false)!!
        assertEquals(-1f, withoutPrevention.db)
    }

    @Test
    fun positiveGainIsNotAmplified() {
        val quiet = GainTags(trackGainDb = 5f, trackPeak = 0.3f, albumGainDb = null, albumPeak = null)
        assertEquals(0f, ReplayGain.compute(quiet, GainSettings(ReplayGainMode.TRACK), false)!!.db)
    }

    @Test
    fun albumInOrderNeedsANeighbourFromTheAlbumAndNoShuffle() {
        val queue = listOf("A", "A", "B", null)
        assertTrue(albumInOrder(false, queue::getOrNull, 0, queue.size))
        assertTrue(albumInOrder(false, queue::getOrNull, 1, queue.size))
        assertEquals(false, albumInOrder(false, queue::getOrNull, 2, queue.size))
        assertEquals(false, albumInOrder(false, queue::getOrNull, 3, queue.size))
        assertEquals(false, albumInOrder(true, queue::getOrNull, 0, queue.size))
    }
}

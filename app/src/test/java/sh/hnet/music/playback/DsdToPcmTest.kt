package sh.hnet.music.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DsdToPcmTest {
    private val dsd64 = 2_822_400

    /**
     * A sine as DSD: a second-order sigma-delta modulator, the highest bit of each byte first. Its
     * output follows its input one bit late, so it is fed one bit ahead.
     */
    private fun modulate(hz: Double, amplitude: Double, bytes: Int, rate: Int = dsd64): ByteArray {
        var s1 = 0.0
        var s2 = 0.0
        return ByteArray(bytes) { i ->
            var byte = 0
            for (bit in 0 until 8) {
                val x = amplitude * sin(2 * PI * hz * (i * 8 + bit + 1) / rate)
                val y = if (s2 >= 0) 1.0 else -1.0
                s1 += x - y
                s2 += s1 - y
                byte = (byte shl 1) or (if (y > 0) 1 else 0)
            }
            byte.toByte()
        }
    }

    private fun buffer(converter: DsdToPcm, frames: Int) =
        ByteBuffer.allocate(converter.maxOutputBytes(frames) + converter.maxOutputBytes(0)).order(ByteOrder.LITTLE_ENDIAN)

    private fun floats(buffer: ByteBuffer): FloatArray {
        buffer.flip()
        return FloatArray(buffer.remaining() / 4) { buffer.getFloat() }
    }

    /** Mono, in one go, with the tail. */
    private fun convert(dsd: ByteArray, pcmRate: Int, gainDb: Float = 0f, rate: Int = dsd64): FloatArray {
        val converter = DsdToPcm(1, rate, pcmRate, gainDb)
        val out = buffer(converter, dsd.size)
        converter.interleaved(dsd, 0, dsd.size, false, out)
        converter.finish(out)
        return floats(out)
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** RMS of what's left of [pcm] after taking out the sine it should be, away from the edges. */
    private fun residual(pcm: FloatArray, hz: Double, amplitude: Double, pcmRate: Int, decimation: Int): Double {
        var sum = 0.0
        val range = 200 until pcm.size - 200
        for (i in range) {
            // Sample i is centered on the start of input byte i × decimation: between two bits
            val t = (i * decimation * 8 - 0.5) / dsd64
            val d = pcm[i] - amplitude * sin(2 * PI * hz * t)
            sum += d * d
        }
        return sqrt(sum / range.count())
    }

    @Test
    fun sineComesOutAtItsLevelAndInItsPlace() {
        val dsd = modulate(1000.0, 0.5, dsd64 / 8 / 10) // 0.1 s
        for (pcmRate in listOf(88_200, 176_400, 352_800)) {
            val pcm = convert(dsd, pcmRate)
            val decimation = dsd64 / 8 / pcmRate
            assertEquals(dsd.size / decimation, pcm.size)
            // No delay, no gain error: what differs from the ideal sine is the modulator's noise
            assertTrue("$pcmRate", db(residual(pcm, 1000.0, 0.5, pcmRate, decimation) / 0.5) < -55)
        }
    }

    @Test
    fun gainIsApplied() {
        val dsd = modulate(1000.0, 0.25, 20_000)
        val pcm = convert(dsd, 88_200, gainDb = 6.0206f)
        assertTrue(db(residual(pcm, 1000.0, 0.5, 88_200, 4) / 0.5) < -55)
    }

    @Test
    fun topOfTheAudioBandIsKept() {
        val pcm = convert(modulate(20_000.0, 0.5, 40_000), 88_200)
        assertTrue(db(residual(pcm, 20_000.0, 0.5, 88_200, 4) / 0.5) < -50)
    }

    @Test
    fun ultrasonicToneIsRemoved() {
        // 100 kHz would fold down to 11.8 kHz at 88.2 kHz if the filter let it through
        val pcm = convert(modulate(100_000.0, 0.5, 40_000), 88_200)
        val rms = sqrt(pcm.drop(200).dropLast(200).sumOf { it * it.toDouble() } / (pcm.size - 400))
        assertTrue(db(rms / 0.5) < -60)
    }

    @Test
    fun silenceStaysSilent() {
        val pcm = convert(ByteArray(10_000) { 0x69 }, 88_200, gainDb = 6f)
        assertTrue(pcm.all { abs(it) < 1e-4f })
    }

    @Test
    fun everyByteGivesItsSampleWhateverTheChunks() {
        val dsd = modulate(440.0, 0.4, 10_001) // not a multiple of the decimation
        val whole = convert(dsd, 88_200)
        assertEquals((dsd.size + 3) / 4, whole.size)

        val converter = DsdToPcm(1, dsd64, 88_200, 0f)
        val out = buffer(converter, dsd.size)
        var at = 0
        for (size in generateSequence(1) { it * 3 % 997 + 1 }) {
            val count = minOf(size, dsd.size - at)
            converter.interleaved(dsd, at, count, false, out)
            at += count
            if (at == dsd.size) break
        }
        converter.finish(out)
        assertArrayEquals(whole, floats(out), 0f)
    }

    @Test
    fun bothLayoutsAndBitOrdersAgree() {
        val left = modulate(1000.0, 0.5, 4096)
        val right = modulate(3000.0, 0.3, 4096)
        val interleaved = ByteArray(8192) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        val a = DsdToPcm(2, dsd64, 176_400, 0f)
        val outA = buffer(a, 4096)
        a.interleaved(interleaved, 0, 4096, false, outA)
        a.finish(outA)

        // DSF: a block per channel, the lowest bit first
        val reversed = ByteArray(8192) { Integer.reverse((if (it < 4096) left[it] else right[it - 4096]).toInt()).ushr(24).toByte() }
        val b = DsdToPcm(2, dsd64, 176_400, 0f)
        val outB = buffer(b, 4096)
        b.planar(reversed, 4096, 4096, true, outB)
        b.finish(outB)

        val pcm = floats(outA)
        assertEquals(4096 / 2 * 2, pcm.size)
        assertArrayEquals(pcm, floats(outB), 0f)
        // Channels stay apart: left is the 1 kHz sine
        val leftOnly = FloatArray(pcm.size / 2) { pcm[it * 2] }
        assertArrayEquals(convert(left, 176_400), leftOnly, 0f)
    }

    @Test
    fun resetStartsAStreamAgain() {
        val dsd = modulate(1000.0, 0.5, 8192)
        val converter = DsdToPcm(1, dsd64, 88_200, 0f)
        val first = buffer(converter, dsd.size)
        converter.interleaved(dsd, 0, 5000, false, first)
        converter.reset()
        val again = buffer(converter, dsd.size)
        converter.interleaved(dsd, 0, dsd.size, false, again)
        converter.finish(again)
        assertArrayEquals(convert(dsd, 88_200), floats(again), 0f)
    }

    @Test
    fun filterIsFlatInTheAudioBandAndDownAboveIt() {
        for ((rate, pcmRate) in listOf(dsd64 to 88_200, dsd64 to 176_400, dsd64 * 4 to 88_200)) {
            val taps = DsdToPcm.lowPass(rate, pcmRate)
            assertEquals(0, taps.size % 16)
            fun response(hz: Double): Double {
                var re = 0.0
                var im = 0.0
                for (n in taps.indices) {
                    re += taps[n] * kotlin.math.cos(2 * PI * hz * n / rate)
                    im += taps[n] * sin(2 * PI * hz * n / rate)
                }
                return db(sqrt(re * re + im * im))
            }
            for (hz in listOf(20.0, 1000.0, 10_000.0, 20_000.0)) assertTrue("$hz", abs(response(hz)) < 0.01)
            val stop = minOf(pcmRate / 2.0, 60_000.0)
            for (hz in listOf(stop, stop * 1.5, 100_000.0, 352_800.0, 1_000_000.0)) assertTrue("$hz", response(hz) < -95)
        }
    }

    @Test
    fun rateFollowsTheStreamsFamily() {
        assertEquals(88_200, DsdToPcm.pcmRateFor(dsd64, 88_200))
        assertEquals(176_400, DsdToPcm.pcmRateFor(dsd64 * 2, 176_400))
        assertEquals(96_000, DsdToPcm.pcmRateFor(3_072_000, 88_200))
        assertEquals(192_000, DsdToPcm.pcmRateFor(3_072_000 * 4, 176_400))
        assertEquals(352_800, DsdToPcm.pcmRateFor(dsd64, 705_600))
    }
}

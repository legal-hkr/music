package sh.hnet.music.playback

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns DSD (1 bit per sample at 2.8 MHz and up) into float PCM: one linear-phase low-pass FIR
 * filter, run only at the output's rate. The filter keeps the audio band and removes the noise the
 * 1-bit format carries above it, which is what makes the lower rate possible at all.
 *
 * The filter works on whole bytes: for each byte position in its window a table holds the sum of
 * the eight taps for every byte value, so a sample costs one lookup per byte, not one per bit.
 *
 * The output isn't delayed: sample i is centred on the start of input byte i × [decimation], which
 * needs half a window of input beyond it – fed by the next call, or by [finish] at the end. Before
 * the start and after the end the input counts as DSD silence.
 *
 * Not thread-safe; one instance per stream. The output buffer must be little-endian (PCM's order).
 *
 * @param dsdRate bits per second of one channel
 * @param pcmRate the output's rate; [dsdRate] / 8 must be a multiple of it (see [pcmRateFor])
 * @param gainDb DSD's full level is 6 dB below PCM's (SACD's 0 dB is a 50 % modulation), so +6 dB
 *   plays it as loud as the same music in PCM; peaks above that level then exceed full scale
 */
class DsdToPcm(private val channels: Int, dsdRate: Int, val pcmRate: Int, gainDb: Float = 6f) {
    /** Input bytes of one channel per output sample. */
    val decimation: Int = dsdRate / 8 / pcmRate

    /** The window in bytes; even. */
    private val window: Int
    private val table: FloatArray

    // Each channel's last [window] bytes, written twice (at i and i + window) so the window is
    // always one unbroken run starting at [oldest]
    private val history: Array<ByteArray>
    private var oldest = 0
    private var fed = 0L

    init {
        require(channels > 0 && decimation >= 1 && decimation * 8L * pcmRate == dsdRate.toLong()) { "Rates don't divide" }
        val taps = lowPass(dsdRate, pcmRate)
        window = taps.size / 8
        val gain = 10.0.pow(gainDb / 20.0)
        table = FloatArray(window * 256)
        for (k in 0 until window) for (value in 0 until 256) {
            var sum = 0.0
            // The highest bit is the earliest in time
            for (bit in 0 until 8) sum += if (value shr (7 - bit) and 1 == 1) taps[k * 8 + bit] else -taps[k * 8 + bit]
            table[k * 256 + value] = (sum * gain).toFloat()
        }
        history = Array(channels) { ByteArray(window * 2) }
        reset()
    }

    /** Back to silence, for a seek: the next input is taken as the start of a stream. */
    fun reset() {
        history.forEach { it.fill(SILENCE) }
        oldest = 0
        fed = 0
    }

    /** The most bytes [frames] bytes per channel of input (or [finish]) can add to the output. */
    fun maxOutputBytes(frames: Int): Int = (maxOf(frames, window) / decimation + 1) * channels * 4

    /**
     * DSF's layout: [block] holds [bytesPerChannel] bytes of each channel one after another, of
     * which the first [frames] of each are audio.
     */
    fun planar(block: ByteArray, bytesPerChannel: Int, frames: Int, lsbFirst: Boolean, out: ByteBuffer) {
        for (i in 0 until frames) {
            for (c in 0 until channels) put(c, block[c * bytesPerChannel + i], lsbFirst)
            advance(out)
        }
    }

    /** DFF's layout: one byte of each channel in turn, [frames] times, from [offset]. */
    fun interleaved(data: ByteArray, offset: Int, frames: Int, lsbFirst: Boolean, out: ByteBuffer) {
        var at = offset
        for (i in 0 until frames) {
            for (c in 0 until channels) put(c, data[at++], lsbFirst)
            advance(out)
        }
    }

    /** The end of the stream: the samples still waiting for input get silence. */
    fun finish(out: ByteBuffer) {
        repeat(window / 2 - 1) {
            for (c in 0 until channels) put(c, SILENCE, false)
            advance(out)
        }
    }

    private fun put(channel: Int, value: Byte, lsbFirst: Boolean) {
        val byte = if (lsbFirst) REVERSED[value.toInt() and 0xFF] else value
        val h = history[channel]
        h[oldest] = byte
        h[oldest + window] = byte
    }

    /** One byte of every channel is in: moves on, and writes a sample when one is due. */
    private fun advance(out: ByteBuffer) {
        if (++oldest == window) oldest = 0
        // Sample i is due once the half window after its centre is in: up to byte i × decimation + window / 2 - 1
        val centre = fed++ - (window / 2 - 1)
        if (centre < 0 || centre % decimation != 0L) return
        for (c in 0 until channels) {
            val h = history[c]
            var sum = 0.0
            var t = 0
            for (k in oldest until oldest + window) {
                sum += table[t + (h[k].toInt() and 0xFF)]
                t += 256
            }
            out.putFloat(sum.toFloat())
        }
    }

    companion object {
        /** DSD's idle pattern: as many ones as zeros in every byte, so it filters to nothing. */
        private const val SILENCE: Byte = 0x69

        private val REVERSED = ByteArray(256) { Integer.reverse(it).ushr(24).toByte() }

        private const val STOPBAND_DB = 100.0

        /**
         * The PCM rate to convert a [dsdRate] stream to: [preferred] (88 200 or 176 400) moved to
         * the stream's family – 48 kHz based DSD gives 96 000 or 192 000 – and never above what
         * one input byte per sample gives.
         */
        fun pcmRateFor(dsdRate: Int, preferred: Int): Int {
            val base = if (dsdRate % 44_100 == 0) 44_100 else 48_000
            var rate = base * (preferred / 44_100).coerceAtLeast(1)
            while (rate > 1 && (rate > dsdRate / 8 || dsdRate / 8 % rate != 0)) rate /= 2
            return rate
        }

        /**
         * The low-pass filter's taps at the DSD rate, a multiple of 16 of them, summing to 1.
         * Flat up to half the stopband's start, which is the output's Nyquist frequency but at
         * most 60 kHz: 22 to 44 kHz for 88.2 kHz output, 30 to 60 kHz for higher rates (DSD64's
         * noise is already well above the music there). Kaiser window, 100 dB down.
         */
        internal fun lowPass(dsdRate: Int, pcmRate: Int): DoubleArray {
            val stop = minOf(pcmRate / 2.0, 60_000.0)
            val pass = stop / 2
            val width = 2 * PI * (stop - pass) / dsdRate
            val count = (ceil((STOPBAND_DB - 7.95) / (2.285 * width) / 16).toInt() + 1) * 16
            val beta = 0.1102 * (STOPBAND_DB - 8.7)
            val cutoff = (pass + stop) / 2 / dsdRate
            val middle = (count - 1) / 2.0
            val taps = DoubleArray(count) { n ->
                val x = n - middle
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val r = x / middle
                sinc * bessel0(beta * sqrt((1 - r * r).coerceAtLeast(0.0))) / bessel0(beta)
            }
            val sum = taps.sum()
            for (n in taps.indices) taps[n] /= sum
            return taps
        }

        /** The modified Bessel function of the first kind, order 0 (for the Kaiser window). */
        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > sum * 1e-17) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                k++
            }
            return sum
        }
    }
}

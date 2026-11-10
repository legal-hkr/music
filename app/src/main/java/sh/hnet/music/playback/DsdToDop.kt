package sh.hnet.music.playback

import java.nio.ByteBuffer

/**
 * DSD as DoP (DSD over PCM, bit-perfect stage B2): the 1-bit audio untouched, sixteen bits of each
 * channel at a time in the lower two bytes of a 24-bit sample, under a marker byte that alternates
 * 0x05 / 0xFA from frame to frame. A DAC that knows DoP sees the markers and plays the bits as DSD;
 * it only works when every bit arrives as sent, so only over bit-perfect output. The PCM rate is the
 * DSD rate / 16 (DSD64: 176.4 kHz).
 *
 * Not thread-safe; one instance per stream. Output: 24-bit little-endian samples, interleaved.
 */
class DsdToDop(private val channels: Int) {
    private var marker = MARKER_A
    /** The first byte of a pair, waiting for the second (groups may hold an odd number of bytes). */
    private val held = ByteArray(channels)
    private var holding = false

    fun reset() {
        marker = MARKER_A
        holding = false
    }

    /** The most bytes [frames] input bytes per channel can become. */
    fun maxOutputBytes(frames: Int): Int = (frames / 2 + 1) * channels * 3

    /** DSF's layout: [bytesPerChannel] bytes of each channel one after another, the first [frames] of each audio. */
    fun planar(block: ByteArray, bytesPerChannel: Int, frames: Int, lsbFirst: Boolean, out: ByteBuffer) {
        for (i in 0 until frames) step(out) { c -> order(block[c * bytesPerChannel + i], lsbFirst) }
    }

    /** DFF's layout: one byte of each channel in turn, [frames] times, from [offset]. */
    fun interleaved(data: ByteArray, offset: Int, frames: Int, lsbFirst: Boolean, out: ByteBuffer) {
        for (i in 0 until frames) step(out) { c -> order(data[offset + i * channels + c], lsbFirst) }
    }

    /** The end of the stream: a byte still waiting for its pair gets DSD silence. */
    fun finish(out: ByteBuffer) {
        if (holding) step(out) { SILENCE }
    }

    /** One byte of every channel: kept as the first of a pair, or written out with it as a frame. */
    private inline fun step(out: ByteBuffer, byte: (Int) -> Byte) {
        if (!holding) {
            for (c in 0 until channels) held[c] = byte(c)
            holding = true
            return
        }
        for (c in 0 until channels) {
            // Little-endian: the later byte lowest, then the earlier one, the marker on top
            out.put(byte(c))
            out.put(held[c])
            out.put(marker)
        }
        marker = if (marker == MARKER_A) MARKER_B else MARKER_A
        holding = false
    }

    /** DoP wants the earliest bit highest. */
    private fun order(value: Byte, lsbFirst: Boolean): Byte = if (lsbFirst) REVERSED[value.toInt() and 0xFF] else value

    companion object {
        const val MARKER_A: Byte = 0x05
        const val MARKER_B: Byte = 0xFA.toByte()
        private const val SILENCE: Byte = 0x69
        private val REVERSED = ByteArray(256) { Integer.reverse(it).ushr(24).toByte() }

        /** The PCM rate a DSD stream travels at as DoP; 0 when it doesn't divide. */
        fun pcmRate(dsdRate: Int): Int = if (dsdRate % 16 == 0) dsdRate / 16 else 0
    }
}

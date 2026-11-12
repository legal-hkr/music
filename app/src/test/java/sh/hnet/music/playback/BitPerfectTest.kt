package sh.hnet.music.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitPerfectTest {
    private val device = listOf(16, 24, 32).flatMap { bits -> listOf(44_100, 48_000, 96_000, 192_000).map { OutputFormat(it, bits, false, 2) } }

    @Test
    fun `a stream goes out at its own rate in the narrowest sample that holds it`() {
        assertEquals(OutputFormat(44_100, 16, false, 2), BitPerfect.choose(device, 44_100, 16, 2))
        assertEquals(OutputFormat(96_000, 24, false, 2), BitPerfect.choose(device, 96_000, 24, 2))
        // Mono is sent to both channels
        assertEquals(OutputFormat(48_000, 16, false, 2), BitPerfect.choose(device, 48_000, 16, 1))
        // A device with 32-bit samples only: padding
        assertEquals(OutputFormat(44_100, 32, false, 2), BitPerfect.choose(listOf(OutputFormat(44_100, 32, false, 2)), 44_100, 16, 2))
    }

    @Test
    fun `what the device can't take unchanged plays the usual way`() {
        assertNull(BitPerfect.choose(device, 88_200, 24, 2))
        assertNull(BitPerfect.choose(device, 176_400, 24, 2))
        // 24 bits into 16 would lose some
        assertNull(BitPerfect.choose(listOf(OutputFormat(96_000, 16, false, 2)), 96_000, 24, 2))
        // 32-bit and float sources, more than two channels, a float-only or mono-only device
        assertNull(BitPerfect.choose(device, 96_000, 32, 2))
        assertNull(BitPerfect.choose(device, 96_000, 0, 2))
        assertNull(BitPerfect.choose(device, 48_000, 16, 6))
        assertNull(BitPerfect.choose(listOf(OutputFormat(48_000, 32, true, 2), OutputFormat(48_000, 16, false, 1)), 48_000, 16, 2))
    }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).also { b -> values.forEach(b::putFloat); b.flip() }

    @Test
    fun `16-bit samples come back bit for bit, in 16, 24 and 32 bits`() {
        val samples = shortArrayOf(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)
        // As ReplayGainSink makes them
        val input = floats(*samples.map { it / 32768f }.toFloatArray())
        val out16 = ByteBuffer.allocate(BitPerfect.outputBytes(input.remaining(), 1, 16))
        BitPerfect.toIntegers(input, 1, 16, out16)
        out16.flip()
        // Mono: each sample twice
        samples.forEach { s -> assertEquals(s, out16.getShort()); assertEquals(s, out16.getShort()) }
        assertEquals(0, input.position())

        val out32 = ByteBuffer.allocate(BitPerfect.outputBytes(input.remaining(), 1, 32))
        BitPerfect.toIntegers(input, 1, 32, out32)
        out32.flip()
        samples.forEach { s -> assertEquals(s.toInt() shl 16, out32.getInt()); out32.getInt() }
    }

    @Test
    fun `24-bit samples come back bit for bit`() {
        val samples = intArrayOf(0, 1, -1, 8_388_607, -8_388_608, 1_234_567, -7_654_321, 0x55AA55, 0x2AD5AA)
        val input = floats(*samples.map { it / 8388608f }.toFloatArray())
        val out = ByteBuffer.allocate(BitPerfect.outputBytes(input.remaining(), 2, 24) * 2)
        BitPerfect.toIntegers(input.duplicate().order(ByteOrder.nativeOrder()).also { it.limit(8 * 4) }, 2, 24, out)
        out.flip()
        assertEquals(8 * 3, out.remaining())
        samples.take(8).forEach { s ->
            val v = (out.get().toInt() and 0xFF) or ((out.get().toInt() and 0xFF) shl 8) or (out.get().toInt() shl 16)
            assertEquals(s, v)
        }
        assertEquals(96, BitPerfect.outputBytes(64, 2, 24) * 2)
    }
}

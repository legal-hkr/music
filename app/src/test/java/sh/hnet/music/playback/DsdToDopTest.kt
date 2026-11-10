package sh.hnet.music.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DsdToDopTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun packed(block: (DsdToDop, ByteBuffer) -> Unit, channels: Int = 2): ByteArray {
        val out = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        block(DsdToDop(channels), out)
        return out.array().copyOf(out.position())
    }

    @Test
    fun `two bytes of each channel make a frame under an alternating marker`() {
        // DFF: left, right in turn, earliest bit highest
        val out = packed({ dop, o -> dop.interleaved(bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88), 0, 4, false, o) })
        assertArrayEquals(
            bytes(
                0x33, 0x11, 0x05, 0x44, 0x22, 0x05,
                0x77, 0x55, 0xFA, 0x88, 0x66, 0xFA,
            ),
            out,
        )
    }

    @Test
    fun `DSF blocks are read channel by channel with their bits turned round`() {
        // Two bytes per channel in a block of 4; 0x01 lsb-first is 0x80 as DoP wants it
        val block = bytes(0x01, 0x02, 0, 0, 0x80, 0xFF, 0, 0)
        val out = packed({ dop, o -> dop.planar(block, 4, 2, true, o) })
        assertArrayEquals(bytes(0x40, 0x80, 0x05, 0xFF, 0x01, 0x05), out)
    }

    @Test
    fun `an odd byte waits for the next group, or for silence at the end`() {
        val out = packed({ dop, o ->
            dop.interleaved(bytes(0x11), 0, 1, false, o)
            assertEquals(0, o.position())
            dop.interleaved(bytes(0x22, 0x33), 0, 2, false, o)
            dop.finish(o)
        }, channels = 1)
        assertArrayEquals(bytes(0x22, 0x11, 0x05, 0x69, 0x33, 0xFA), out)
        assertEquals(176_400, DsdToDop.pcmRate(2_822_400))
        assertEquals(705_600, DsdToDop.pcmRate(11_289_600))
    }

    @Test
    fun `DoP survives the float path bit for bit and is told from music`() {
        val dsd = ByteArray(64) { (it * 37 + 11).toByte() }
        val dop = packed({ d, o -> d.interleaved(dsd, 0, 32, false, o) })
        val asInts = ByteBuffer.wrap(dop).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(BitPerfect.isDop24(asInts, 2))
        // As ReplayGainSink reads 24-bit samples
        val floats = ByteBuffer.allocate(dop.size / 3 * 4).order(ByteOrder.nativeOrder())
        repeat(dop.size / 3) {
            val v = (asInts.get().toInt() and 0xFF) or ((asInts.get().toInt() and 0xFF) shl 8) or (asInts.get().toInt() shl 16)
            floats.putFloat(v / 8388608f)
        }
        floats.flip()
        assertTrue(BitPerfect.isDopFloat(floats, 2))
        val back = ByteBuffer.allocate(dop.size)
        BitPerfect.toIntegers(floats, 2, 24, back)
        assertArrayEquals(dop, back.array())
        // In a 32-bit sample the marker stays on top
        val wide = ByteBuffer.allocate(dop.size / 3 * 4)
        BitPerfect.toIntegers(floats, 2, 32, wide)
        wide.flip()
        assertEquals(0x05, wide.order(ByteOrder.LITTLE_ENDIAN).getInt() ushr 24)

        val music = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16) { music.put(it.toByte()); music.put(1); music.put(0x05) }
        music.flip()
        assertFalse(BitPerfect.isDop24(music, 2))
    }
}

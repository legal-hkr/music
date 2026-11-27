package sh.hnet.music.playback

import androidx.media3.common.Format
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bit for bit against ffmpeg: the files in resources/alac are synthetic signals ffmpeg encoded, vectors.txt holds the
 * SHA-256 of ffmpeg's own decode of each (gen.sh makes both). Covers 16 and 24 bits, mono, pairs with and without
 * mid/side mixing, every channel layout (4.0 as seven channels), prediction orders 1–8, 14, 22–30, bytes shifted,
 * uncompressed frames, silence and a short last frame. 20 and 32 bits have no file: ffmpeg's encoder writes neither
 * (a review fuzzed them against ffmpeg's decoder on 27.11.2026).
 */
class AlacDecoderTest {
    private class Vector(val name: String, val bits: Int, val rate: Int, val channels: Int, val frames: Int, val sha256: String)

    private val vectors = javaClass.getResourceAsStream("/alac/vectors.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.map {
        val f = it.split(' ')
        Vector(f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5])
    }

    @Test
    fun `every vector decodes exactly as ffmpeg decodes it`() {
        for (v in vectors) {
            val mp4 = Mp4(javaClass.getResourceAsStream("/alac/${v.name}.m4a")!!.readBytes())
            val config = AlacConfig.parse(mp4.cookie)
            assertEquals(v.name, v.bits, config.bitDepth)
            assertEquals(v.name, v.channels, config.channels)
            assertEquals(v.name, v.rate, config.sampleRate)
            val decoder = AlacDecoder(config)
            val digest = MessageDigest.getInstance("SHA-256")
            val out = ByteBuffer.allocate(config.frameLength * config.outputChannels * config.outputBytes)
            var frames = 0
            for ((offset, size) in mp4.samples) {
                out.clear()
                frames += decoder.decode(mp4.data, offset, size, out)
                digest.update(out.array(), 0, out.position())
            }
            assertEquals(v.name, v.frames, frames)
            assertEquals(v.name, v.sha256, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun `the config is found bare, after version and flags, or in its box`() {
        val box = Mp4(javaClass.getResourceAsStream("/alac/s16_stereo_44k.m4a")!!.readBytes()).cookie
        val bare = box.copyOfRange(12, 36)
        for (cookie in listOf(box, bare, box.copyOfRange(8, 36))) {
            val config = AlacConfig.parse(cookie)
            assertEquals(4096, config.frameLength)
            assertEquals(16, config.bitDepth)
            assertEquals(2, config.channels)
            assertEquals(44100, config.sampleRate)
        }
        assertThrows(AlacException::class.java) { AlacConfig.parse(bare.copyOf(20)) }
    }

    @Test
    fun `fill and data stream elements are skipped`() {
        val v = vectors.first { it.name == "s16_stereo_44k" }
        val mp4 = Mp4(javaClass.getResourceAsStream("/alac/${v.name}.m4a")!!.readBytes())
        val config = AlacConfig.parse(mp4.cookie)
        // A data stream element (3 bytes), and an empty fill element followed by an empty aligned data stream element
        for (prefix in listOf(byteArrayOf(0x80.toByte(), 3, 1, 2, 3), byteArrayOf(0xC1.toByte(), 2, 0))) {
            val decoder = AlacDecoder(config)
            val out = ByteBuffer.allocate(config.frameLength * config.outputChannels * config.outputBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            for ((offset, size) in mp4.samples) {
                val frame = prefix + mp4.data.copyOfRange(offset, offset + size)
                out.clear()
                decoder.decode(frame, 0, frame.size, out)
                digest.update(out.array(), 0, out.position())
            }
            assertEquals(v.sha256, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun `damaged frames are an error, never a crash`() {
        val random = Random(1)
        for (v in vectors) {
            val mp4 = Mp4(javaClass.getResourceAsStream("/alac/${v.name}.m4a")!!.readBytes())
            val config = AlacConfig.parse(mp4.cookie)
            val decoder = AlacDecoder(config)
            val out = ByteBuffer.allocate(config.frameLength * config.outputChannels * config.outputBytes)
            repeat(300) {
                val (offset, size) = mp4.samples[random.nextInt(mp4.samples.size)]
                val frame = mp4.data.copyOfRange(offset, offset + size)
                when (random.nextInt(3)) {
                    0 -> repeat(1 + random.nextInt(8)) { val bit = random.nextInt(frame.size * 8); frame[bit / 8] = (frame[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
                    1 -> frame[random.nextInt(frame.size)] = random.nextInt().toByte()
                }
                val length = if (random.nextBoolean()) frame.size else random.nextInt(frame.size + 1)
                out.clear()
                try {
                    decoder.decode(frame, 0, length, out)
                } catch (_: AlacException) {
                }
            }
        }
        assertThrows(AlacException::class.java) { AlacDecoder(AlacConfig.parse(Mp4(javaClass.getResourceAsStream("/alac/s16_mono_22k.m4a")!!.readBytes()).cookie)).decode(ByteArray(4), 2, 4, ByteBuffer.allocate(16)) }
    }

    @Test
    fun `a damaged or cut frame is an error, not a crash`() {
        val mp4 = Mp4(javaClass.getResourceAsStream("/alac/s16_stereo_44k.m4a")!!.readBytes())
        val config = AlacConfig.parse(mp4.cookie)
        val out = ByteBuffer.allocate(config.frameLength * config.outputChannels * config.outputBytes)
        val (offset, size) = mp4.samples[1]
        assertThrows(AlacException::class.java) { AlacDecoder(config).decode(mp4.data, offset, size / 2, out.also { it.clear() }) }
        // Garbage: whatever it decodes to, it never reads past the frame or throws anything else
        val random = Random(1)
        repeat(200) {
            val junk = ByteArray(64 + random.nextInt(2000)).also(random::nextBytes)
            try {
                AlacDecoder(config).decode(junk, 0, junk.size, out.also { it.clear() })
            } catch (_: AlacException) {
            }
        }
    }

    @Test(timeout = 20_000)
    fun `through Media3's decoder the output is the same`() {
        val v = vectors.first { it.name == "s24_stereo_96k" }
        val mp4 = Mp4(javaClass.getResourceAsStream("/alac/${v.name}.m4a")!!.readBytes())
        val decoder = AlacMediaDecoder(AlacConfig.parse(mp4.cookie), Format.NO_VALUE)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            for ((index, sample) in mp4.samples.withIndex()) {
                val (offset, size) = sample
                val input = decoder.dequeueInputBuffer()!!
                input.ensureSpaceForWrite(size + 7)
                // Not at the start of the buffer's array: the decoder must read from where the data is
                input.data!!.position(7)
                input.data!!.put(mp4.data, offset, size).flip().position(7)
                input.timeUs = index * 1000L
                decoder.queueInputBuffer(input)
                val output = decoder.next()
                val data = output.data!!
                val bytes = ByteArray(data.remaining()).also { data.get(it) }
                digest.update(bytes)
                output.release()
            }
        } finally {
            decoder.release()
        }
        assertEquals(v.sha256, digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test(timeout = 20_000)
    fun `through Media3, a damaged frame plays as silence and a run of them is an error`() {
        val mp4 = Mp4(javaClass.getResourceAsStream("/alac/s16_stereo_44k.m4a")!!.readBytes())
        val config = AlacConfig.parse(mp4.cookie)
        val decoder = AlacMediaDecoder(config, Format.NO_VALUE)
        val junk = ByteArray(40) { 0x7f }
        fun queue(bytes: ByteArray) {
            val input = decoder.dequeueInputBuffer()!!
            input.ensureSpaceForWrite(bytes.size)
            input.data!!.put(bytes).flip()
            decoder.queueInputBuffer(input)
        }
        try {
            queue(junk)
            val silent = decoder.next()
            assertEquals(config.frameLength * 2 * 2, silent.data!!.remaining())
            assertEquals(true, (0 until silent.data!!.remaining()).all { silent.data!!.get(it).toInt() == 0 })
            silent.release()
            val (offset, size) = mp4.samples[0]
            queue(mp4.data.copyOfRange(offset, offset + size))
            decoder.next().release()
            assertThrows(AlacDecoderException::class.java) {
                repeat(20) {
                    queue(junk)
                    decoder.next().release()
                }
            }
        } finally {
            decoder.release()
        }
    }

    private fun AlacMediaDecoder.next(): SimpleDecoderOutputBuffer {
        while (true) {
            dequeueOutputBuffer()?.let { return it }
            Thread.sleep(1)
        }
    }

    /** Just enough of MP4 for one ALAC track: the 'alac' box inside the sample entry, and where each sample is. */
    private class Mp4(val data: ByteArray) {
        lateinit var cookie: ByteArray
        val samples = ArrayList<Pair<Int, Int>>()
        private var sizes = IntArray(0)
        private var chunkOffsets = LongArray(0)
        private var perChunk = ArrayList<IntArray>() // first chunk (1-based), samples per chunk

        init {
            boxes(0, data.size)
            var sample = 0
            for (chunk in chunkOffsets.indices) {
                val count = perChunk.last { it[0] <= chunk + 1 }[1]
                var at = chunkOffsets[chunk].toInt()
                repeat(count) {
                    samples += at to sizes[sample]
                    at += sizes[sample++]
                }
            }
        }

        private fun u32(at: Int) = ((data[at].toInt() and 0xff) shl 24) or ((data[at + 1].toInt() and 0xff) shl 16) or
            ((data[at + 2].toInt() and 0xff) shl 8) or (data[at + 3].toInt() and 0xff)

        private fun type(at: Int) = String(data, at + 4, 4, Charsets.ISO_8859_1)

        private fun boxes(from: Int, to: Int) {
            var at = from
            while (at + 8 <= to) {
                val size = u32(at)
                when (type(at)) {
                    "moov", "trak", "mdia", "minf", "stbl" -> boxes(at + 8, at + size)
                    // Full box (4) and entry count (4), then the sample entry: its 28-byte audio header, then its boxes
                    "stsd" -> {
                        val entry = at + 16
                        var inner = entry + 36
                        while (inner < entry + u32(entry)) {
                            if (type(inner) == "alac") cookie = data.copyOfRange(inner, inner + u32(inner))
                            inner += u32(inner)
                        }
                    }
                    "stsz" -> sizes = IntArray(u32(at + 16)) { u32(at + 20 + it * 4) }
                    "stco" -> chunkOffsets = LongArray(u32(at + 12)) { u32(at + 16 + it * 4).toLong() }
                    "stsc" -> repeat(u32(at + 12)) { perChunk += intArrayOf(u32(at + 16 + it * 12), u32(at + 20 + it * 12)) }
                }
                at += size
            }
        }
    }
}

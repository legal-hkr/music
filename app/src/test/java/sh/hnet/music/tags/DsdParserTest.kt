package sh.hnet.music.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdParserTest {
    private fun synchsafe(v: Int) = bytes { u8(v ushr 21 and 0x7F); u8(v ushr 14 and 0x7F); u8(v ushr 7 and 0x7F); u8(v and 0x7F) }
    private fun frame(id: String, body: ByteArray) = bytes { ascii(id); be32(body.size.toLong()); be16(0); bytes(body) }
    private fun text(value: String) = bytes { u8(3); utf8(value) }
    private fun id3(frames: ByteArray) = bytes { ascii("ID3"); u8(3); u8(0); u8(0); bytes(synchsafe(frames.size)); bytes(frames) }

    private val tag = id3(bytes {
        bytes(frame("TIT2", text("Zażółć")))
        bytes(frame("TPE1", text("Artysta")))
        bytes(frame("TALB", text("Album")))
        bytes(frame("TRCK", text("3/9")))
        bytes(frame("TXXX", bytes { u8(0); ascii("REPLAYGAIN_TRACK_GAIN\u0000-3.20 dB") }))
        bytes(frame("APIC", ByteArray(2000)))
    })

    /** Stereo DSF: [blocks] blocks of 4096 bytes per channel, of which [samples] bits are audio. */
    private fun dsf(rate: Long, samples: Long, blocks: Int, bits: Int = 1, tag: ByteArray? = null, channels: Int = 2): ByteArray {
        val data = blocks * 4096 * channels
        val audioEnd = 28L + 52 + 12 + data
        return bytes {
            ascii("DSD "); le64(28); le64(audioEnd + (tag?.size ?: 0)); le64(if (tag != null) audioEnd else 0)
            ascii("fmt "); le64(52); le32(1); le32(0); le32(2); le32(channels.toLong()); le32(rate); le32(bits.toLong())
            le64(samples); le32(4096); le32(0)
            ascii("data"); le64(12L + data); zeros(data)
            tag?.let { bytes(it) }
        }
    }

    private fun chunk(id: String, body: ByteArray) = bytes { ascii(id); be64(body.size.toLong()); bytes(body); if (body.size % 2 == 1) u8(0) }

    private fun dff(compression: String = "DSD ", audio: Int = 705_600, after: ByteArray = ByteArray(0)): ByteArray {
        val prop = bytes {
            ascii("SND ")
            bytes(chunk("FS  ", bytes { be32(2_822_400) }))
            bytes(chunk("CHNL", bytes { be16(2); ascii("SLFTSRGT") }))
            // The compression's name is a counted string, padded to an even length
            bytes(chunk("CMPR", bytes { ascii(compression); u8(14); ascii("not compressed"); u8(0) }))
            bytes(chunk("ABSS", bytes { be16(0); u8(0); u8(0); be32(0) }))
        }
        val body = bytes {
            ascii("DSD ")
            bytes(chunk("FVER", bytes { be32(0x01050000) }))
            bytes(chunk("PROP", prop))
            if (compression == "DSD ") bytes(chunk("DSD ", ByteArray(audio))) else bytes(chunk("DST ", ByteArray(100)))
            bytes(after)
        }
        return bytes { ascii("FRM8"); be64(body.size.toLong()); bytes(body) }
    }

    @Test
    fun dsfWithTagAtTheEnd() {
        // 10 s of DSD64: 28 224 000 bits per channel, in 862 blocks (the last one partly padding)
        val file = dsf(2_822_400, 28_224_000, 862, tag = tag)
        val (props, tags) = parse(file)
        assertEquals(AudioCodec.DSD, props.codec)
        assertEquals(2_822_400, props.sampleRate)
        assertEquals(1, props.bitsPerSample)
        assertEquals(2, props.channels)
        assertEquals(10_000L, props.durationMs)
        assertEquals(5644, props.bitrateKbps)
        assertEquals("Zażółć", tags.title)
        assertEquals(listOf("Artysta"), tags.artists)
        assertEquals("Album", tags.album)
        assertEquals(3, tags.trackNumber)
        assertEquals(9, tags.trackTotal)
        assertEquals(-3.2f, tags.replayGain.trackGainDb)
        assertTrue(tags.hasEmbeddedCover)
    }

    @Test
    fun dsfLayoutForThePlayer() {
        val stream = DsdFormat.readDsf(SourceReader(ByteArraySource(dsf(5_644_800, 1_000_000, 31, bits = 8))))
        assertEquals(92L, stream.dataOffset)
        assertEquals(31L * 4096 * 2, stream.dataBytes)
        assertEquals(4096, stream.blockSize)
        assertEquals(1_000_000L, stream.sampleCount)
        assertFalse(stream.lsbFirst)
        assertTrue(DsdFormat.readDsf(SourceReader(ByteArraySource(dsf(2_822_400, 8, 1)))).lsbFirst)
    }

    @Test
    fun dsfWithoutTagOrWithAPointerOutsideTheFile() {
        assertNull(parse(dsf(2_822_400, 2_822_400, 87)).tags.title)
        val file = dsf(2_822_400, 2_822_400, 87)
        // Metadata pointer far beyond the end
        for (i in 20 until 28) file[i] = 0x7F
        assertEquals(1000L, parse(file).properties.durationMs)
    }

    @Test
    fun dsfNeverClaimsMoreSamplesThanItHolds() {
        val (props, _) = parse(dsf(2_822_400, Long.MAX_VALUE / 2, 10))
        assertEquals(10L * 4096 * 8 * 1000 / 2_822_400, props.durationMs)
    }

    @Test
    fun damagedDsfIsRejectedQuietly() {
        assertNull(TagParser.parse(ByteArraySource(dsf(2_822_400, 1000, 1).copyOf(60))))
        assertNull(TagParser.parse(ByteArraySource(dsf(44_100, 1000, 1))))
        assertNull(TagParser.parse(ByteArraySource(dsf(2_822_400, 1000, 1, bits = 16))))
        assertNull(TagParser.parse(ByteArraySource(dsf(2_822_400, 1000, 0, channels = 0))))
    }

    @Test
    fun dffWithId3ChunkAndMasterInformation() {
        val diin = chunk("DIIN", bytes {
            bytes(chunk("DIAR", bytes { be32(7); ascii("Someone") }))
            bytes(chunk("DITI", bytes { be32(5); ascii("Other") }))
        })
        val (props, tags) = parse(dff(after = diin + chunk("ID3 ", tag)))
        assertEquals(AudioCodec.DSD, props.codec)
        assertEquals(2_822_400, props.sampleRate)
        assertEquals(2, props.channels)
        assertEquals(1000L, props.durationMs)
        // The ID3 chunk comes first
        assertEquals("Zażółć", tags.title)
        assertEquals(listOf("Artysta"), tags.artists)

        val plain = parse(dff(after = diin))
        assertEquals("Other", plain.tags.title)
        assertEquals(listOf("Someone"), plain.tags.artists)
    }

    @Test
    fun dffLayoutForThePlayer() {
        val file = dff(audio = 1001)
        val stream = DsdFormat.readDff(SourceReader(ByteArraySource(file)))!!
        assertEquals(0, stream.blockSize)
        assertFalse(stream.lsbFirst)
        assertEquals(1001L, stream.dataBytes)
        assertEquals(500L * 8, stream.sampleCount)
        // The audio is where the header says: right after the "DSD " chunk's header
        assertEquals("DSD ", String(file, (stream.dataOffset - 12).toInt(), 4, Charsets.ISO_8859_1))
        assertEquals(file.size.toLong(), stream.dataOffset + 1001 + 1)
    }

    @Test
    fun dstCompressedDffIsNotForUs() {
        assertNull(TagParser.parse(ByteArraySource(dff(compression = "DST "))))
    }

    @Test
    fun truncatedDffIsRejectedQuietly() {
        assertNull(TagParser.parse(ByteArraySource(dff().copyOf(70))))
        // Cut inside the audio: the properties are still there
        assertEquals(AudioCodec.DSD, TagParser.parse(ByteArraySource(dff().copyOf(5000)))?.properties?.codec)
    }
}

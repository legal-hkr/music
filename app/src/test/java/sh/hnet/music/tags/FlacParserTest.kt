package sh.hnet.music.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacParserTest {
    private fun streamInfo(rate: Int, channels: Int, bits: Int, samples: Long) = bytes {
        be16(4096); be16(4096); be24(0); be24(0)
        // 20 bits rate, 3 bits channels-1, 5 bits bps-1, 36 bits total samples
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36) or samples
        be64(packed)
        zeros(16)
    }

    private fun block(type: Int, last: Boolean, body: ByteArray) = bytes {
        u8(type or if (last) 0x80 else 0); be24(body.size); bytes(body)
    }

    @Test
    fun readsStreamInfoAndComments() {
        val picture = "METADATA_BLOCK_PICTURE=" + "A".repeat(5000)
        val file = bytes {
            ascii("fLaC")
            bytes(block(0, false, streamInfo(96_000, 2, 24, 96_000L * 10)))
            bytes(block(4, false, vorbisComments(
                "TITLE=Tytuł", "artist=One", "ARTIST=Two", "ALBUM ARTIST=Both", "TRACKNUMBER=3",
                "TRACKTOTAL=12", "DATE=1997-05-21", "REPLAYGAIN_TRACK_GAIN=-6.50 dB",
                "REPLAYGAIN_TRACK_PEAK=0.988", picture,
            )))
            bytes(block(1, true, ByteArray(100)))
            zeros(10_000)
        }
        val (props, tags) = parse(file)
        assertEquals(AudioCodec.FLAC, props.codec)
        assertEquals(96_000, props.sampleRate)
        assertEquals(24, props.bitsPerSample)
        assertEquals(2, props.channels)
        assertEquals(10_000L, props.durationMs)
        assertEquals("Tytuł", tags.title)
        assertEquals(listOf("One", "Two"), tags.artists)
        assertEquals(listOf("Both"), tags.albumArtists)
        assertEquals(3, tags.trackNumber)
        assertEquals(12, tags.trackTotal)
        assertEquals(1997, tags.year)
        assertEquals(-6.5f, tags.replayGain.trackGainDb)
        assertEquals(0.988f, tags.replayGain.trackPeak)
        assertTrue(tags.hasEmbeddedCover)
    }

    @Test
    fun readsRealFile() {
        val file = javaClass.getResourceAsStream("/tags/tone-48k-24bit.flac")!!.readBytes()
        val (props, tags) = parse(file)
        assertEquals(48_000, props.sampleRate)
        assertEquals(24, props.bitsPerSample)
        assertEquals(1000L, props.durationMs)
        assertEquals("Ton A – próba", tags.title)
        assertEquals(listOf("Zespół Testowy", "Gość"), tags.artists)
        assertEquals(2, tags.trackNumber)
        assertEquals(7, tags.trackTotal)
        assertEquals(1, tags.discNumber)
        assertEquals(2, tags.discTotal)
        assertEquals(ReplayGain(-4.2f, 0.25f, -5f, 0.3f), tags.replayGain)
    }

    @Test
    fun truncatedFileIsRejectedQuietly() {
        val file = javaClass.getResourceAsStream("/tags/tone-48k-24bit.flac")!!.readBytes().copyOf(60)
        assertNull(TagParser.parse(ByteArraySource(file)))
    }
}

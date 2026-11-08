package sh.hnet.music.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OggParserTest {
    private val serial = 0x1234L

    private fun page(granule: Long, type: Int, segments: List<Int>, body: ByteArray, serial: Long = this.serial) = bytes {
        ascii("OggS"); u8(0); u8(type); le64(granule); le32(serial); le32(0); le32(0)
        u8(segments.size); segments.forEach { u8(it) }; bytes(body)
    }

    /** Lacing values of a whole packet. */
    private fun lacing(length: Int) = List(length / 255) { 255 } + (length % 255)

    @Test
    fun opusWithCommentsAcrossPages() {
        val head = bytes { ascii("OpusHead"); u8(1); u8(2); le16(312); le32(44_100); le16(0); u8(0) }
        val tags = bytes {
            ascii("OpusTags")
            bytes(vorbisComments("METADATA_BLOCK_PICTURE=" + "B".repeat(600), "TITLE=Opus", "R128_TRACK_GAIN=-512"))
        }
        val laces = lacing(tags.size)
        val firstPart = laces.take(2)
        val split = firstPart.sum()
        val file = page(0, 2, lacing(head.size), head) +
            page(-1, 0, firstPart, tags.copyOfRange(0, split)) +
            page(0, 1, laces.drop(2), tags.copyOfRange(split, tags.size)) +
            page(999_999, 0, listOf(10), ByteArray(10), serial = 0x9999) + // another stream: ignored
            page(48_000L * 3 + 312, 4, listOf(100), ByteArray(100))
        val (props, parsed) = parse(file)
        assertEquals(AudioCodec.OPUS, props.codec)
        assertEquals(48_000, props.sampleRate)
        assertEquals(2, props.channels)
        assertEquals(3000L, props.durationMs)
        assertEquals("Opus", parsed.title)
        assertEquals(3f, parsed.replayGain.trackGainDb)
        assertTrue(parsed.hasEmbeddedCover)
    }

    @Test
    fun vorbis() {
        val ident = bytes {
            u8(1); ascii("vorbis"); le32(0); u8(2); le32(44_100); le32(0); le32(192_000); le32(0); u8(0xB8); u8(1)
        }
        val comments = bytes { u8(3); ascii("vorbis"); bytes(vorbisComments("ARTIST=V", "GENRE=Jazz")); u8(1) }
        val file = page(0, 2, lacing(ident.size), ident) +
            page(0, 0, lacing(comments.size), comments) +
            page(88_200, 4, listOf(50), ByteArray(50))
        val (props, tags) = parse(file)
        assertEquals(AudioCodec.VORBIS, props.codec)
        assertEquals(44_100, props.sampleRate)
        assertEquals(2000L, props.durationMs)
        assertEquals(listOf("V"), tags.artists)
        assertEquals(listOf("Jazz"), tags.genres)
    }
}

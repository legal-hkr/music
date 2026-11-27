package sh.hnet.music.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3ParserTest {
    // MPEG 1 Layer III, 128 kbps, 44.1 kHz, stereo: 417 bytes per frame
    private val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    private val frameLength = 417

    private fun plainFrames(count: Int) = bytes {
        repeat(count) { bytes(header); zeros(frameLength - 4) }
    }

    private fun xingFrame(frames: Long, delay: Int, padding: Int) = bytes {
        bytes(header); zeros(32)
        ascii("Xing"); be32(3); be32(frames); be32(frames * frameLength)
        ascii("LAME3.100"); zeros(12)
        u8(delay ushr 4); u8(((delay and 0xF) shl 4) or (padding ushr 8)); u8(padding and 0xFF)
    }.let { it + ByteArray(frameLength - it.size) }

    private fun synchsafe(v: Int) = bytes { u8(v ushr 21 and 0x7F); u8(v ushr 14 and 0x7F); u8(v ushr 7 and 0x7F); u8(v and 0x7F) }

    private fun id3(major: Int, frames: ByteArray) = bytes {
        ascii("ID3"); u8(major); u8(0); u8(0); bytes(synchsafe(frames.size + 64)); bytes(frames); zeros(64)
    }

    private fun frame23(id: String, body: ByteArray) = bytes { ascii(id); be32(body.size.toLong()); be16(0); bytes(body) }
    private fun frame24(id: String, body: ByteArray, plainSize: Boolean = false) = bytes {
        ascii(id); if (plainSize) be32(body.size.toLong()) else bytes(synchsafe(body.size)); be16(0); bytes(body)
    }
    private fun utf16(text: String) = bytes { u8(1); bytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); bytes(text.toByteArray(Charsets.UTF_16LE)) }
    private fun text8(vararg values: String) = bytes { u8(3); utf8(values.joinToString("\u0000")) }

    @Test
    fun id3v23WithXingAndLame() {
        val tag = id3(3, bytes {
            bytes(frame23("TIT2", utf16("Zażółć")))
            bytes(frame23("TPE1", utf16("Artysta")))
            bytes(frame23("TRCK", bytes { u8(0); ascii("5/10") }))
            bytes(frame23("TCON", bytes { u8(0); ascii("(17)") }))
            bytes(frame23("TXXX", bytes { u8(0); ascii("replaygain_track_gain\u0000-7.01 dB") }))
            bytes(frame23("APIC", ByteArray(3000)))
        })
        val file = tag + xingFrame(1000, 576, 1000) + plainFrames(3)
        val (props, tags) = parse(file)
        assertEquals(AudioCodec.MP3, props.codec)
        assertEquals(44_100, props.sampleRate)
        assertEquals((1000L * 1152 - 1576) * 1000 / 44_100, props.durationMs)
        assertEquals("Zażółć", tags.title)
        assertEquals(listOf("Artysta"), tags.artists)
        assertEquals(5, tags.trackNumber)
        assertEquals(10, tags.trackTotal)
        assertEquals(listOf("Rock"), tags.genres)
        assertEquals(-7.01f, tags.replayGain.trackGainDb)
        assertTrue(tags.hasEmbeddedCover)
    }

    @Test
    fun id3v24MultipleValuesAndLargeFrames() {
        val longTitle = "x".repeat(300)
        val tag = id3(4, bytes {
            bytes(frame24("TIT2", text8(longTitle)))
            bytes(frame24("TPE1", text8("A", "B")))
            bytes(frame24("TDRC", text8("2001-02-03")))
            bytes(frame24("TXXX", text8("REPLAYGAIN_ALBUM_GAIN", "+1.25 dB")))
        })
        val (_, tags) = parse(tag + plainFrames(4))
        assertEquals(longTitle, tags.title)
        assertEquals(listOf("A", "B"), tags.artists)
        assertEquals(2001, tags.year)
        assertEquals(1.25f, tags.replayGain.albumGainDb)
    }

    @Test
    fun id3v24WithPlainFrameSizes() {
        // 256 bytes: written plain as 0x00000100, which as synchsafe would read 128
        val title = "t".repeat(255)
        val tag = id3(4, bytes {
            bytes(frame24("TIT2", text8(title), plainSize = true))
            bytes(frame24("TPE1", text8("Artist"), plainSize = true))
        })
        val (_, tags) = parse(tag + plainFrames(4))
        assertEquals(title, tags.title)
        assertEquals(listOf("Artist"), tags.artists)
    }

    @Test
    fun apeAndId3v1AtTheEnd() {
        val apeItems = bytes {
            fun item(key: String, value: String) {
                val v = value.toByteArray(Charsets.UTF_8)
                le32(v.size.toLong()); le32(0); ascii(key); u8(0); bytes(v)
            }
            item("REPLAYGAIN_TRACK_GAIN", "-2.50 dB")
            item("Album Artist", "Various")
        }
        val ape = bytes {
            bytes(apeItems)
            ascii("APETAGEX"); le32(2000); le32(apeItems.size + 32L); le32(2); le32(0); zeros(8)
        }
        val v1 = bytes {
            ascii("TAG")
            fun field(s: String, n: Int) = bytes(s.toByteArray(Charsets.ISO_8859_1).copyOf(n))
            field("Old title", 30); field("Old artist", 30); field("Old album", 30); field("1999", 4)
            zeros(28); u8(0); u8(7); u8(17)
        }
        val audio = plainFrames(100)
        val (props, tags) = parse(audio + ape + v1)
        assertEquals(audio.size.toLong() * 8 / 128, props.durationMs)
        assertEquals(128, props.bitrateKbps)
        assertEquals("Old title", tags.title)
        assertEquals(listOf("Various"), tags.albumArtists)
        assertEquals(7, tags.trackNumber)
        assertEquals(listOf("Rock"), tags.genres)
        assertEquals(-2.5f, tags.replayGain.trackGainDb)
    }

    @Test
    fun layerTwoNeedsFourFramesInARow() {
        // MPEG 1 Layer II, 128 kbps, 44.1 kHz: also 417 bytes per frame
        val layer2 = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x80.toByte(), 0x00)
        fun frames(count: Int) = bytes { repeat(count) { bytes(layer2); zeros(frameLength - 4) } }
        assertEquals(AudioCodec.MP2, TagParser.parse(ByteArraySource(frames(6)))?.properties?.codec)
        // Two Layer II frames by chance in junk, then the real MP3: not taken for MP2 (GH #8 would hide the file)
        val chance = frames(2) + ByteArray(500) { 0x11 } + plainFrames(6)
        assertEquals(AudioCodec.MP3, TagParser.parse(ByteArraySource(chance))?.properties?.codec)
    }

    @Test
    fun noiseIsNotAudio() {
        val noise = ByteArray(20_000) { (it * 31 + 7).toByte() }
        assertNull(TagParser.parse(ByteArraySource(noise)))
    }
}

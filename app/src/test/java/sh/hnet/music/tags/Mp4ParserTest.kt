package sh.hnet.music.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ParserTest {
    private fun box(type: String, body: ByteArray) = bytes { be32(8L + body.size); ascii(type); bytes(body) }
    private fun box(type: String, vararg children: ByteArray) = box(type, children.fold(ByteArray(0)) { a, b -> a + b })

    private fun data(type: Int, value: ByteArray) = box("data", bytes { be32(type.toLong()); be32(0); bytes(value) })
    private fun text(item: String, value: String) = box(item, data(1, value.toByteArray(Charsets.UTF_8)))

    @Test
    fun alacWithItunesMetadata() {
        val alacConfig = box("alac", bytes {
            be32(0) // version, flags
            be32(4096); u8(0); u8(24); u8(40); u8(10); u8(14); u8(2); be16(255); be32(0); be32(0); be32(96_000)
        })
        val sampleEntry = box("alac", bytes {
            zeros(6); be16(1) // reserved, data reference index
            be16(0); zeros(6); be16(2); be16(24); zeros(4); be32(0) // rate doesn't fit in 16.16
            bytes(alacConfig)
        })
        val stsd = box("stsd", bytes { be32(0); be32(1); bytes(sampleEntry) })
        val trak = box("trak", box("mdia",
            box("mdhd", bytes { be32(0); be32(0); be32(0); be32(96_000); be32(96_000L * 5); be32(0) }),
            box("hdlr", bytes { be32(0); be32(0); ascii("soun"); zeros(12); u8(0) }),
            box("minf", box("stbl", stsd)),
        ))
        val freeform = box("----",
            box("mean", bytes { be32(0); ascii("com.apple.iTunes") }),
            box("name", bytes { be32(0); ascii("replaygain_track_gain") }),
            data(1, "-3.10 dB".toByteArray()),
        )
        val ilst = box("ilst",
            text("©nam", "Utwór"),
            text("©ART", "Wykonawca"),
            text("aART", "Album Artist"),
            box("trkn", data(0, bytes { be16(0); be16(2); be16(9); be16(0) })),
            box("disk", data(0, bytes { be16(0); be16(1); be16(2) })),
            box("gnre", data(0, bytes { be16(18) })),
            box("cpil", data(21, byteArrayOf(1))),
            box("covr", data(13, ByteArray(500))),
            freeform,
        )
        val meta = box("meta", bytes { be32(0); bytes(box("hdlr", bytes { be32(0); be32(0); ascii("mdir"); zeros(12) })); bytes(ilst) })
        val moov = box("moov",
            box("mvhd", bytes { be32(0); be32(0); be32(0); be32(1000); be32(5000); zeros(80) }),
            trak,
            box("udta", meta),
        )
        val file = box("ftyp", bytes { ascii("M4A "); be32(0); ascii("M4A ") }) + moov + box("mdat", ByteArray(50_000))
        val (props, tags) = parse(file)
        assertEquals(AudioCodec.ALAC, props.codec)
        assertEquals(96_000, props.sampleRate)
        assertEquals(24, props.bitsPerSample)
        assertEquals(5000L, props.durationMs)
        assertEquals(80, props.bitrateKbps)
        assertEquals("Utwór", tags.title)
        assertEquals(listOf("Wykonawca"), tags.artists)
        assertEquals(listOf("Album Artist"), tags.albumArtists)
        assertEquals(2, tags.trackNumber)
        assertEquals(9, tags.trackTotal)
        assertEquals(2, tags.discTotal)
        assertEquals(listOf("Rock"), tags.genres)
        assertTrue(tags.compilation)
        assertTrue(tags.hasEmbeddedCover)
        assertEquals(-3.1f, tags.replayGain.trackGainDb)
    }
}

package sh.hnet.music.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverExtractorTest {
    private val front = "FRONT-IMAGE".toByteArray()
    private val back = "BACK-IMAGE".toByteArray()

    private fun flacPictureBody(type: Int, data: ByteArray) = bytes {
        be32(type.toLong()); be32(10); ascii("image/jpeg"); be32(0); be32(0); be32(0); be32(0); be32(0)
        be32(data.size.toLong()); bytes(data)
    }

    private fun flac(vararg blocks: Pair<Int, ByteArray>) = bytes {
        ascii("fLaC")
        val info = bytes { be16(4096); be16(4096); be24(0); be24(0); be64((44_100L shl 44) or (1L shl 41) or (15L shl 36) or 44_100L); zeros(16) }
        val all = listOf(0 to info) + blocks
        all.forEachIndexed { i, (type, body) -> u8(type or if (i == all.lastIndex) 0x80 else 0); be24(body.size); bytes(body) }
        zeros(100)
    }

    private fun extract(file: ByteArray) = CoverExtractor.extract(ByteArraySource(file))

    @Test
    fun flacPrefersFrontCover() {
        val file = flac(6 to flacPictureBody(4, back), 6 to flacPictureBody(3, front))
        assertArrayEquals(front, extract(file))
    }

    @Test
    fun flacFallsBackToFirstPicture() {
        assertArrayEquals(back, extract(flac(6 to flacPictureBody(4, back))))
    }

    @Test
    fun vorbisCommentPicture() {
        val base64 = java.util.Base64.getEncoder().encodeToString(flacPictureBody(3, front))
        val file = flac(4 to vorbisComments("TITLE=x", "METADATA_BLOCK_PICTURE=$base64"))
        assertArrayEquals(front, extract(file))
    }

    @Test
    fun id3ApicWithUtf16Description() {
        val apic = bytes {
            u8(1); ascii("image/png"); u8(0); u8(3)
            bytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); bytes("Okładka".toByteArray(Charsets.UTF_16LE)); u8(0); u8(0)
            bytes(front)
        }
        val frames = bytes { ascii("APIC"); be32(apic.size.toLong()); be16(0); bytes(apic) }
        val size = frames.size
        val tag = bytes {
            ascii("ID3"); u8(3); u8(0); u8(0)
            u8(size ushr 21 and 0x7F); u8(size ushr 14 and 0x7F); u8(size ushr 7 and 0x7F); u8(size and 0x7F)
            bytes(frames)
        }
        val frame = bytes { u8(0xFF); u8(0xFB); u8(0x90); u8(0); zeros(413) }
        assertArrayEquals(front, extract(tag + frame + frame + frame))
    }

    @Test
    fun noPicture() {
        assertNull(extract(flac(4 to vorbisComments("TITLE=x"))))
    }
}

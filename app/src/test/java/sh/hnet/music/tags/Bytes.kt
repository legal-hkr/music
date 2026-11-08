package sh.hnet.music.tags

import java.io.ByteArrayOutputStream

/** Builds test files byte by byte. */
class Bytes {
    private val out = ByteArrayOutputStream()
    val size get() = out.size()

    fun u8(v: Int) = apply { out.write(v) }
    fun be16(v: Int) = apply { u8(v ushr 8 and 0xFF); u8(v and 0xFF) }
    fun be24(v: Int) = apply { u8(v ushr 16 and 0xFF); be16(v and 0xFFFF) }
    fun be32(v: Long) = apply { be16((v ushr 16 and 0xFFFF).toInt()); be16((v and 0xFFFF).toInt()) }
    fun be64(v: Long) = apply { be32(v ushr 32); be32(v and 0xFFFFFFFFL) }
    fun le16(v: Int) = apply { u8(v and 0xFF); u8(v ushr 8 and 0xFF) }
    fun le32(v: Long) = apply { le16((v and 0xFFFF).toInt()); le16((v ushr 16 and 0xFFFF).toInt()) }
    fun le64(v: Long) = apply { le32(v and 0xFFFFFFFFL); le32(v ushr 32) }
    fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
    fun utf8(s: String) = apply { out.write(s.toByteArray(Charsets.UTF_8)) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun zeros(n: Int) = apply { out.write(ByteArray(n)) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

fun bytes(block: Bytes.() -> Unit): ByteArray = Bytes().apply(block).toByteArray()

fun parse(file: ByteArray): ParsedAudio = requireNotNull(TagParser.parseOrThrow(ByteArraySource(file)))

/** A Vorbis comment block (vendor, then KEY=value entries). */
fun vorbisComments(vararg entries: String): ByteArray = bytes {
    le32(4); ascii("test")
    le32(entries.size.toLong())
    for (e in entries) {
        val b = e.toByteArray(Charsets.UTF_8)
        le32(b.size.toLong()); bytes(b)
    }
}

package sh.hnet.music.tags

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** ID3v2.2, 2.3 and 2.4 at the start of a file (MP3, sometimes FLAC). */
internal object Id3v2 {
    private const val MAX_FRAME = 1 shl 20
    private const val MAX_UNSYNCED_TAG = 16 shl 20

    private val TEXT_FRAMES = mapOf(
        "TIT2" to "TITLE", "TT2" to "TITLE",
        "TPE1" to "ARTIST", "TP1" to "ARTIST",
        "TALB" to "ALBUM", "TAL" to "ALBUM",
        "TPE2" to "ALBUMARTIST", "TP2" to "ALBUMARTIST",
        "TRCK" to "TRACKNUMBER", "TRK" to "TRACKNUMBER",
        "TPOS" to "DISCNUMBER", "TPA" to "DISCNUMBER",
        "TDRC" to "DATE", "TYER" to "DATE", "TYE" to "DATE",
        "TCON" to "GENRE", "TCO" to "GENRE",
        "TCOM" to "COMPOSER", "TCM" to "COMPOSER",
        "TSOT" to "TITLESORT", "TST" to "TITLESORT",
        "TSOP" to "ARTISTSORT", "TSP" to "ARTISTSORT",
        "TSOA" to "ALBUMSORT", "TSA" to "ALBUMSORT",
        "TSO2" to "ALBUMARTISTSORT", "TS2" to "ALBUMARTISTSORT",
        "TCMP" to "COMPILATION", "TCP" to "COMPILATION",
    )
    private val USER_TEXT = setOf("TXXX", "TXX")
    private val PICTURES = setOf("APIC", "PIC")
    private val NUMBERED_GENRE = Regex("""^\((\d+)\)(.*)$""")

    /** Size of a tag at [offset] with its header and footer, or 0 when there's none. */
    fun tagSize(r: SourceReader, offset: Long): Long {
        val h = r.peek(offset, 10)
        if (h.size < 10 || !h.startsWith("ID3")) return 0
        val major = h[3].toInt()
        if (major !in 2..4 || (6..9).any { h[it].toInt() and 0x80 != 0 }) return 0
        val footer = major == 4 && h[5].toInt() and 0x10 != 0
        return 10 + synchsafe(h, 6) + if (footer) 10 else 0
    }

    fun read(r: SourceReader, offset: Long, into: TagBuilder) {
        val h = r.peek(offset, 10)
        val major = h[3].toInt()
        val flags = h[5].toInt() and 0xFF
        val size = synchsafe(h, 6)
        if (major == 2 && flags and 0x40 != 0) return // v2.2 compression: never defined
        val end = minOf(offset + 10 + size, r.size)
        if (major < 4 && flags and 0x80 != 0) {
            // v2.2/2.3 unsynchronise the whole tag, and frame sizes count the restored bytes.
            if (size > MAX_UNSYNCED_TAG) return
            r.seek(offset + 10)
            val restored = resync(r.readBytes((end - offset - 10).toInt()))
            readFrames(SourceReader(ByteArraySource(restored)), 0, restored.size.toLong(), major, flags, into)
        } else {
            readFrames(r, offset + 10, end, major, flags, into)
        }
    }

    private fun readFrames(r: SourceReader, start: Long, end: Long, major: Int, flags: Int, into: TagBuilder) {
        var pos = start
        if (major >= 3 && flags and 0x40 != 0) {
            r.seek(pos)
            pos += if (major == 3) 4 + r.u32be() else synchsafe(r.readBytes(4), 0)
        }
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        while (pos + headerLength <= end) {
            r.seek(pos)
            val idBytes = r.readBytes(idLength)
            if (!validId(idBytes)) break // padding or garbage
            val id = String(idBytes, Charsets.ISO_8859_1)
            val dataStart = pos + headerLength
            val size: Long
            val frameFlags: Int
            when (major) {
                2 -> { size = r.u24be().toLong(); frameFlags = 0 }
                3 -> { size = r.u32be(); frameFlags = r.u16be() }
                else -> {
                    val raw = r.u32be()
                    frameFlags = r.u16be()
                    size = v24FrameSize(r, raw, dataStart, end)
                }
            }
            if (dataStart + size > end) break
            frame(r, id, major, frameFlags, dataStart, size, into)
            pos = dataStart + size
        }
    }

    /** Some writers (old iTunes) put plain sizes into v2.4 tags; take whichever leads to a frame. */
    private fun v24FrameSize(r: SourceReader, raw: Long, dataStart: Long, end: Long): Long {
        if (raw and 0x80808080L != 0L) return raw
        val decoded = synchsafe(raw)
        if (decoded == raw || frameOrEndAt(r, dataStart + decoded, end)) return decoded
        return if (frameOrEndAt(r, dataStart + raw, end)) raw else decoded
    }

    private fun frameOrEndAt(r: SourceReader, pos: Long, end: Long): Boolean {
        if (pos > end) return false
        if (pos + 10 > end) return true
        val id = r.peek(pos, 4)
        return id.all { it.toInt() == 0 } || validId(id)
    }

    private fun validId(id: ByteArray) = id.all { it in 'A'.code.toByte()..'Z'.code.toByte() || it in '0'.code.toByte()..'9'.code.toByte() }

    private fun frame(r: SourceReader, id: String, major: Int, flags: Int, dataStart: Long, size: Long, into: TagBuilder) {
        if (id in PICTURES) {
            into.hasCover = true
            // The picture type sits inside the frame, after the encoding and the MIME type
            val sink = into.pictures ?: return
            if (size > TagBuilder.MAX_PICTURE) return
            val data = frameData(r, major, flags, dataStart, size, TagBuilder.MAX_PICTURE.toInt()) ?: return
            picture(id, data)?.let { (type, bytes) -> sink.picture(type, bytes) }
            return
        }
        val key = TEXT_FRAMES[id]
        if (key == null && id !in USER_TEXT) return
        if (size > MAX_FRAME || size < 1) return
        val data = frameData(r, major, flags, dataStart, size, MAX_FRAME) ?: return
        if (data.isEmpty()) return
        val values = strings(data[0].toInt(), data, 1)
        if (key == null) {
            // TXXX: description, then the values
            val description = values.firstOrNull() ?: return
            values.drop(1).forEach { into.add(description, it) }
        } else if (key == "GENRE") {
            values.forEach { into.add(key, genre(it)) }
        } else {
            values.forEach { into.add(key, it) }
        }
    }

    /** A frame's content with its format flags undone (extra header bytes, unsynchronisation, compression). */
    private fun frameData(r: SourceReader, major: Int, flags: Int, dataStart: Long, size: Long, limit: Int): ByteArray? {
        if (size < 1) return null
        r.seek(dataStart)
        var data = r.readBytes(size.toInt())
        var skip = 0
        var compressed = false
        when (major) {
            3 -> {
                if (flags and 0x0040 != 0) return null // encrypted
                if (flags and 0x0080 != 0) { compressed = true; skip += 4 }
                if (flags and 0x0020 != 0) skip += 1
            }
            4 -> {
                if (flags and 0x0004 != 0) return null // encrypted
                if (flags and 0x0040 != 0) skip += 1
                if (flags and 0x0001 != 0) skip += 4
                compressed = flags and 0x0008 != 0
            }
        }
        if (skip >= data.size) return null
        data = data.copyOfRange(skip, data.size)
        if (major == 4 && flags and 0x0002 != 0) data = resync(data)
        if (compressed) data = inflate(data, limit) ?: return null
        return data
    }

    /**
     * APIC: encoding, MIME type (Latin-1, terminated), picture type, description (in the encoding,
     * terminated), image. v2.2 PIC: encoding, three-letter format, type, description, image.
     */
    private fun picture(id: String, data: ByteArray): Pair<Int, ByteArray>? {
        if (data.size < 4) return null
        val encoding = data[0].toInt()
        var i = if (id == "PIC") {
            4
        } else {
            val end = (1 until data.size).firstOrNull { data[it].toInt() == 0 } ?: return null
            end + 1
        }
        if (i >= data.size) return null
        val type = data[i].toInt() and 0xFF
        i++
        // Description, terminated by one zero byte, or two at an even offset for UTF-16
        val wide = encoding == 1 || encoding == 2
        while (i < data.size) {
            if (wide) {
                if (i + 1 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0) { i += 2; break }
                i += 2
            } else {
                if (data[i].toInt() == 0) { i += 1; break }
                i += 1
            }
        }
        if (i >= data.size) return null
        return type to data.copyOfRange(i, data.size)
    }

    /** "(17)", "17", "(17)Rock" or a name. */
    private fun genre(value: String): String {
        value.toIntOrNull()?.let { return Id3Genres.name(it) ?: value }
        val numbered = NUMBERED_GENRE.find(value) ?: return value
        val refined = numbered.groupValues[2].trim()
        if (refined.isNotEmpty()) return refined
        return Id3Genres.name(numbered.groupValues[1].toInt()) ?: value
    }

    /** Null-separated strings in the frame's encoding; v2.4 uses the separator for multiple values. */
    fun strings(encoding: Int, data: ByteArray, from: Int): List<String> {
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val wide = encoding == 1 || encoding == 2
        val result = mutableListOf<String>()
        var start = from
        var i = from
        val step = if (wide) 2 else 1
        while (i + step <= data.size) {
            val terminator = data[i].toInt() == 0 && (!wide || data[i + 1].toInt() == 0)
            if (terminator) {
                result += String(data, start, i - start, charset)
                start = i + step
            }
            i += step
        }
        if (start < data.size) result += String(data, start, data.size - start, charset)
        return result.map { it.trim() }.dropLastWhile { it.isEmpty() }
    }

    private fun inflate(data: ByteArray, limit: Int): ByteArray? {
        if (data.isEmpty()) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /** Undoes unsynchronisation: FF 00 becomes FF. */
    private fun resync(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i].toInt() and 0xFF == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun synchsafe(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, k -> (acc shl 7) or (bytes[at + k].toLong() and 0x7F) }

    private fun synchsafe(value: Long): Long =
        ((value shr 24 and 0x7F) shl 21) or ((value shr 16 and 0x7F) shl 14) or
            ((value shr 8 and 0x7F) shl 7) or (value and 0x7F)
}

/** ID3v1 at the end of an MP3: fixed fields, the last resort for files without other tags. */
internal object Id3v1 {
    const val SIZE = 128L

    fun isAt(r: SourceReader, pos: Long) = pos >= 0 && r.peek(pos, 3).startsWith("TAG")

    fun read(r: SourceReader, pos: Long, into: TagBuilder) {
        r.seek(pos + 3)
        into.add("TITLE", field(r.readBytes(30)))
        into.add("ARTIST", field(r.readBytes(30)))
        into.add("ALBUM", field(r.readBytes(30)))
        into.add("DATE", field(r.readBytes(4)))
        val comment = r.readBytes(30)
        if (comment[28].toInt() == 0 && comment[29].toInt() != 0) {
            into.add("TRACKNUMBER", (comment[29].toInt() and 0xFF).toString())
        }
        Id3Genres.name(r.u8())?.let { into.add("GENRE", it) }
    }

    private fun field(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.ISO_8859_1)
    }
}

/** APEv2 at the end of a file, before ID3v1; mp3gain keeps its ReplayGain values there. */
internal object ApeTag {
    private const val MAX_VALUE = 1 shl 20

    /** Reads a tag ending at [end]; returns where it starts, or null when there's none. */
    fun readEndingAt(r: SourceReader, end: Long, into: TagBuilder): Long? {
        if (end < 32) return null
        val footer = r.peek(end - 32, 32)
        if (!footer.startsWith("APETAGEX")) return null
        val tagSize = le32(footer, 12)
        val count = le32(footer, 16)
        val hasHeader = le32(footer, 20) and 0x80000000L != 0L
        val itemsStart = end - tagSize
        if (itemsStart < 0 || tagSize < 32) return null
        var pos = itemsStart
        for (i in 0 until minOf(count, 10_000L)) {
            if (pos + 9 > end - 32) break
            r.seek(pos)
            val valueSize = r.u32le()
            val flags = r.u32le()
            val keyBytes = ByteArrayOutputStream()
            while (true) {
                val b = r.u8()
                if (b == 0 || keyBytes.size() > 255) break
                keyBytes.write(b)
            }
            val key = keyBytes.toString(Charsets.US_ASCII.name())
            val valueStart = r.position
            if (valueStart + valueSize > end - 32) break
            val type = (flags shr 1 and 3).toInt()
            when {
                key.uppercase().startsWith("COVER ART") -> into.picture(if (key.uppercase() == "COVER ART (FRONT)") 3 else 0, valueSize) {
                    // Binary item: the file name, a zero byte, then the image
                    val value = r.readBytes(valueSize.toInt())
                    val zero = value.indexOf(0)
                    if (zero < 0) null else value.copyOfRange(zero + 1, value.size)
                }
                type == 0 && valueSize <= MAX_VALUE -> String(r.readBytes(valueSize.toInt()), Charsets.UTF_8)
                    .split('\u0000').forEach { into.add(key, it) }
            }
            pos = valueStart + valueSize
        }
        return itemsStart - if (hasHeader) 32 else 0
    }

    private fun le32(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, k -> acc or ((bytes[at + k].toLong() and 0xFF) shl (8 * k)) }
}

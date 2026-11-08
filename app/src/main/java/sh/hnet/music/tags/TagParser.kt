package sh.hnet.music.tags

import java.io.IOException

/**
 * Reads tags and stream properties from the start (and for MP3 the end) of an audio file,
 * never the audio itself. The format is recognised by content, not by the file name.
 */
object TagParser {
    /** Null when the file isn't a supported audio format or is damaged. */
    fun parse(source: RandomAccessSource, pictures: PictureSink? = null): ParsedAudio? = try {
        parseOrThrow(source, pictures)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // Malformed sizes end up as index or argument errors; one bad file must not stop a scan.
        null
    }

    internal fun parseOrThrow(source: RandomAccessSource, pictures: PictureSink? = null): ParsedAudio? {
        val r = SourceReader(source)
        val id3 = TagBuilder(pictures)
        var start = 0L
        while (true) {
            val size = Id3v2.tagSize(r, start)
            if (size == 0L) break
            Id3v2.read(r, start, id3)
            start += size
        }
        val magic = r.peek(start, 8)
        return when {
            magic.startsWith("fLaC") -> FlacParser.parse(r, start, pictures)?.withFallback(id3)
            magic.startsWith("OggS") -> OggParser.parse(r, start, pictures)?.withFallback(id3)
            start == 0L && magic.startsWith("ftyp", 4) -> Mp4Parser.parse(r, pictures)
            else -> Mp3Parser.parse(r, start, id3, pictures)
        }
    }

    private fun ParsedAudio.withFallback(id3: TagBuilder) = copy(tags = tags.orElse(id3.build()))
}

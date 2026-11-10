package sh.hnet.music.tags

import java.io.EOFException

/**
 * Where the 1-bit audio of a DSD file lies and how it is laid out: what the library shows and
 * all the player's extractor needs to read it.
 */
class DsdStream(
    val channels: Int,
    /** Bits per second of one channel: 2 822 400 for DSD64. */
    val sampleRate: Int,
    /** Bits of one channel. */
    val sampleCount: Long,
    val dataOffset: Long,
    val dataBytes: Long,
    /** DSF: bytes of one channel in a row (4096); 0 when the channels alternate byte by byte (DFF). */
    val blockSize: Int,
    /** The first bit in time is the lowest of each byte (DSF with 1 "bit per sample"). */
    val lsbFirst: Boolean,
    /** DSF: where the ID3v2 tag starts; 0 without one. */
    val metadataOffset: Long = 0,
) {
    val durationMs: Long get() = sampleCount * 1000 / sampleRate
}

/**
 * The headers of the two DSD file formats, read in one pass from the first byte up to the audio,
 * so the scanner (a file) and the player's extractor (a stream) share them.
 *
 * DSF: Sony's "DSF File Format Specification" 1.01 – little-endian, the channels in blocks of 4096
 * bytes. DFF: Philips' "DSDIFF" 1.5 – big-endian IFF chunks, the channels byte by byte.
 */
object DsdFormat {
    private const val MAX_CHANNELS = 6
    private const val MAX_BLOCK = 1 shl 16
    private const val MAX_CHUNKS = 64
    private const val MAX_PROP = 65_536L

    /** The 1-bit rates in use: DSD64 to DSD1024 of both families (64 × 44.1 kHz and 64 × 48 kHz, doubled). */
    private fun validRate(rate: Long) = (0..4).any { rate == 2_822_400L shl it || rate == 3_072_000L shl it }

    fun isDsf(magic: ByteArray) = magic.startsWith("DSD ")

    /** [magic]: the file's first 16 bytes ("FRM8", the form's size, "DSD "). */
    fun isDff(magic: ByteArray) = magic.startsWith("FRM8") && magic.startsWith("DSD ", 12)

    /** @throws TagFormatException when the header isn't a DSF file's or describes something we don't read */
    fun readDsf(input: ByteInput): DsdStream {
        if (input.latin1(4) != "DSD " || input.u64le() != 28L) throw TagFormatException("Not DSF")
        input.skip(8) // the file's size
        val metadata = input.u64le()
        if (input.latin1(4) != "fmt ") throw TagFormatException("No fmt chunk")
        val fmtSize = input.u64le()
        if (fmtSize < 52 || fmtSize > 4096) throw TagFormatException("Bad fmt chunk")
        val version = input.u32le()
        val formatId = input.u32le() // 0: DSD raw, the only one defined
        input.skip(4) // channel type (the layout), which the channel count implies
        val channels = input.u32le()
        val rate = input.u32le()
        val bits = input.u32le() // 1: the lowest bit first, 8: the highest first
        val samples = input.u64le()
        val block = input.u32le()
        input.skip(4 + (fmtSize - 52)) // reserved, and whatever a later version adds
        if (version != 1L || formatId != 0L) throw TagFormatException("Unknown DSF format")
        if (channels !in 1..MAX_CHANNELS || !validRate(rate) || (bits != 1L && bits != 8L)) throw TagFormatException("Bad DSF format")
        if (block !in 1..MAX_BLOCK || samples < 0) throw TagFormatException("Bad DSF block")
        if (input.latin1(4) != "data") throw TagFormatException("No data chunk")
        val dataBytes = input.u64le() - 12
        if (dataBytes < 0) throw TagFormatException("Bad data chunk")
        return DsdStream(
            channels = channels.toInt(),
            sampleRate = rate.toInt(),
            // The last block is filled up with padding: never more samples than the chunk holds
            sampleCount = minOf(samples, dataBytes / channels * 8),
            dataOffset = 28 + fmtSize + 12,
            dataBytes = dataBytes,
            blockSize = block.toInt(),
            lsbFirst = bits == 1L,
            metadataOffset = metadata,
        )
    }

    /**
     * @return null for DST-compressed audio (SACD's lossless packing), which we don't decode
     * @throws TagFormatException when it isn't a DSDIFF file
     */
    fun readDff(input: ByteInput): DsdStream? {
        if (input.latin1(4) != "FRM8") throw TagFormatException("Not DSDIFF")
        input.skip(8)
        if (input.latin1(4) != "DSD ") throw TagFormatException("Not DSDIFF")
        var offset = 16L
        var rate = 0L
        var channels = 0
        var compression = ""
        repeat(MAX_CHUNKS) {
            val id = input.latin1(4)
            val size = input.u64be()
            offset += 12
            if (size < 0) throw TagFormatException("Bad chunk")
            when (id) {
                "DSD " -> {
                    if (compression != "DSD " || channels !in 1..MAX_CHANNELS || !validRate(rate)) throw TagFormatException("Bad DSDIFF properties")
                    return DsdStream(
                        channels = channels,
                        sampleRate = rate.toInt(),
                        sampleCount = size / channels * 8,
                        dataOffset = offset,
                        dataBytes = size,
                        blockSize = 0,
                        lsbFirst = false,
                    )
                }
                "DST " -> return null
                "PROP" -> {
                    if (size < 4 || size > MAX_PROP) throw TagFormatException("Bad PROP chunk")
                    // Sound properties: among them the sample rate, the channels and the compression
                    val prop = ChunkInput(input.readBytes(size.toInt()))
                    if (prop.latin1(4) == "SND ") {
                        while (prop.remaining >= 12) {
                            val propId = prop.latin1(4)
                            val propSize = prop.u64be()
                            if (propSize < 0 || propSize > prop.remaining) break
                            val next = prop.position + propSize.toInt() + (propSize and 1).toInt()
                            when (propId) {
                                "FS  " -> if (propSize >= 4) rate = prop.u32be()
                                "CHNL" -> if (propSize >= 2) channels = prop.u16be()
                                "CMPR" -> if (propSize >= 4) compression = prop.latin1(4)
                            }
                            prop.position = minOf(next, prop.size)
                        }
                    }
                    input.skip(size and 1)
                    offset += size + (size and 1)
                }
                else -> {
                    val padded = size + (size and 1)
                    input.skip(padded)
                    offset += padded
                }
            }
        }
        throw TagFormatException("No sound data")
    }

    /** A chunk's bytes, read one after another. */
    private class ChunkInput(private val bytes: ByteArray) : ByteInput {
        var position = 0
        val size: Int get() = bytes.size
        val remaining: Int get() = bytes.size - position

        override fun readByte(): Int {
            if (position >= bytes.size) throw EOFException()
            return bytes[position++].toInt() and 0xFF
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            if (length > remaining) throw EOFException()
            System.arraycopy(bytes, position, buffer, offset, length)
            position += length
        }

        override fun skip(count: Long) {
            if (count < 0 || count > remaining) throw EOFException()
            position += count.toInt()
        }
    }
}

/**
 * DSD files for the library. DSF carries an ID3v2 tag at its end; DSDIFF has no tags of its own
 * beyond an artist and a title (DIIN), but taggers add an "ID3 " chunk, which comes first.
 */
internal object DsdParser {
    private const val MAX_CHUNKS = 64
    private const val MAX_TEXT = 4096L

    fun parseDsf(r: SourceReader, pictures: PictureSink? = null): ParsedAudio {
        r.seek(0)
        val stream = DsdFormat.readDsf(r)
        val tags = TagBuilder(pictures)
        val at = stream.metadataOffset
        if (at > 0 && at < r.size && Id3v2.tagSize(r, at) > 0) Id3v2.read(r, at, tags)
        return ParsedAudio(properties(stream), tags.build())
    }

    /** Null for DST-compressed audio. */
    fun parseDff(r: SourceReader, pictures: PictureSink? = null): ParsedAudio? {
        r.seek(0)
        val stream = DsdFormat.readDff(r) ?: return null
        val id3 = TagBuilder(pictures)
        val info = TagBuilder()
        // What follows the audio: edited master information, an ID3 tag
        var pos = stream.dataOffset + stream.dataBytes + (stream.dataBytes and 1)
        var chunks = 0
        while (pos + 12 <= r.size && chunks++ < MAX_CHUNKS) {
            r.seek(pos)
            val id = r.latin1(4)
            val size = r.u64be()
            val body = pos + 12
            if (size < 0 || body + size > r.size) break
            when (id) {
                "ID3 " -> if (Id3v2.tagSize(r, body) > 0) Id3v2.read(r, body, id3)
                "DIIN" -> masterInformation(r, body, body + size, info)
            }
            pos = body + size + (size and 1)
        }
        return ParsedAudio(properties(stream), id3.build().orElse(info.build()))
    }

    /** DIIN: the artist (DIAR) and the title (DITI), each a counted text. */
    private fun masterInformation(r: SourceReader, start: Long, end: Long, into: TagBuilder) {
        var pos = start
        var chunks = 0
        while (pos + 12 <= end && chunks++ < MAX_CHUNKS) {
            r.seek(pos)
            val id = r.latin1(4)
            val size = r.u64be()
            val body = pos + 12
            if (size < 0 || body + size > end) return
            val key = when (id) {
                "DIAR" -> "ARTIST"
                "DITI" -> "TITLE"
                else -> null
            }
            if (key != null && size >= 4) {
                val count = r.u32be()
                if (count in 1..minOf(size - 4, MAX_TEXT)) into.add(key, r.latin1(count.toInt()))
            }
            pos = body + size + (size and 1)
        }
    }

    private fun properties(stream: DsdStream) = AudioProperties(
        codec = AudioCodec.DSD,
        sampleRate = stream.sampleRate,
        channels = stream.channels,
        bitsPerSample = 1,
        durationMs = stream.durationMs,
        bitrateKbps = (stream.sampleRate.toLong() * stream.channels / 1000).toInt(),
    )
}

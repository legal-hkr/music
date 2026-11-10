package sh.hnet.music.tags

/** Native FLAC: "fLaC", then metadata blocks (STREAMINFO, VORBIS_COMMENT, PICTURE, …). */
internal object FlacParser {
    private const val STREAMINFO = 0
    private const val VORBIS_COMMENT = 4
    private const val PICTURE = 6

    fun parse(r: SourceReader, start: Long, pictures: PictureSink? = null): ParsedAudio? {
        r.seek(start + 4)
        val tags = TagBuilder(pictures)
        var info: StreamInfo? = null
        while (true) {
            val header = r.u8()
            val type = header and 0x7F
            val length = r.u24be()
            val blockStart = r.position
            if (type == 127 || blockStart + length > r.size) throw TagFormatException("Bad FLAC block")
            when (type) {
                STREAMINFO -> info = streamInfo(r)
                VORBIS_COMMENT -> VorbisComment.read(r, tags)
                PICTURE -> tags.picture(r.peek(blockStart, 4).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) }, length.toLong()) {
                    pictureBlock(r)
                }
            }
            r.seek(blockStart + length)
            if (header and 0x80 != 0) break
        }
        val streamInfo = info ?: return null
        val durationMs = if (streamInfo.sampleRate > 0) streamInfo.totalSamples * 1000 / streamInfo.sampleRate else 0
        return ParsedAudio(
            AudioProperties(
                codec = AudioCodec.FLAC,
                sampleRate = streamInfo.sampleRate,
                channels = streamInfo.channels,
                bitsPerSample = streamInfo.bitsPerSample,
                durationMs = durationMs,
                bitrateKbps = bitrate(r.size - r.position, durationMs),
            ),
            tags.build(),
        )
    }

    class StreamInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val totalSamples: Long)

    /** The 34-byte STREAMINFO body; also inside MP4's dfLa box. */
    fun streamInfo(input: ByteInput): StreamInfo {
        input.skip(10) // block sizes, frame sizes
        val b = input.readBytes(8).map { it.toInt() and 0xFF }
        val sampleRate = (b[0] shl 12) or (b[1] shl 4) or (b[2] shr 4)
        val channels = ((b[2] shr 1) and 0x07) + 1
        val bitsPerSample = (((b[2] and 0x01) shl 4) or (b[3] shr 4)) + 1
        val totalSamples = ((b[3] and 0x0F).toLong() shl 32) or
            (b[4].toLong() shl 24) or (b[5].toLong() shl 16) or (b[6].toLong() shl 8) or b[7].toLong()
        return StreamInfo(sampleRate, channels, bitsPerSample, totalSamples)
    }
}

/**
 * The image inside a FLAC picture block (also Ogg's METADATA_BLOCK_PICTURE): type, MIME type,
 * description, four dimensions, then the data.
 */
internal fun pictureBlock(input: ByteInput): ByteArray? {
    input.skip(4) // type, read by the caller
    input.skip(input.u32be()) // MIME type
    input.skip(input.u32be()) // description
    input.skip(16) // width, height, depth, colors
    val length = input.u32be()
    if (length > TagBuilder.MAX_PICTURE) return null
    return input.readBytes(length.toInt())
}

/** Average bitrate of [bytes] of audio lasting [durationMs]. */
internal fun bitrate(bytes: Long, durationMs: Long): Int? =
    if (durationMs > 0 && bytes > 0) (bytes * 8 / durationMs).toInt() else null

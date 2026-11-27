package sh.hnet.music.tags

/** MPEG audio (layers I–III): tags at both ends, duration from Xing/Info, VBRI or the bitrate. */
internal object Mp3Parser {
    private const val SYNC_SEARCH = 64 * 1024

    fun parse(r: SourceReader, audioStart: Long, id3: TagBuilder, pictures: PictureSink? = null): ParsedAudio? {
        var end = r.size
        val v1 = TagBuilder()
        if (Id3v1.isAt(r, end - Id3v1.SIZE)) {
            Id3v1.read(r, end - Id3v1.SIZE, v1)
            end -= Id3v1.SIZE
        }
        val ape = TagBuilder(pictures)
        ApeTag.readEndingAt(r, end, ape)?.let { end = it }
        val properties = audio(r, audioStart, end) ?: return null
        return ParsedAudio(properties, id3.build().orElse(ape.build()).orElse(v1.build()))
    }

    private fun audio(r: SourceReader, start: Long, end: Long): AudioProperties? {
        val limit = minOf(end, start + SYNC_SEARCH)
        var pos = start
        while (pos + 4 <= limit) {
            r.seek(pos)
            val frame = MpegFrame.parse(r.u32be())
            if (frame != null && confirmed(r, frame, pos + frame.length, end)) return describe(r, pos, frame, end)
            pos++
        }
        return null
    }

    /**
     * A sync word can occur by chance; the next frame must follow with the same layout. Layers I and II need three
     * more, as Media3's extractor asks: such a file may go to the skipped ones for want of a decoder (GH #8), so a
     * chance sync in junk mustn't make a playable MP3 look like one.
     */
    private fun confirmed(r: SourceReader, frame: MpegFrame, next: Long, end: Long): Boolean {
        var at = next
        repeat(if (frame.layer == 3) 1 else 3) {
            if (at + 4 > end) return true
            r.seek(at)
            val following = MpegFrame.parse(r.u32be()) ?: return false
            if (following.layer != frame.layer || following.sampleRate != frame.sampleRate) return false
            at += following.length
        }
        return true
    }

    private fun describe(r: SourceReader, pos: Long, frame: MpegFrame, end: Long): AudioProperties {
        val sideInfo = if (frame.mpeg1) (if (frame.channels == 1) 17 else 32) else (if (frame.channels == 1) 9 else 17)
        var frames: Long? = null
        var bytes: Long? = null
        var delay = 0
        var padding = 0
        r.seek(pos + 4 + sideInfo)
        val xing = r.latin1(4)
        if (xing == "Xing" || xing == "Info") {
            val flags = r.u32be()
            if (flags and 1L != 0L) frames = r.u32be()
            if (flags and 2L != 0L) bytes = r.u32be()
            if (flags and 4L != 0L) r.skip(100)
            if (flags and 8L != 0L) r.skip(4)
            if (r.position + 24 <= end) {
                // LAME tag: encoder name (9), then delay and padding at byte 21, 12 bits each
                val encoder = r.latin1(9)
                if (encoder.startsWith("LAME") || encoder.startsWith("Lavc") || encoder.startsWith("Lavf")) {
                    r.skip(12)
                    val b = r.readBytes(3).map { it.toInt() and 0xFF }
                    delay = (b[0] shl 4) or (b[1] shr 4)
                    padding = ((b[1] and 0x0F) shl 8) or b[2]
                }
            }
        } else {
            r.seek(pos + 4 + 32)
            if (r.latin1(4) == "VBRI") {
                r.skip(6) // version, delay, quality
                bytes = r.u32be()
                frames = r.u32be()
            }
        }
        val audioBytes = bytes ?: (end - pos)
        val durationMs = if (frames != null) {
            val samples = (frames * frame.samplesPerFrame - delay - padding).coerceAtLeast(0)
            samples * 1000 / frame.sampleRate
        } else {
            (end - pos) * 8 / frame.bitrateKbps
        }
        return AudioProperties(
            codec = when (frame.layer) { 1 -> AudioCodec.MP1; 2 -> AudioCodec.MP2; else -> AudioCodec.MP3 },
            sampleRate = frame.sampleRate,
            channels = frame.channels,
            bitsPerSample = null,
            durationMs = durationMs,
            bitrateKbps = if (frames != null) bitrate(audioBytes, durationMs) else frame.bitrateKbps,
        )
    }
}

internal class MpegFrame(
    val mpeg1: Boolean,
    val layer: Int,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val channels: Int,
    val samplesPerFrame: Int,
    val length: Int,
) {
    companion object {
        private val V1 = arrayOf(
            intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
            intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
            intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
        )
        private val V2_L1 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        private val V2_L23 = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val RATES = intArrayOf(44_100, 48_000, 32_000)

        fun parse(header: Long): MpegFrame? {
            val h = header.toInt()
            if (h ushr 21 and 0x7FF != 0x7FF) return null
            val version = h ushr 19 and 3 // 0: MPEG 2.5, 1: reserved, 2: MPEG 2, 3: MPEG 1
            val layerBits = h ushr 17 and 3
            val bitrateIndex = h ushr 12 and 0xF
            val rateIndex = h ushr 10 and 3
            if (version == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
            val layer = 4 - layerBits
            val mpeg1 = version == 3
            val sampleRate = RATES[rateIndex] shr when (version) { 3 -> 0; 2 -> 1; else -> 2 }
            val bitrate = when {
                mpeg1 -> V1[layer - 1][bitrateIndex - 1]
                layer == 1 -> V2_L1[bitrateIndex - 1]
                else -> V2_L23[bitrateIndex - 1]
            }
            val padding = h ushr 9 and 1
            val channels = if (h ushr 6 and 3 == 3) 1 else 2
            val samplesPerFrame = when {
                layer == 1 -> 384
                layer == 2 || mpeg1 -> 1152
                else -> 576
            }
            val length = if (layer == 1) {
                (12 * bitrate * 1000 / sampleRate + padding) * 4
            } else {
                samplesPerFrame / 8 * bitrate * 1000 / sampleRate + padding
            }
            return MpegFrame(mpeg1, layer, bitrate, sampleRate, channels, samplesPerFrame, length)
        }
    }
}

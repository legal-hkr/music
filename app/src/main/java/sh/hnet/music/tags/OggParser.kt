package sh.hnet.music.tags

import java.io.EOFException

/** Ogg Vorbis and Opus: identification and comment packets, duration from the last page. */
internal object OggParser {
    private const val OPUS_RATE = 48_000

    fun parse(r: SourceReader, start: Long): ParsedAudio? {
        val packets = OggPacketInput(r, start)
        packets.startPacket()
        val ident = packets.readBytes(8)
        val tags = TagBuilder()
        val codec: AudioCodec
        val sampleRate: Int
        val channels: Int
        var preSkip = 0
        var outputGain: Float? = null
        when {
            ident.startsWith("\u0001vorbis") -> {
                codec = AudioCodec.VORBIS
                packets.skip(3) // rest of the 4-byte version (its first byte was read with the signature)
                channels = packets.u8()
                sampleRate = packets.u32le().toInt()
                packets.startPacket()
                if (!packets.readBytes(7).startsWith("\u0003vorbis")) return null
                VorbisComment.read(packets, tags)
            }
            ident.startsWith("OpusHead") -> {
                codec = AudioCodec.OPUS
                packets.skip(1) // version
                channels = packets.u8()
                preSkip = packets.u16le()
                packets.skip(4) // input sample rate: informational, Opus always decodes at 48 kHz
                outputGain = packets.i16le() / 256f
                sampleRate = OPUS_RATE
                packets.startPacket()
                if (!packets.readBytes(8).startsWith("OpusTags")) return null
                VorbisComment.read(packets, tags)
            }
            else -> return null
        }
        val granule = lastGranule(r, packets.serial)
        val samples = (granule ?: 0) - preSkip
        val durationMs = if (sampleRate > 0 && samples > 0) samples * 1000 / sampleRate else 0
        return ParsedAudio(
            AudioProperties(
                codec = codec,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = null,
                durationMs = durationMs,
                bitrateKbps = bitrate(r.size - start, durationMs),
                opusOutputGainDb = outputGain?.takeIf { it != 0f },
            ),
            tags.build(),
        )
    }

    /** Granule position of the stream's last page, read backwards from the end of the file. */
    private fun lastGranule(r: SourceReader, serial: Long): Long? {
        var chunk = 64L * 1024
        while (true) {
            val from = maxOf(0L, r.size - chunk)
            val bytes = r.peek(from, (r.size - from).toInt())
            var i = bytes.size - 27
            while (i >= 0) {
                if (bytes.startsWith("OggS", i)) {
                    val granule = le64(bytes, i + 6)
                    val pageSerial = le64(bytes, i + 14) and 0xFFFFFFFFL
                    if (pageSerial == serial && granule != -1L) return granule
                }
                i--
            }
            if (from == 0L || chunk >= 4L * 1024 * 1024) return null
            chunk *= 4
        }
    }

    private fun le64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (k in 7 downTo 0) value = (value shl 8) or (bytes[at + k].toLong() and 0xFF)
        return value
    }
}

/**
 * The packets of one logical Ogg stream (the first page's serial), read across page
 * boundaries without buffering a packet, so a comment packet with a large picture is skipped.
 */
internal class OggPacketInput(private val r: SourceReader, private var nextPage: Long) : ByteInput {
    var serial = -1L
        private set
    private val lacing = IntArray(255)
    private var segmentCount = 0
    private var segmentIndex = 0
    private var segmentRemaining = 0
    private var segmentEndsPacket = false
    private var dataPosition = 0L
    private var started = false

    private fun loadPage(): Boolean {
        while (true) {
            if (nextPage + 27 > r.size) return false
            r.seek(nextPage)
            if (!r.readBytes(4).startsWith("OggS")) throw TagFormatException("Lost Ogg page sync")
            r.skip(2 + 8) // version, header type, granule
            val pageSerial = r.u32le()
            r.skip(8) // sequence, checksum
            val count = r.u8()
            var bodyLength = 0L
            for (i in 0 until count) {
                lacing[i] = r.u8()
                bodyLength += lacing[i]
            }
            val dataStart = r.position
            nextPage = dataStart + bodyLength
            if (serial == -1L) serial = pageSerial
            if (pageSerial != serial) continue
            segmentCount = count
            segmentIndex = 0
            dataPosition = dataStart
            return true
        }
    }

    private fun nextSegment() {
        while (segmentIndex >= segmentCount) if (!loadPage()) throw EOFException()
        segmentRemaining = lacing[segmentIndex++]
        segmentEndsPacket = segmentRemaining < 255
    }

    /** Moves to the start of the next packet, skipping what's left of the current one. */
    fun startPacket() {
        if (started) {
            while (true) {
                dataPosition += segmentRemaining
                segmentRemaining = 0
                if (segmentEndsPacket) break
                nextSegment()
            }
        }
        started = true
        nextSegment()
    }

    private inline fun consume(count: Long, action: (position: Long, length: Int) -> Unit) {
        var left = count
        while (left > 0) {
            if (segmentRemaining == 0) {
                if (segmentEndsPacket) throw EOFException("End of packet")
                nextSegment()
                continue
            }
            val n = minOf(left, segmentRemaining.toLong()).toInt()
            action(dataPosition, n)
            dataPosition += n
            segmentRemaining -= n
            left -= n
        }
    }

    override fun readByte(): Int {
        val one = ByteArray(1)
        readFully(one)
        return one[0].toInt() and 0xFF
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        consume(length.toLong()) { position, n ->
            r.seek(position)
            r.readFully(buffer, offset + done, n)
            done += n
        }
    }

    override fun skip(count: Long) = consume(count) { _, _ -> }
}

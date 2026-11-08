package sh.hnet.music.tags

/** MP4/M4A (AAC, ALAC, FLAC or Opus in MP4): the first sound track and iTunes-style metadata. */
internal object Mp4Parser {
    private const val MAX_VALUE = 1 shl 20

    private val TEXT_ITEMS = mapOf(
        "©nam" to "TITLE",
        "©ART" to "ARTIST",
        "©alb" to "ALBUM",
        "aART" to "ALBUMARTIST",
        "©day" to "DATE",
        "©gen" to "GENRE",
        "©wrt" to "COMPOSER",
        "sonm" to "TITLESORT",
        "soar" to "ARTISTSORT",
        "soal" to "ALBUMSORT",
        "soaa" to "ALBUMARTISTSORT",
    )

    private class Track {
        var handler: String? = null
        var timescale = 0L
        var duration = 0L
        var codec = AudioCodec.UNKNOWN
        var sampleRate = 0
        var channels = 0
        var bitsPerSample: Int? = null
    }

    private class State(pictures: PictureSink?) {
        var movieTimescale = 0L
        var movieDuration = 0L
        var audio: Track? = null
        var mdatBytes = 0L
        val tags = TagBuilder(pictures)
    }

    fun parse(r: SourceReader, pictures: PictureSink? = null): ParsedAudio? {
        val state = State(pictures)
        boxes(r, 0, r.size) { type, start, end ->
            when (type) {
                "moov" -> moov(r, start, end, state)
                "mdat" -> state.mdatBytes += end - start
            }
        }
        val track = state.audio ?: return null
        val durationMs = when {
            track.timescale > 0 && track.duration > 0 -> track.duration * 1000 / track.timescale
            state.movieTimescale > 0 -> state.movieDuration * 1000 / state.movieTimescale
            else -> 0
        }
        // The sample entry holds rates up to 65535 Hz; above that the media timescale is the rate.
        val sampleRate = if (track.sampleRate > 0) track.sampleRate else track.timescale.toInt()
        return ParsedAudio(
            AudioProperties(
                codec = track.codec,
                sampleRate = sampleRate,
                channels = track.channels,
                bitsPerSample = track.bitsPerSample,
                durationMs = durationMs,
                bitrateKbps = bitrate(state.mdatBytes, durationMs),
            ),
            state.tags.build(),
        )
    }

    private inline fun boxes(r: SourceReader, start: Long, end: Long, block: (type: String, payload: Long, end: Long) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            r.seek(pos)
            var size = r.u32be()
            val type = r.latin1(4)
            var header = 8L
            when (size) {
                1L -> { size = r.u64be(); header = 16 }
                0L -> size = end - pos
            }
            if (size < header) break
            val boxEnd = minOf(pos + size, end) // a truncated last box still gets read
            block(type, pos + header, boxEnd)
            pos += size
        }
    }

    private fun moov(r: SourceReader, start: Long, end: Long, state: State) {
        boxes(r, start, end) { type, payload, boxEnd ->
            when (type) {
                "mvhd" -> {
                    r.seek(payload)
                    val version = r.u8()
                    r.skip(3)
                    if (version == 1) { r.skip(16); state.movieTimescale = r.u32be(); state.movieDuration = r.u64be() }
                    else { r.skip(8); state.movieTimescale = r.u32be(); state.movieDuration = r.u32be() }
                }
                "trak" -> {
                    val track = Track()
                    trak(r, payload, boxEnd, track)
                    if (track.handler == "soun" && state.audio == null) state.audio = track
                }
                "udta" -> boxes(r, payload, boxEnd) { t, p, e -> if (t == "meta") meta(r, p, e, state.tags) }
                "meta" -> meta(r, payload, boxEnd, state.tags)
            }
        }
    }

    private fun trak(r: SourceReader, start: Long, end: Long, track: Track) {
        boxes(r, start, end) { type, payload, boxEnd ->
            when (type) {
                "mdia", "minf", "stbl" -> trak(r, payload, boxEnd, track)
                "mdhd" -> {
                    r.seek(payload)
                    val version = r.u8()
                    r.skip(3)
                    if (version == 1) { r.skip(16); track.timescale = r.u32be(); track.duration = r.u64be() }
                    else { r.skip(8); track.timescale = r.u32be(); track.duration = r.u32be() }
                }
                "hdlr" -> {
                    r.seek(payload + 8)
                    track.handler = r.latin1(4)
                }
                "stsd" -> boxes(r, payload + 8, boxEnd) { entry, entryPayload, entryEnd ->
                    if (track.codec == AudioCodec.UNKNOWN) sampleEntry(r, entry, entryPayload, entryEnd, track)
                }
            }
        }
    }

    private fun sampleEntry(r: SourceReader, type: String, start: Long, end: Long, track: Track) {
        track.codec = when (type) {
            "mp4a" -> AudioCodec.AAC
            "alac" -> AudioCodec.ALAC
            "fLaC" -> AudioCodec.FLAC
            "Opus" -> AudioCodec.OPUS
            ".mp3" -> AudioCodec.MP3
            "drms", "enca" -> AudioCodec.PROTECTED
            else -> return
        }
        r.seek(start + 8) // reserved, data reference index
        val version = r.u16be()
        r.skip(6) // revision, vendor
        track.channels = r.u16be()
        val sampleSize = r.u16be()
        r.skip(4) // compression id, packet size
        track.sampleRate = (r.u32be() ushr 16).toInt()
        if (track.codec == AudioCodec.ALAC || track.codec == AudioCodec.FLAC) track.bitsPerSample = sampleSize
        val children = start + 28 + when (version) { 0 -> 0; 1 -> 16; else -> return }
        boxes(r, children, end) { child, payload, _ ->
            when (child) {
                "alac" -> {
                    // ALACSpecificConfig after version/flags: frame length, version, bit depth, …, sample rate
                    r.seek(payload + 4 + 5)
                    track.bitsPerSample = r.u8()
                    r.skip(3)
                    track.channels = r.u8()
                    r.skip(10)
                    track.sampleRate = r.u32be().toInt()
                }
                "dfLa" -> {
                    r.seek(payload + 4 + 4) // version/flags, metadata block header
                    val info = FlacParser.streamInfo(r)
                    track.sampleRate = info.sampleRate
                    track.channels = info.channels
                    track.bitsPerSample = info.bitsPerSample
                }
            }
        }
    }

    private fun meta(r: SourceReader, start: Long, end: Long, tags: TagBuilder) {
        // ISO meta is a full box; QuickTime's has no version and starts with hdlr right away.
        val children = if (r.peek(start + 4, 4).startsWith("hdlr")) start else start + 4
        boxes(r, children, end) { type, payload, boxEnd -> if (type == "ilst") ilst(r, payload, boxEnd, tags) }
    }

    private fun ilst(r: SourceReader, start: Long, end: Long, tags: TagBuilder) {
        boxes(r, start, end) { item, payload, itemEnd ->
            if (item == "covr") {
                // One data box per picture; iTunes keeps the front cover first
                boxes(r, payload, itemEnd) { child, childPayload, childEnd ->
                    if (child == "data" && childEnd - childPayload > 8) {
                        tags.picture(3, childEnd - childPayload - 8) {
                            r.seek(childPayload + 8)
                            r.readBytes((childEnd - childPayload - 8).toInt())
                        }
                    }
                }
                return@boxes
            }
            var name: String? = null
            boxes(r, payload, itemEnd) { child, childPayload, childEnd ->
                when (child) {
                    "name" -> if (childEnd - childPayload in 4..256L) {
                        r.seek(childPayload + 4)
                        name = String(r.readBytes((childEnd - childPayload - 4).toInt()), Charsets.UTF_8)
                    }
                    "data" -> {
                        if (childEnd - childPayload < 8) return@boxes
                        r.seek(childPayload)
                        val dataType = (r.u32be() and 0xFFFFFF).toInt()
                        r.skip(4) // locale
                        val length = childEnd - childPayload - 8
                        if (length > MAX_VALUE) return@boxes
                        val value = r.readBytes(length.toInt())
                        item(item, name, dataType, value, tags)
                    }
                }
            }
        }
    }

    private fun item(item: String, freeformName: String?, dataType: Int, value: ByteArray, tags: TagBuilder) {
        fun text() = when (dataType) {
            1 -> String(value, Charsets.UTF_8)
            2 -> String(value, Charsets.UTF_16BE)
            else -> null
        }
        when (item) {
            "----" -> freeformName?.let { name -> text()?.let { tags.add(name, it) } }
            "trkn", "disk" -> if (value.size >= 6) {
                val number = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
                val total = ((value[4].toInt() and 0xFF) shl 8) or (value[5].toInt() and 0xFF)
                val key = if (item == "trkn") "TRACKNUMBER" else "DISCNUMBER"
                if (number > 0) tags.add(key, if (total > 0) "$number/$total" else "$number")
            }
            "gnre" -> if (value.size >= 2) {
                val index = (((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)) - 1
                Id3Genres.name(index)?.let { tags.add("GENRE", it) }
            }
            "cpil" -> if (value.isNotEmpty() && value.last().toInt() != 0) tags.add("COMPILATION", "1")
            else -> TEXT_ITEMS[item]?.let { key -> text()?.let { tags.add(key, it) } }
        }
    }
}

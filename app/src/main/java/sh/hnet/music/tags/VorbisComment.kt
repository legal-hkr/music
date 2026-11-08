package sh.hnet.music.tags

/** Vorbis comment block (FLAC, Ogg Vorbis, Opus): little-endian lengths, "KEY=value" in UTF-8. */
internal object VorbisComment {
    private const val MAX_VALUE = 1 shl 20
    private const val KEY_PREFIX = 128
    private val PICTURE_KEYS = setOf("METADATA_BLOCK_PICTURE", "COVERART")

    /** COVERART is a bare base64 image (taken as the front cover); METADATA_BLOCK_PICTURE a base64 FLAC picture block. */
    private fun picture(key: String, base64: ByteArray, sink: PictureSink) {
        val bytes = try {
            java.util.Base64.getMimeDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return
        }
        if (key == "COVERART") {
            sink.picture(3, bytes)
            return
        }
        val block = SourceReader(ByteArraySource(bytes))
        val type = block.peek(0, 4).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) }
        pictureBlock(block)?.let { sink.picture(type, it) }
    }

    fun read(input: ByteInput, into: TagBuilder) {
        input.skip(input.u32le()) // vendor string
        val count = input.u32le()
        if (count > 100_000) throw TagFormatException("Too many comments")
        for (i in 0 until count) {
            val length = input.u32le()
            val prefixLength = minOf(length, KEY_PREFIX.toLong()).toInt()
            val prefix = input.readBytes(prefixLength)
            val rest = length - prefixLength
            val eq = prefix.indexOf('='.code.toByte())
            if (eq <= 0) {
                input.skip(rest)
                continue
            }
            val key = String(prefix, 0, eq, Charsets.US_ASCII).uppercase()
            // Pictures are only noted (megabytes of base64), unless the cover is being read.
            if (key in PICTURE_KEYS) {
                into.hasCover = true
                val sink = into.pictures
                if (sink == null || length > TagBuilder.MAX_PICTURE * 4 / 3 + 1024) {
                    input.skip(rest)
                } else {
                    val base64 = prefix.copyOfRange(eq + 1, prefixLength) + input.readBytes(rest.toInt())
                    picture(key, base64, sink)
                }
                continue
            }
            if (length > MAX_VALUE) {
                input.skip(rest)
                continue
            }
            val value = prefix.copyOfRange(eq + 1, prefixLength) + input.readBytes(rest.toInt())
            into.add(key, String(value, Charsets.UTF_8))
        }
    }
}

package sh.hnet.music.tags

/** Vorbis comment block (FLAC, Ogg Vorbis, Opus): little-endian lengths, "KEY=value" in UTF-8. */
internal object VorbisComment {
    private const val MAX_VALUE = 1 shl 20
    private const val KEY_PREFIX = 128
    private val PICTURE_KEYS = setOf("METADATA_BLOCK_PICTURE", "COVERART")

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
            // Pictures are only noted: they can be megabytes of base64, read when the cover is shown.
            if (key in PICTURE_KEYS || length > MAX_VALUE) {
                if (key in PICTURE_KEYS) into.hasCover = true
                input.skip(rest)
                continue
            }
            val value = prefix.copyOfRange(eq + 1, prefixLength) + input.readBytes(rest.toInt())
            into.add(key, String(value, Charsets.UTF_8))
        }
    }
}

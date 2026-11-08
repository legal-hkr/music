package sh.hnet.music.tags

/**
 * Collects fields under Vorbis comment names, whatever the tag format: ID3 frames, APE items
 * and MP4 atoms are mapped onto them, so one place turns fields into [AudioTags].
 */
internal class TagBuilder {
    private val fields = LinkedHashMap<String, MutableList<String>>()
    var hasCover = false

    fun add(key: String, value: String) {
        val clean = value.trim().trim('\u0000').trim()
        if (clean.isEmpty()) return
        fields.getOrPut(normalizeKey(key)) { mutableListOf() }.add(clean)
    }

    private fun first(key: String): String? = fields[key]?.firstOrNull()
    private fun all(key: String): List<String> = fields[key]?.distinct().orEmpty()

    fun build(): AudioTags {
        val (track, trackTotalInline) = numberPair(first("TRACKNUMBER"))
        val (disc, discTotalInline) = numberPair(first("DISCNUMBER"))
        val date = first("DATE")
        return AudioTags(
            title = first("TITLE"),
            artists = all("ARTIST"),
            album = first("ALBUM"),
            albumArtists = all("ALBUMARTIST"),
            trackNumber = track,
            trackTotal = trackTotalInline ?: leadingInt(first("TRACKTOTAL")),
            discNumber = disc,
            discTotal = discTotalInline ?: leadingInt(first("DISCTOTAL")),
            date = date,
            year = date?.let { YEAR.find(it)?.value?.toInt() },
            genres = all("GENRE"),
            composers = all("COMPOSER"),
            titleSort = first("TITLESORT"),
            artistSort = first("ARTISTSORT"),
            albumSort = first("ALBUMSORT"),
            albumArtistSort = first("ALBUMARTISTSORT"),
            compilation = first("COMPILATION")?.lowercase() in setOf("1", "true", "yes"),
            replayGain = ReplayGain(
                trackGainDb = gain(first("REPLAYGAIN_TRACK_GAIN")) ?: r128(first("R128_TRACK_GAIN")),
                trackPeak = number(first("REPLAYGAIN_TRACK_PEAK")),
                albumGainDb = gain(first("REPLAYGAIN_ALBUM_GAIN")) ?: r128(first("R128_ALBUM_GAIN")),
                albumPeak = number(first("REPLAYGAIN_ALBUM_PEAK")),
            ),
            hasEmbeddedCover = hasCover,
        )
    }

    companion object {
        private val YEAR = Regex("""\d{4}""")
        private val LEADING_INT = Regex("""^\s*(\d+)""")

        private val ALIASES = mapOf(
            "ALBUM ARTIST" to "ALBUMARTIST",
            "ALBUM_ARTIST" to "ALBUMARTIST",
            "TOTALTRACKS" to "TRACKTOTAL",
            "TOTALDISCS" to "DISCTOTAL",
            "TRACK" to "TRACKNUMBER",
            "DISC" to "DISCNUMBER",
            "YEAR" to "DATE",
        )

        fun normalizeKey(key: String): String = key.trim().uppercase().let { ALIASES[it] ?: it }

        private fun leadingInt(value: String?): Int? =
            value?.let { LEADING_INT.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        /** "3", "03", "3/12" */
        private fun numberPair(value: String?): Pair<Int?, Int?> {
            if (value == null) return null to null
            val parts = value.split('/', limit = 2)
            return leadingInt(parts[0]) to parts.getOrNull(1)?.let { leadingInt(it) }
        }

        private fun number(value: String?): Float? = value
            ?.replace('−', '-')
            ?.replace(',', '.')
            ?.trim()
            ?.toFloatOrNull()

        /** "-6.50 dB", "+1,2 dB" */
        private fun gain(value: String?): Float? =
            number(value?.replace(Regex("""(?i)\s*db\s*$"""), ""))

        /** Opus R128 gain: Q7.8 relative to -23 LUFS, ReplayGain's reference is 5 dB louder. */
        private fun r128(value: String?): Float? = value?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
    }
}

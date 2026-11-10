package sh.hnet.music.tags

enum class AudioCodec {
    FLAC, ALAC, MP3, MP2, MP1, AAC, VORBIS, OPUS,

    /** 1-bit audio (DSF, DSDIFF): the sample rate is the bit rate of a channel, 2.8 MHz and up. */
    DSD,

    /** Encrypted (old iTunes FairPlay .m4p): tags are readable, the audio isn't. */
    PROTECTED,
    UNKNOWN,
}

/** What the stream itself says; [bitsPerSample] only for lossless codecs. */
data class AudioProperties(
    val codec: AudioCodec,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    /** Opus header gain the decoder must apply, kept apart from ReplayGain. */
    val opusOutputGainDb: Float? = null,
)

/** ReplayGain 2 values, reference -18 LUFS; gains in dB, peaks as linear sample values. */
data class ReplayGain(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    fun orElse(other: ReplayGain) = ReplayGain(
        trackGainDb ?: other.trackGainDb,
        trackPeak ?: other.trackPeak,
        albumGainDb ?: other.albumGainDb,
        albumPeak ?: other.albumPeak,
    )
}

data class AudioTags(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtists: List<String> = emptyList(),
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    /** As tagged: a year, a date or a timestamp. */
    val date: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val titleSort: String? = null,
    val artistSort: String? = null,
    val albumSort: String? = null,
    val albumArtistSort: String? = null,
    val compilation: Boolean = false,
    val replayGain: ReplayGain = ReplayGain(),
    val hasEmbeddedCover: Boolean = false,
) {
    /** This tag's values, missing ones taken from [other] (a lower-priority tag of the same file). */
    fun orElse(other: AudioTags) = AudioTags(
        title = title ?: other.title,
        artists = artists.ifEmpty { other.artists },
        album = album ?: other.album,
        albumArtists = albumArtists.ifEmpty { other.albumArtists },
        trackNumber = trackNumber ?: other.trackNumber,
        trackTotal = trackTotal ?: other.trackTotal,
        discNumber = discNumber ?: other.discNumber,
        discTotal = discTotal ?: other.discTotal,
        date = date ?: other.date,
        year = year ?: other.year,
        genres = genres.ifEmpty { other.genres },
        composers = composers.ifEmpty { other.composers },
        titleSort = titleSort ?: other.titleSort,
        artistSort = artistSort ?: other.artistSort,
        albumSort = albumSort ?: other.albumSort,
        albumArtistSort = albumArtistSort ?: other.albumArtistSort,
        compilation = compilation || other.compilation,
        replayGain = replayGain.orElse(other.replayGain),
        hasEmbeddedCover = hasEmbeddedCover || other.hasEmbeddedCover,
    )
}

data class ParsedAudio(val properties: AudioProperties, val tags: AudioTags)

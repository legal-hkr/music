package sh.hnet.music.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import sh.hnet.music.tags.AudioCodec
import sh.hnet.music.tags.ParsedAudio

/** A playable file found in a picked folder, with its tags and stream properties. */
@Entity(
    tableName = "track",
    indices = [Index("folderUri"), Index("album"), Index("albumArtists")],
)
data class Track(
    /** SAF document URI inside the folder's tree; stable while the file isn't moved or renamed. */
    @PrimaryKey val documentUri: String,
    val folderUri: String,
    /** Path below the picked folder, e.g. "Artist/Album/01 Title.flac". */
    val relativePath: String,
    val size: Long,
    val lastModified: Long,

    val codec: AudioCodec,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    val opusOutputGainDb: Float?,

    val title: String?,
    val artists: List<String>,
    val album: String?,
    val albumArtists: List<String>,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val discTotal: Int?,
    val date: String?,
    val year: Int?,
    val genres: List<String>,
    val composers: List<String>,
    val titleSort: String?,
    val artistSort: String?,
    val albumSort: String?,
    val albumArtistSort: String?,
    val compilation: Boolean,
    val trackGainDb: Float?,
    val trackPeak: Float?,
    val albumGainDb: Float?,
    val albumPeak: Float?,
    val hasEmbeddedCover: Boolean,
) {
    companion object {
        fun from(file: ScannedFile, parsed: ParsedAudio): Track {
            val p = parsed.properties
            val t = parsed.tags
            return Track(
                documentUri = file.documentUri, folderUri = file.folderUri, relativePath = file.relativePath,
                size = file.size, lastModified = file.lastModified,
                codec = p.codec, sampleRate = p.sampleRate, channels = p.channels, bitsPerSample = p.bitsPerSample,
                durationMs = p.durationMs, bitrateKbps = p.bitrateKbps, opusOutputGainDb = p.opusOutputGainDb,
                title = t.title, artists = t.artists, album = t.album, albumArtists = t.albumArtists,
                trackNumber = t.trackNumber, trackTotal = t.trackTotal, discNumber = t.discNumber, discTotal = t.discTotal,
                date = t.date, year = t.year, genres = t.genres, composers = t.composers,
                titleSort = t.titleSort, artistSort = t.artistSort, albumSort = t.albumSort, albumArtistSort = t.albumArtistSort,
                compilation = t.compilation,
                trackGainDb = t.replayGain.trackGainDb, trackPeak = t.replayGain.trackPeak,
                albumGainDb = t.replayGain.albumGainDb, albumPeak = t.replayGain.albumPeak,
                hasEmbeddedCover = t.hasEmbeddedCover,
            )
        }
    }
}

enum class SkipReason {
    /** Not a format the parser reads (yet), e.g. WavPack, DST-compressed DSD, or not audio after all. */
    UNSUPPORTED,

    /** DRM-protected (old iTunes .m4p). */
    PROTECTED,

    /** Couldn't be opened. */
    UNREADABLE,
}

/** A file that looked like audio but isn't in the library; kept so unchanged ones aren't read again. */
@Entity(tableName = "skipped_file", indices = [Index("folderUri")])
data class SkippedFile(
    @PrimaryKey val documentUri: String,
    val folderUri: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val reason: SkipReason,
) {
    companion object {
        fun from(file: ScannedFile, reason: SkipReason) =
            SkippedFile(file.documentUri, file.folderUri, file.relativePath, file.size, file.lastModified, reason)
    }
}

/** A file as the folder listing describes it. */
data class ScannedFile(
    val documentUri: String,
    val folderUri: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
)

/** What a scan compares against the listing. */
data class FileState(val documentUri: String, val size: Long, val lastModified: Long)

data class FolderCount(val folderUri: String, val count: Int)

/** The columns the library views need, without the technical ones (codec, rate, ReplayGain). */
data class LibraryTrack(
    val documentUri: String,
    val folderUri: String,
    val relativePath: String,
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val albumArtists: List<String>,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val compilation: Boolean,
    val titleSort: String?,
    val artistSort: String?,
    val albumSort: String?,
    val albumArtistSort: String?,
    val lastModified: Long,
    val size: Long,
    val hasEmbeddedCover: Boolean,
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    /** The title, or the file name without its extension when the file has none. */
    val displayTitle: String
        get() = title ?: relativePath.substringAfterLast('/').substringBeforeLast('.')
}

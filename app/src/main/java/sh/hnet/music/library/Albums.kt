package sh.hnet.music.library

import java.text.CollationKey
import java.text.Collator
import java.util.Locale
import sh.hnet.music.data.LibraryTrack

/**
 * An album as the library shows it (SPEC §4.4).
 *
 * @param albumArtists The album artist tags; empty when the files have none.
 * @param shownArtists Who the album is shown under: the tags, else the one artist all tracks share;
 *   empty for a compilation without tags ("Various artists").
 */
data class Album(
    val key: String,
    val title: String,
    val albumArtists: List<String>,
    val shownArtists: List<String>,
    val compilation: Boolean,
    val year: Int?,
    /** In disc and track order; tracks without a number after the numbered ones, by title. */
    val tracks: List<LibraryTrack>,
    val discCount: Int,
    val durationMs: Long,
    /** The newest file's modification time: when the album came to the device, near enough. */
    val addedAt: Long,
    val coverTrack: LibraryTrack?,
    val albumSort: String?,
    val albumArtistSort: String?,
) {
    val isVarious: Boolean get() = shownArtists.isEmpty()

    /** Whether a track's row shows its artists: always on a compilation, else when they differ. */
    fun showsArtistOf(track: LibraryTrack): Boolean = compilation || track.artists != shownArtists
}

enum class AlbumSort { ALBUM_ARTIST, TITLE, YEAR, RECENTLY_ADDED }

object Albums {
    private val DISC_FOLDER = Regex("""^(cd|disc|disk|dysk|płyta)\s*[-_]?\s*\d+$""", RegexOption.IGNORE_CASE)

    /** A disc's own folder inside an album's ("CD1", "Disc 2"). */
    fun isDiscFolder(name: String): Boolean = DISC_FOLDER.matches(name.trim())

    /** Groups tracks into albums; tracks without an album tag stay out (they're in Tracks). */
    fun group(tracks: List<LibraryTrack>): List<Album> =
        tracks.filter { !it.album.isNullOrBlank() }
            .groupBy(::keyOf)
            .map { (key, members) -> build(key, members) }

    /**
     * Album title + album artists; without album artist tags, title + the folder the files are in
     * (a "CD1"/"Disc 2" folder counts as its parent), so two different "Greatest Hits" don't merge.
     */
    fun keyOf(track: LibraryTrack): String {
        val title = normalize(track.album!!)
        if (track.albumArtists.isNotEmpty()) {
            return "a\u0000$title\u0000" + track.albumArtists.joinToString("\u0001") { normalize(it) }
        }
        val parts = track.relativePath.split('/').dropLast(1)
        val folder = if (parts.isNotEmpty() && isDiscFolder(parts.last())) parts.dropLast(1) else parts
        return "f\u0000$title\u0000${track.folderUri}\u0000" + folder.joinToString("/")
    }

    private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT)

    private fun build(key: String, members: List<LibraryTrack>): Album {
        val ordered = members.sortedWith(
            compareBy<LibraryTrack> { it.discNumber ?: 1 }
                .thenBy { it.trackNumber == null }
                .thenBy { it.trackNumber ?: 0 }
                .thenBy { it.displayTitle.lowercase() },
        )
        val first = ordered.first()
        val albumArtists = first.albumArtists
        val leadArtists = ordered.mapNotNull { it.artists.firstOrNull() }.distinct()
        val compilation = ordered.any { it.compilation } || leadArtists.size > 1
        val shown = when {
            albumArtists.isNotEmpty() -> albumArtists
            compilation -> emptyList()
            else -> leadArtists
        }
        return Album(
            key = key,
            title = first.album!!.trim(),
            albumArtists = albumArtists,
            shownArtists = shown,
            compilation = compilation,
            year = ordered.firstNotNullOfOrNull { it.year },
            tracks = ordered,
            discCount = ordered.mapNotNull { it.discNumber }.distinct().size.coerceAtLeast(1),
            durationMs = ordered.sumOf { it.durationMs },
            addedAt = ordered.maxOf { it.lastModified },
            // A track with its own picture first: a folder image may be too large or fail to decode
            coverTrack = ordered.firstOrNull { it.hasEmbeddedCover } ?: ordered.firstOrNull { it.hasCover },
            albumSort = ordered.firstNotNullOfOrNull { it.albumSort },
            albumArtistSort = ordered.firstNotNullOfOrNull { it.albumArtistSort },
        )
    }

    /**
     * Sorted per SPEC §4.2: sort tags win over names, comparison by the locale's collator, a leading
     * "The " ignored only when there's no sort tag; "Various artists" last by album artist.
     */
    fun sort(albums: List<Album>, order: AlbumSort, locale: Locale): List<Album> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val keys = HashMap<String, CollationKey>()
        fun key(text: String): CollationKey = keys.getOrPut(text) { collator.getCollationKey(text) }
        val byTitle = compareBy<Album> { key(it.albumSort ?: withoutArticle(it.title)) }
        val comparator = when (order) {
            AlbumSort.TITLE -> byTitle
            AlbumSort.ALBUM_ARTIST -> compareBy<Album> { it.isVarious }
                .thenBy { key(it.albumArtistSort ?: withoutArticle(it.shownArtists.joinToString(", "))) }
                .thenBy(nullsLast<Int>()) { it.year }
                .then(byTitle)
            AlbumSort.YEAR -> compareBy<Album, Int?>(nullsLast(reverseOrder())) { it.year }.then(byTitle)
            AlbumSort.RECENTLY_ADDED -> compareByDescending<Album> { it.addedAt }.then(byTitle)
        }
        return albums.sortedWith(comparator)
    }

    /** Fast-scroller label of an album in [order] (SPEC §5.5). */
    fun section(album: Album, order: AlbumSort, locale: Locale): String = when (order) {
        AlbumSort.TITLE -> Sections.letter(album.albumSort ?: withoutArticle(album.title), locale)
        AlbumSort.ALBUM_ARTIST -> if (album.isVarious) Sections.OTHER else
            Sections.letter(album.albumArtistSort ?: withoutArticle(album.shownArtists.joinToString(", ")), locale)
        AlbumSort.YEAR -> album.year?.toString() ?: Sections.OTHER
        AlbumSort.RECENTLY_ADDED -> Sections.month(album.addedAt, locale)
    }

    fun withoutArticle(text: String): String =
        if (text.length > 4 && text.startsWith("The ", ignoreCase = true)) text.substring(4) else text
}

/** The albums listened to most, for the widget (SPEC-widgets §4, as the owner changed it): no history is kept, only the play counts. */
object MostPlayed {
    /**
     * The [limit] albums with the most listens, most played first: an album's listens are its tracks'
     * [counts] together (by document URI). Albums never listened to aren't among them; equal ones go by title.
     */
    fun albums(albums: List<Album>, counts: Map<String, Int>, limit: Int): List<Album> =
        albums.map { album -> album to album.tracks.sumOf { counts[it.documentUri] ?: 0 } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Album, Int>> { it.second }.thenBy { it.first.title })
            .take(limit)
            .map { it.first }
}

package sh.hnet.music.library

import sh.hnet.music.data.LibraryTrack
import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/** The Tracks sorts; Most played last (owner's decision), Title the default. */
enum class TrackSort { TITLE, ARTIST, ALBUM, RECENTLY_ADDED, MOST_PLAYED }

/** A sorted list with its fast-scroller labels, one per item, computed together off the main thread. */
data class Sorted<T>(val items: List<T>, val sections: List<String>)

object Tracks {
    private fun collator(locale: Locale) = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    fun titleKey(track: LibraryTrack) = track.titleSort ?: Albums.withoutArticle(track.displayTitle)
    fun artistKey(track: LibraryTrack) = track.artistSort ?: Albums.withoutArticle(track.artists.joinToString(", "))
    fun albumKey(track: LibraryTrack) = track.albumSort ?: track.album?.let(Albums::withoutArticle).orEmpty()

    /**
     * SPEC §7: Title (default); Artist, then album, then disc/track; Album, then disc/track; Recently
     * added; Most played (by [playCounts], then by title – never-played tracks in title order last).
     */
    fun sort(tracks: List<LibraryTrack>, order: TrackSort, locale: Locale, playCounts: Map<String, Int> = emptyMap()): Sorted<LibraryTrack> {
        val collator = collator(locale)
        val keys = HashMap<String, CollationKey>()
        fun key(text: String): CollationKey = keys.getOrPut(text) { collator.getCollationKey(text) }
        val inAlbum = compareBy<LibraryTrack> { it.discNumber ?: 1 }.thenBy(nullsLast<Int>()) { it.trackNumber }
        val byTitle = compareBy<LibraryTrack> { key(titleKey(it)) }
        val comparator = when (order) {
            TrackSort.TITLE -> byTitle.thenBy { key(artistKey(it)) }
            // Within an artist, tracks without an album come after the albums
            TrackSort.ARTIST -> compareBy<LibraryTrack> { it.artists.isEmpty() }.thenBy { key(artistKey(it)) }
                .thenBy { it.album == null }.thenBy { key(albumKey(it)) }.then(inAlbum).then(byTitle)
            TrackSort.ALBUM -> compareBy<LibraryTrack> { it.album == null }.thenBy { key(albumKey(it)) }.then(inAlbum).then(byTitle)
            TrackSort.RECENTLY_ADDED -> compareByDescending<LibraryTrack> { it.lastModified }.then(byTitle)
            TrackSort.MOST_PLAYED -> compareByDescending<LibraryTrack> { playCounts[it.documentUri] ?: 0 }.then(byTitle).thenBy { key(artistKey(it)) }
        }
        val sorted = tracks.sortedWith(comparator)
        val sections = sorted.map { track ->
            when (order) {
                TrackSort.TITLE -> Sections.letter(titleKey(track), locale)
                TrackSort.ARTIST -> Sections.letter(artistKey(track), locale)
                TrackSort.ALBUM -> Sections.letter(albumKey(track), locale)
                TrackSort.RECENTLY_ADDED -> Sections.month(track.lastModified, locale)
                // Counts aren't shown anywhere (owner's decision), so the scroller has no labels to give
                TrackSort.MOST_PLAYED -> Sections.OTHER
            }
        }
        return Sorted(sorted, sections)
    }
}

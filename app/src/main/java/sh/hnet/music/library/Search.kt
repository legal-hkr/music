package sh.hnet.music.library

import sh.hnet.music.data.LibraryTrack
import java.text.Normalizer

/** Search results by kind, each in its rank order (SPEC §8). */
data class SearchResults(
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val tracks: List<LibraryTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = albums.isEmpty() && artists.isEmpty() && tracks.isEmpty()
}

/**
 * Local search over albums (title), artists (name) and tracks (title). Case- and diacritics-
 * insensitive ("lodz" finds "Łódź"); every typed word must occur in the field, and items where
 * each typed word starts a word of the field rank above items with matches inside words.
 */
class SearchIndex(albums: List<Album>, artists: List<Artist>, tracks: List<LibraryTrack>) {
    private class Entry<T>(val item: T, val words: List<String>, val whole: String)

    private val albums = albums.map { Entry(it, words(it.title), normalize(it.title)) }
    private val artists = artists.map { Entry(it, words(it.name), normalize(it.name)) }
    private val tracks = tracks.map { Entry(it, words(it.displayTitle), normalize(it.displayTitle)) }

    fun search(query: String): SearchResults {
        val terms = words(query)
        if (terms.isEmpty()) return SearchResults()
        return SearchResults(rank(albums, terms), rank(artists, terms), rank(tracks, terms))
    }

    /** Matching items, word-start matches first; the input order (the library's sort) otherwise. */
    private fun <T> rank(entries: List<Entry<T>>, terms: List<String>): List<T> {
        val prefix = ArrayList<T>()
        val inner = ArrayList<T>()
        for (entry in entries) {
            var allPrefix = true
            val matches = terms.all { term ->
                when {
                    entry.words.any { it.startsWith(term) } -> true
                    entry.whole.contains(term) -> { allPrefix = false; true }
                    else -> false
                }
            }
            if (matches) (if (allPrefix) prefix else inner) += entry.item
        }
        return prefix + inner
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Lower case without diacritics; letters that don't decompose (ł, ø, đ, ß) mapped by hand. */
        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            return buildString(decomposed.length) {
                for (c in decomposed) when (c) {
                    'ł' -> append('l')
                    'ø' -> append('o')
                    'đ' -> append('d')
                    'ß' -> append("ss")
                    'æ' -> append("ae")
                    'œ' -> append("oe")
                    else -> append(c)
                }
            }
        }

        fun words(text: String): List<String> = normalize(text).split(SEPARATORS).filter { it.isNotEmpty() }
    }
}

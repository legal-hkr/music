package sh.hnet.music.library

import sh.hnet.music.data.LibraryTrack
import java.text.Normalizer
import kotlin.math.abs

/** What a playlist line knows about its track (a stored entry's snapshot, or an imported line). */
data class TrackHint(
    val path: String?,
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
)

/**
 * Finds the library track a playlist line means (SPEC-playlists §9.2): by the trailing parts of its
 * path, then by its tags. Pure; built once per pass over the library's visible tracks.
 */
class TrackMatcher(tracks: List<LibraryTrack>) {
    /** Tracks by their file name, with their path's parts (normalised) for the suffix comparison. */
    private val byFileName: Map<String, List<Pair<List<String>, LibraryTrack>>> = tracks
        .map { pathParts(it.relativePath) to it }
        .filter { it.first.isNotEmpty() }
        .groupBy { it.first.last() }

    private val byTitle: Map<String, List<LibraryTrack>> = tracks
        .filter { !it.title.isNullOrBlank() }
        .groupBy { titleKey(it.title!!) }

    /**
     * The track [hint] means, or null. [previousAlbum] (the album of the entry before it) settles
     * a choice between equal candidates, as playlists usually keep albums together.
     */
    fun match(hint: TrackHint, previousAlbum: String? = null): LibraryTrack? {
        var tied: List<LibraryTrack> = emptyList()
        val parts = hint.path?.let(::pathParts).orEmpty()
        if (parts.isNotEmpty()) {
            val scored = byFileName[parts.last()].orEmpty().map { (candidate, track) -> commonSuffix(parts, candidate) to track }
            val best = scored.maxOfOrNull { it.first } ?: 0
            // At least the file and its folder, so "01 Intro.flac" alone never decides
            if (best >= 2) {
                tied = scored.filter { it.first == best }.map { it.second }
                if (tied.size == 1) return tied.first()
            }
        }
        val byTags = matchTags(hint, if (tied.isNotEmpty()) tied else null)
        return choose(byTags.ifEmpty { tied }, previousAlbum)
    }

    private fun matchTags(hint: TrackHint, among: List<LibraryTrack>?): List<LibraryTrack> {
        val title = hint.title?.takeIf { it.isNotBlank() } ?: return emptyList()
        var candidates = among?.filter { !it.title.isNullOrBlank() && titleKey(it.title) == titleKey(title) } ?: byTitle[titleKey(title)].orEmpty()
        val artists = hint.artists.flatMap(::artistKeys).toSet()
        if (artists.isNotEmpty()) candidates = candidates.filter { track -> track.artists.flatMap(::artistKeys).any { it in artists } }
        hint.durationMs?.takeIf { it > 0 }?.let { duration -> candidates = candidates.filter { abs(it.durationMs - duration) <= DURATION_SLACK_MS } }
        hint.album?.takeIf { it.isNotBlank() }?.let { album ->
            val key = SearchIndex.normalize(album.trim())
            candidates.filter { !it.album.isNullOrBlank() && SearchIndex.normalize(it.album.trim()) == key }.takeIf { it.isNotEmpty() }?.let { candidates = it }
        }
        return candidates
    }

    /** Several left: the one on the previous entry's album, else the first by path. */
    private fun choose(candidates: List<LibraryTrack>, previousAlbum: String?): LibraryTrack? {
        if (candidates.size <= 1) return candidates.firstOrNull()
        if (previousAlbum != null) candidates.firstOrNull { !it.album.isNullOrBlank() && Albums.keyOf(it) == previousAlbum }?.let { return it }
        return candidates.minByOrNull { it.relativePath }
    }

    companion object {
        const val DURATION_SLACK_MS = 2_000L

        private val PERCENT = Regex("%[0-9A-Fa-f]{2}")
        private val FEATURING = Regex("""\s*[(\[]?\s*\b(feat|ft|featuring)\b\.?.*$""", RegexOption.IGNORE_CASE)
        private val ARTIST_SEPARATORS = Regex("""\s*(?:,|;|/|&|\+|\band\b|\bx\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)

        /**
         * A path's parts, normalised for comparison: `file://` and percent-encoding decoded, `\` as
         * `/`, a drive letter dropped, case and Unicode form folded. Roots don't matter: only the
         * trailing parts are compared.
         */
        fun pathParts(path: String): List<String> {
            var p = path.trim()
            if (p.startsWith("file:", ignoreCase = true)) p = p.substring(5)
            if (PERCENT.containsMatchIn(p)) p = percentDecode(p)
            p = p.replace('\\', '/')
            return p.split('/')
                .filter { it.isNotEmpty() && it != "." && !(it.length == 2 && it[1] == ':') }
                .map { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase() }
        }

        private fun percentDecode(text: String): String {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length && text.substring(i + 1, i + 3).all { it.isLetterOrDigit() }) {
                    bytes.write(text.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun commonSuffix(a: List<String>, b: List<String>): Int {
            var n = 0
            while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
            return n
        }

        /** A title without "feat. …" and with case and diacritics folded. */
        fun titleKey(title: String) = SearchIndex.normalize(title.replace(FEATURING, "").trim())

        /** An artist field's names ("A & B feat. C" counts A, B and C), folded. */
        fun artistKeys(artist: String): List<String> =
            artist.replace(Regex("""\b(feat|ft|featuring)\b\.?""", RegexOption.IGNORE_CASE), ",")
                .split(ARTIST_SEPARATORS)
                .map { SearchIndex.normalize(it.trim()) }
                .filter { it.isNotEmpty() }
    }
}

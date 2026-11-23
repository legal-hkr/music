package sh.hnet.music.library

import java.text.Collator
import java.util.Locale
import sh.hnet.music.data.LibraryTrack

/**
 * Everyone who is an album artist or a track artist (SPEC §6.1). "Various artists" is a UI string,
 * never a name, so it doesn't appear; a tag literally reading "Various Artists" does.
 */
data class Artist(
    val key: String,
    val name: String,
    val sortName: String,
    /** Albums under this artist's name, newest first. */
    val ownAlbums: List<Album>,
    /** Other albums with tracks by this artist, newest first. */
    val appearsOn: List<Album>,
    /** All tracks by this artist. */
    val tracks: List<LibraryTrack>,
) {
    /** The newest own album's cover, for the list's avatar. */
    val avatar: LibraryTrack? get() = ownAlbums.firstNotNullOfOrNull { it.coverTrack }

    /** Play order (SPEC §6.2): own albums in track order, then Appears on, then the rest. */
    fun playOrder(): List<LibraryTrack> {
        val ownTracks = ownAlbums.flatMap { it.tracks }
        val appears = appearsOn.flatMap { album -> tracksOn(album) }
        val listed = (ownTracks + appears).mapTo(HashSet()) { it.documentUri }
        return ownTracks + appears + tracks.filter { it.documentUri !in listed }
    }

    /** This artist's tracks on [album], in the album's order. */
    fun tracksOn(album: Album): List<LibraryTrack> = album.tracks.filter { track -> track.artists.any { Artists.key(it) == key } }
}

object Artists {
    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    private val newestFirst = compareBy<Album, Int?>(nullsLast(reverseOrder())) { it.year }.thenByDescending { it.addedAt }

    fun build(tracks: List<LibraryTrack>, albums: List<Album>): List<Artist> {
        val names = HashMap<String, String>()
        val sortTags = HashMap<String, String>()
        val own = HashMap<String, MutableList<Album>>()
        val byArtist = HashMap<String, MutableList<LibraryTrack>>()
        val albumOf = HashMap<String, Album>()
        for (album in albums) {
            album.tracks.forEach { albumOf[it.documentUri] = album }
            for (name in album.shownArtists) {
                val k = key(name)
                names.putIfAbsent(k, name.trim())
                own.getOrPut(k) { mutableListOf() } += album
            }
            if (album.albumArtists.size == 1) album.albumArtistSort?.let { sortTags.putIfAbsent(key(album.albumArtists[0]), it) }
        }
        for (track in tracks) {
            // Names equal but for case or spaces are one artist: the track once (a second copy made its key twice on "Show all")
            for (name in track.artists.distinctBy(::key)) {
                val k = key(name)
                if (k.isEmpty()) continue
                names.putIfAbsent(k, name.trim())
                byArtist.getOrPut(k) { mutableListOf() } += track
            }
            if (track.artists.size == 1) track.artistSort?.let { sortTags.putIfAbsent(key(track.artists[0]), it) }
        }
        return names.map { (k, name) ->
            val ownAlbums = own[k].orEmpty().sortedWith(newestFirst)
            val ownKeys = ownAlbums.mapTo(HashSet()) { it.key }
            val artistTracks = byArtist[k].orEmpty()
            val appearsOn = artistTracks.mapNotNull { albumOf[it.documentUri] }
                .distinctBy { it.key }
                .filter { it.key !in ownKeys }
                .sortedWith(newestFirst)
            Artist(k, name, sortTags[k] ?: Albums.withoutArticle(name), ownAlbums, appearsOn, artistTracks)
        }
    }

    fun sort(artists: List<Artist>, locale: Locale): Sorted<Artist> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val keyed = artists.map { it to collator.getCollationKey(it.sortName) }.sortedBy { it.second }.map { it.first }
        return Sorted(keyed, keyed.map { Sections.letter(it.sortName, locale) })
    }
}

package sh.hnet.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.hnet.music.data.LibraryTrack
import java.util.Locale

class ArtistsTracksTest {
    private val pl = Locale.forLanguageTag("pl")

    private fun track(
        path: String, title: String, artists: List<String>, album: String? = null, albumArtists: List<String> = emptyList(),
        number: Int? = null, year: Int? = null, artistSort: String? = null,
    ) = LibraryTrack(
        documentUri = "doc:$path", folderUri = "tree", relativePath = path, title = title, artists = artists, album = album,
        albumArtists = albumArtists, trackNumber = number, discNumber = null, year = year, durationMs = 1000, compilation = false,
        titleSort = null, artistSort = artistSort, albumSort = null, albumArtistSort = null, lastModified = 0, size = 1,
        hasEmbeddedCover = false,
    )

    private val library = listOf(
        track("a/1", "Pierwsza", listOf("Łona"), "Album Łony", listOf("Łona"), 1, 2019),
        track("a/2", "Druga", listOf("Łona", "Webber"), "Album Łony", listOf("Łona"), 2, 2019),
        track("c/1", "Lato", listOf("Lao Che"), "Hity", number = 1, year = 2024),
        track("c/2", "Zima", listOf("Łona"), "Hity", number = 2, year = 2024),
        track("x/1", "Singiel", listOf("Łona")),
        track("b/1", "Help", listOf("The Beatles"), "Help!", year = 1965),
    )

    @Test
    fun artistsHaveOwnAlbumsAppearancesAndTracks() {
        val artists = Artists.build(library, Albums.group(library)).associateBy { it.name }
        assertEquals(setOf("Łona", "Webber", "Lao Che", "The Beatles"), artists.keys)
        val lona = artists.getValue("Łona")
        assertEquals(listOf("Album Łony"), lona.ownAlbums.map { it.title })
        assertEquals(listOf("Hity"), lona.appearsOn.map { it.title })
        assertEquals(listOf("Zima"), lona.tracksOn(lona.appearsOn[0]).map { it.title })
        assertEquals(listOf("Pierwsza", "Druga", "Zima", "Singiel"), lona.playOrder().map { it.title })
        val webber = artists.getValue("Webber")
        assertEquals(listOf("Album Łony"), webber.appearsOn.map { it.title })
        assertFalse(artists.containsKey("Various artists"))
    }

    @Test
    fun artistOrderAndPolishSections() {
        val sorted = Artists.sort(Artists.build(library, Albums.group(library)), pl)
        assertEquals(listOf("The Beatles", "Lao Che", "Łona", "Webber"), sorted.items.map { it.name })
        assertEquals(listOf("B", "L", "Ł", "W"), sorted.sections)
    }

    @Test
    fun tracksByArtistThenAlbumThenNumber() {
        val sorted = Tracks.sort(library, TrackSort.ARTIST, pl)
        assertEquals(listOf("Help", "Lato", "Pierwsza", "Zima", "Singiel", "Druga"), sorted.items.map { it.title })
        val byTitle = Tracks.sort(library, TrackSort.TITLE, pl)
        assertEquals(listOf("Druga", "Help", "Lato", "Pierwsza", "Singiel", "Zima"), byTitle.items.map { it.title })
        assertEquals("D", byTitle.sections.first())
    }

    @Test
    fun tracksByPlaysThenTitle() {
        val counts = mapOf("doc:c/1" to 3, "doc:b/1" to 7, "doc:a/2" to 3)
        val sorted = Tracks.sort(library, TrackSort.MOST_PLAYED, pl, counts)
        // Most played first, ties and never-played ones by title
        assertEquals(listOf("Help", "Druga", "Lato", "Pierwsza", "Singiel", "Zima"), sorted.items.map { it.title })
    }
}

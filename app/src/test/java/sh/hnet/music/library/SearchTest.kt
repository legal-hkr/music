package sh.hnet.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.Playlist

class SearchTest {
    private fun track(title: String, artist: String = "A", album: String? = null) = LibraryTrack(
        documentUri = "doc:$title", folderUri = "tree", relativePath = "$title.flac", title = title, artists = listOf(artist),
        album = album, albumArtists = emptyList(), trackNumber = null, discNumber = null, year = null, durationMs = 1000,
        compilation = false, titleSort = null, artistSort = null, albumSort = null, albumArtistSort = null, lastModified = 0,
        size = 1, hasEmbeddedCover = false,
    )

    private val tracks = listOf(
        track("Łódź Kaliska", "Łona", "Nawiasem mówiąc"),
        track("Melodia", "Kult"),
        track("Komedia", "Kult"),
        track("Paranoja", "Myslovitz", "Nawiasem mówiąc"),
    )
    private val albums = Albums.group(tracks)
    private val index = SearchIndex(albums, Artists.build(tracks, albums), tracks)

    @Test
    fun ignoresCaseAndDiacritics() {
        assertEquals(listOf("Łódź Kaliska"), index.search("lodz").tracks.map { it.title })
        assertEquals(listOf("Łona"), index.search("LONA").artists.map { it.name })
        assertEquals(listOf("Nawiasem mówiąc"), index.search("mowiac").albums.map { it.title })
    }

    @Test
    fun everyWordMustMatchAndWordStartsRankFirst() {
        assertEquals(listOf("Łódź Kaliska"), index.search("kal lo").tracks.map { it.title })
        assertTrue(index.search("kal xyz").isEmpty)
        // "media" occurs only inside "Komedia", not at a word start: still found
        assertEquals(listOf("Komedia"), index.search("media").tracks.map { it.title })
        // Word start beats inner match: "me" starts "Melodia", is inside "Komedia"
        assertEquals(listOf("Melodia", "Komedia"), index.search("me").tracks.map { it.title })
    }

    @Test
    fun emptyQueryFindsNothing() {
        assertTrue(index.search("  - ").isEmpty)
    }

    @Test
    fun playlistsMatchByNameWithoutDiacritics() {
        fun playlist(id: Long, name: String) = PlaylistSummary(Playlist(id, name, 0, 0), emptyList(), emptyList(), 0)
        val playlists = listOf(playlist(1, "Na wieczór"), playlist(2, "Wieczorem"), playlist(3, "Bieganie"))
        // Both start a word with "wiecz": the input order stays (the app passes them in name order)
        assertEquals(listOf("Na wieczór", "Wieczorem"), index.searchPlaylists(playlists, "wiecz").map { it.playlist.name })
        assertTrue(index.searchPlaylists(playlists, "łódź").isEmpty())
    }
}

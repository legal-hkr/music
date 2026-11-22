package sh.hnet.music.library

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.data.FolderCover
import sh.hnet.music.data.LibraryTrack

class AlbumsTest {
    private fun track(
        path: String,
        album: String?,
        artists: List<String> = listOf("A"),
        albumArtists: List<String> = emptyList(),
        number: Int? = null,
        disc: Int? = null,
        year: Int? = null,
        compilation: Boolean = false,
        albumSort: String? = null,
        modified: Long = 0,
    ) = LibraryTrack(
        documentUri = "doc:$path", folderUri = "tree", relativePath = path, title = path.substringAfterLast('/'),
        artists = artists, album = album, albumArtists = albumArtists, trackNumber = number, discNumber = disc,
        year = year, durationMs = 1000, compilation = compilation, titleSort = null, artistSort = null,
        albumSort = albumSort, albumArtistSort = null, lastModified = modified, size = 1, hasEmbeddedCover = false,
    )

    @Test
    fun theCoverComesFromATrackWithItsOwnPictureFirstThenFromAFolderImage() {
        val folder = FolderCover("doc:X/cover.jpg", 10, 1)
        val first = track("X/CD1/1.flac", "X", number = 1, disc = 1).copy(folderCover = folder)
        val own = track("X/CD2/1.flac", "X", number = 1, disc = 2).copy(hasEmbeddedCover = true)
        assertEquals(own.documentUri, Albums.group(listOf(first, own)).single().coverTrack?.documentUri)
        assertEquals(first.documentUri, Albums.group(listOf(first, track("X/CD2/2.flac", "X", number = 2, disc = 2))).single().coverTrack?.documentUri)
        assertEquals(null, Albums.group(listOf(track("Y/1.flac", "Y"))).single().coverTrack)
    }

    @Test
    fun withoutAlbumArtistGroupsByFolderAndJoinsDiscFolders() {
        val albums = Albums.group(listOf(
            track("X/Greatest Hits/CD1/01.flac", "Greatest Hits", number = 1, disc = 1),
            track("X/Greatest Hits/CD2/01.flac", "Greatest Hits", number = 1, disc = 2),
            track("Y/Greatest Hits/01.flac", "Greatest Hits", artists = listOf("B")),
            track("loose.flac", null),
        ))
        assertEquals(2, albums.size)
        val x = albums.first { it.tracks.size == 2 }
        assertEquals(2, x.discCount)
        assertEquals(listOf("A"), x.shownArtists)
        assertFalse(x.compilation)
    }

    @Test
    fun compilationRules() {
        val various = Albums.group(listOf(
            track("C/1.flac", "Hits 2024", artists = listOf("A"), number = 1),
            track("C/2.flac", "Hits 2024", artists = listOf("B"), number = 2),
        )).single()
        assertTrue(various.compilation)
        assertTrue(various.isVarious)
        assertTrue(various.showsArtistOf(various.tracks[0]))

        val tagged = Albums.group(listOf(
            track("R/1.flac", "RAM", artists = listOf("Daft Punk"), albumArtists = listOf("Daft Punk"), number = 1),
            track("R/2.flac", "RAM", artists = listOf("Daft Punk", "Giorgio Moroder"), albumArtists = listOf("Daft Punk"), number = 2),
        )).single()
        assertFalse(tagged.compilation)
        assertEquals(listOf("Daft Punk"), tagged.shownArtists)
        assertFalse(tagged.showsArtistOf(tagged.tracks[0]))
        assertTrue(tagged.showsArtistOf(tagged.tracks[1]))

        // "Various Artists" in the album artist tag names no artist: a compilation, also without the flag
        val namedVarious = Albums.group(listOf(
            track("V/1.m4a", "Now 50", artists = listOf("A"), albumArtists = listOf("Various Artists"), number = 1),
            track("V/2.m4a", "Now 50", artists = listOf("A"), albumArtists = listOf("Various Artists"), number = 2),
        )).single()
        assertTrue(namedVarious.compilation)
        assertTrue(namedVarious.isVarious)
        assertEquals(emptyList<String>(), namedVarious.albumArtists)
        assertEquals(listOf("A"), Artists.build(namedVarious.tracks, listOf(namedVarious)).map { it.name })

        // A compilation flag with a real album artist (a DJ's mix) keeps the artist
        val mix = Albums.group(listOf(
            track("M/1.flac", "Mix", artists = listOf("A"), albumArtists = listOf("DJ X"), compilation = true, number = 1),
            track("M/2.flac", "Mix", artists = listOf("B"), albumArtists = listOf("DJ X"), compilation = true, number = 2),
        )).single()
        assertEquals(listOf("DJ X"), mix.shownArtists)
    }

    @Test
    fun sortsWithPolishCollationArticlesAndVariousLast() {
        val albums = Albums.group(listOf(
            track("1/a.flac", "Łąka", albumArtists = listOf("Łona")),
            track("2/a.flac", "Lato", albumArtists = listOf("Lao Che")),
            track("3/a.flac", "Zebra", albumArtists = listOf("The Beatles")),
            track("4/a.flac", "Mix", artists = listOf("X")), track("4/b.flac", "Mix", artists = listOf("Y")),
        ))
        val pl = Locale.forLanguageTag("pl")
        assertEquals(listOf("Zebra", "Lato", "Łąka", "Mix"), Albums.sort(albums, AlbumSort.ALBUM_ARTIST, pl).map { it.title })
        assertEquals(listOf("Lato", "Łąka", "Mix", "Zebra"), Albums.sort(albums, AlbumSort.TITLE, pl).map { it.title })
    }

    @Test
    fun yearNewestFirstUnknownLast() {
        val albums = Albums.group(listOf(
            track("1/a.flac", "Old", year = 1990), track("2/a.flac", "New", year = 2020), track("3/a.flac", "None"),
        ))
        assertEquals(listOf("New", "Old", "None"), Albums.sort(albums, AlbumSort.YEAR, Locale.ENGLISH).map { it.title })
    }

    @Test
    fun `the most played albums are those whose tracks were listened to most, never listened ones left out`() {
        val albums = Albums.group(listOf(
            track("A/One/01.flac", "One"), track("A/One/02.flac", "One"),
            track("B/Two/01.flac", "Two"),
            track("C/Three/01.flac", "Three"),
            track("D/Four/01.flac", "Four"),
        ))
        val counts = mapOf("doc:A/One/01.flac" to 2, "doc:A/One/02.flac" to 3, "doc:B/Two/01.flac" to 9, "doc:D/Four/01.flac" to 5, "doc:gone.flac" to 50)
        // An album's listens are its tracks' together; equal ones go by title
        assertEquals(listOf("Two", "Four", "One"), MostPlayed.albums(albums, counts, 5).map { it.title })
        assertEquals(listOf("Two", "Four"), MostPlayed.albums(albums, counts, 2).map { it.title })
        assertEquals(emptyList<Album>(), MostPlayed.albums(albums, emptyMap(), 5))
    }
}

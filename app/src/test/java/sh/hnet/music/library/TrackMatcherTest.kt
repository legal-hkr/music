package sh.hnet.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.music.data.LibraryTrack

class TrackMatcherTest {
    private fun track(path: String, title: String?, artist: String = "A", album: String? = null, durationMs: Long = 200_000) = LibraryTrack(
        documentUri = "doc:$path", folderUri = "tree", relativePath = path, title = title, artists = listOf(artist),
        album = album, albumArtists = emptyList(), trackNumber = null, discNumber = null, year = null, durationMs = durationMs,
        compilation = false, titleSort = null, artistSort = null, albumSort = null, albumArtistSort = null, lastModified = 0,
        size = 1, hasEmbeddedCover = false,
    )

    private val getLucky = track("Daft Punk/Random Access Memories/08 Get Lucky.flac", "Get Lucky", "Daft Punk", "Random Access Memories", 369_000)
    private val intro1 = track("Album One/01 Intro.flac", "Intro", "X", "Album One")
    private val intro2 = track("Album Two/01 Intro.flac", "Intro", "Y", "Album Two")
    private val lodz = track("Łona/Nawiasem mówiąc/03 Łódź Kaliska.flac", "Łódź Kaliska", "Łona", "Nawiasem mówiąc", 241_000)
    private val matcher = TrackMatcher(listOf(getLucky, intro1, intro2, lodz))

    private fun hint(path: String? = null, title: String? = null, artists: List<String> = emptyList(), album: String? = null, durationMs: Long? = null) =
        TrackHint(path, title, artists, album, durationMs)

    @Test
    fun pathsFromOtherSystemsMatchByTheirTrailingParts() {
        assertEquals(getLucky, matcher.match(hint("C:\\Music\\Daft Punk\\Random Access Memories\\08 Get Lucky.flac")))
        assertEquals(getLucky, matcher.match(hint("/storage/emulated/0/Music/Hi-Res/Daft Punk/Random Access Memories/08 Get Lucky.flac")))
        assertEquals(getLucky, matcher.match(hint("file:///home/me/Music/Daft%20Punk/Random%20Access%20Memories/08%20Get%20Lucky.flac")))
        assertEquals(lodz, matcher.match(hint("music/ŁONA/NAWIASEM MÓWIĄC/03 Łódź Kaliska.flac")))
        assertEquals(intro2, matcher.match(hint("../Album Two/01 Intro.flac")))
    }

    @Test
    fun aFileNameAloneDoesntDecide() {
        assertNull(matcher.match(hint("01 Intro.flac")))
        // With the tags it does
        assertEquals(intro1, matcher.match(hint("01 Intro.flac", title = "Intro", artists = listOf("X"))))
    }

    @Test
    fun tagsMatchWithoutCaseDiacriticsOrFeaturing() {
        assertEquals(getLucky, matcher.match(hint(title = "get lucky (feat. Pharrell Williams)", artists = listOf("Daft Punk & Pharrell Williams"))))
        assertEquals(lodz, matcher.match(hint(title = "Lodz Kaliska", artists = listOf("Lona"))))
        assertNull(matcher.match(hint(title = "Get Lucky", artists = listOf("Someone Else"))))
    }

    @Test
    fun durationMustBeClose() {
        assertEquals(getLucky, matcher.match(hint(title = "Get Lucky", artists = listOf("Daft Punk"), durationMs = 370_500)))
        assertNull(matcher.match(hint(title = "Get Lucky", artists = listOf("Daft Punk"), durationMs = 300_000)))
    }

    @Test
    fun equalCandidatesFollowThePreviousEntrysAlbum() {
        assertEquals(intro2, matcher.match(hint(title = "Intro"), previousAlbum = Albums.keyOf(intro2)))
        assertEquals(intro1, matcher.match(hint(title = "Intro")))
    }
}

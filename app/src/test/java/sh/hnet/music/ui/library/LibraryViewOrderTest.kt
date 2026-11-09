package sh.hnet.music.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.music.ui.library.LibraryView.ALBUMS
import sh.hnet.music.ui.library.LibraryView.ARTISTS
import sh.hnet.music.ui.library.LibraryView.PLAYLISTS
import sh.hnet.music.ui.library.LibraryView.TRACKS

class LibraryViewOrderTest {
    @Test
    fun `nothing saved gives the default order`() {
        assertEquals(listOf(PLAYLISTS, ALBUMS, ARTISTS, TRACKS), LibraryView.order(null))
        assertEquals(listOf(PLAYLISTS, ALBUMS, ARTISTS, TRACKS), LibraryView.order(""))
    }

    @Test
    fun `a saved order round-trips`() {
        val order = listOf(TRACKS, ARTISTS, ALBUMS, PLAYLISTS)
        assertEquals(order, LibraryView.order(LibraryView.save(order)))
    }

    @Test
    fun `unknown and repeated names are dropped and missing views appended`() {
        assertEquals(listOf(TRACKS, PLAYLISTS, ALBUMS, ARTISTS), LibraryView.order("TRACKS,FOLDERS,TRACKS"))
    }
}

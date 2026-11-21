package sh.hnet.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.data.ScannedFile

class FolderCoversTest {
    private fun image(path: String) = ScannedFile("doc:$path", "tree", path, 1000, 1)

    @Test
    fun `only known names with picture extensions are candidates, in any case`() {
        assertTrue(FolderCovers.isCandidate("folder.jpg"))
        assertTrue(FolderCovers.isCandidate("Cover.JPEG"))
        assertTrue(FolderCovers.isCandidate("front.png"))
        assertTrue(FolderCovers.isCandidate("ALBUM.webp"))
        assertFalse(FolderCovers.isCandidate("back.jpg"))
        assertFalse(FolderCovers.isCandidate("booklet 01.jpg"))
        assertFalse(FolderCovers.isCandidate("cover.gif"))
        assertFalse(FolderCovers.isCandidate("cover"))
        assertFalse(FolderCovers.isCandidate("folder.jpg.txt"))
    }

    @Test
    fun `a directory's best image goes by name, then extension`() {
        val images = FolderCovers.byDirectory(
            listOf(image("A/Album/album.jpg"), image("A/Album/folder.png"), image("A/Album/folder.jpg"), image("B/front.webp")),
        )
        assertEquals("A/Album/folder.jpg", images["A/Album"]?.relativePath)
        assertEquals("B/front.webp", images["B"]?.relativePath)
    }

    @Test
    fun `cover is preferred to folder`() {
        val images = FolderCovers.byDirectory(listOf(image("X/folder.jpg"), image("X/cover.png")))
        assertEquals("X/cover.png", images["X"]?.relativePath)
    }

    @Test
    fun `a track takes its own folder's image`() {
        val images = FolderCovers.byDirectory(listOf(image("Artist/Album/folder.jpg"), image("Artist/folder.jpg")))
        assertEquals("Artist/Album/folder.jpg", FolderCovers.forTrack("Artist/Album/01.flac", images)?.relativePath)
    }

    @Test
    fun `a disc folder falls back to the album folder above it`() {
        val images = FolderCovers.byDirectory(listOf(image("Artist/Album/cover.jpg")))
        assertEquals("Artist/Album/cover.jpg", FolderCovers.forTrack("Artist/Album/CD1/01.flac", images)?.relativePath)
        assertEquals("Artist/Album/cover.jpg", FolderCovers.forTrack("Artist/Album/Disc 2/01.flac", images)?.relativePath)
    }

    @Test
    fun `an album without an image doesn't take the artist's folder image`() {
        val images = FolderCovers.byDirectory(listOf(image("Artist/folder.jpg")))
        assertNull(FolderCovers.forTrack("Artist/Album/01.flac", images))
    }

    @Test
    fun `files at the top of the picked folder use its image`() {
        val images = FolderCovers.byDirectory(listOf(image("folder.jpg")))
        assertEquals("folder.jpg", FolderCovers.forTrack("01.flac", images)?.relativePath)
        assertNull(FolderCovers.forTrack("CD1/01.flac", FolderCovers.byDirectory(emptyList())))
    }
}

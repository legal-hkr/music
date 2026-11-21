package sh.hnet.music.library

import sh.hnet.music.data.ScannedFile

/**
 * Cover images kept next to the audio (GH #1): `cover`, `folder`, `front` or `album` with a JPEG, PNG
 * or WebP extension, in any case. Only these names: another lone image in a folder may be a scan of
 * the booklet or a photo. A track's image is the one in its own folder, or, for a disc folder
 * ("CD1", "Disc 2" – the rule albums are grouped by), the one in the folder above.
 */
object FolderCovers {
    private val NAMES = listOf("cover", "folder", "front", "album")
    private val EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    fun isCandidate(name: String): Boolean = rank(name) != null

    /** Lower is better: by name, then by extension. */
    private fun rank(name: String): Int? {
        val base = name.substringBeforeLast('.', "").lowercase()
        val extension = name.substringAfterLast('.', "").lowercase()
        val byName = NAMES.indexOf(base)
        val byExtension = EXTENSIONS.indexOf(extension)
        return if (byName < 0 || byExtension < 0) null else byName * EXTENSIONS.size + byExtension
    }

    /** The best image of each directory (relative path, "" for the picked folder itself). */
    fun byDirectory(images: List<ScannedFile>): Map<String, ScannedFile> =
        images.mapNotNull { image -> rank(image.relativePath.substringAfterLast('/'))?.let { image to it } }
            .groupBy { (image, _) -> directoryOf(image.relativePath) }
            .mapValues { (_, ranked) -> ranked.minWith(compareBy<Pair<ScannedFile, Int>> { it.second }.thenBy { it.first.relativePath }).first }

    /** The image for the audio file at [relativePath], or null. */
    fun forTrack(relativePath: String, images: Map<String, ScannedFile>): ScannedFile? {
        val directory = directoryOf(relativePath)
        images[directory]?.let { return it }
        val name = directory.substringAfterLast('/')
        if (directory.isEmpty() || !Albums.isDiscFolder(name)) return null
        return images[directoryOf(directory)]
    }

    private fun directoryOf(relativePath: String) = relativePath.substringBeforeLast('/', "")
}

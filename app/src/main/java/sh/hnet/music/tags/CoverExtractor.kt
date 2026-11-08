package sh.hnet.music.tags

/** The embedded cover of an audio file: the front cover when marked, otherwise the first picture. */
object CoverExtractor {
    private const val FRONT = 3

    fun extract(source: RandomAccessSource): ByteArray? {
        var front: ByteArray? = null
        var first: ByteArray? = null
        TagParser.parse(source) { type, data ->
            if (type == FRONT && front == null) front = data
            if (first == null) first = data
        }
        return front ?: first
    }
}

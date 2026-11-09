package sh.hnet.music.library

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

/** The formats Music reads and writes (SPEC-playlists §9, §10), alphabetical as the export dialog lists them. */
enum class PlaylistFormat(val extension: String, val mimeType: String) {
    M3U("m3u", "audio/x-mpegurl"),
    M3U8("m3u8", "audio/x-mpegurl"),
    PLS("pls", "audio/x-scpls"),
    XSPF("xspf", "application/xspf+xml"),
}

/** A playlist file read: its own title (XSPF only) and its lines in order. */
class ParsedPlaylist(val title: String?, val lines: List<TrackHint>)

/** One entry to write: the path to put in the file and what the formats have room for. */
class ExportLine(val path: String, val title: String?, val artists: List<String>, val album: String?, val durationMs: Long?)

/** Why a file couldn't be imported; [details] is for the error's Show dialog. */
class PlaylistFileException(val kind: Kind, val details: String) : Exception(details) {
    enum class Kind { NOT_A_PLAYLIST, EMPTY }
}

/** Reading and writing playlist files: pure Kotlin, no Android (JVM-tested). */
object PlaylistFiles {
    /** Larger files aren't playlists Music should read (a 10 000-line M3U is well under 2 MB). */
    const val MAX_BYTES = 8 * 1024 * 1024

    private const val XSPF_NS = "http://xspf.org/ns/0/"

    /**
     * Reads a playlist by its content, not its extension (§9.1): `[playlist]` is PLS, a `<playlist`
     * in the XSPF namespace is XSPF, anything else text is M3U.
     *
     * @throws PlaylistFileException when it isn't a playlist or has no entries
     */
    fun parse(bytes: ByteArray, fileName: String?): ParsedPlaylist {
        if (bytes.any { it == 0.toByte() }) throw PlaylistFileException(PlaylistFileException.Kind.NOT_A_PLAYLIST, "Binary data")
        val text = decode(bytes, forceUtf8 = fileName?.endsWith(".m3u8", ignoreCase = true) == true).removePrefix("\uFEFF")
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val parsed = when {
            firstLine.equals("[playlist]", ignoreCase = true) -> parsePls(text)
            text.contains("<playlist") && text.contains(XSPF_NS) -> parseXspf(text)
            looksLikeM3u(text) -> parseM3u(text)
            else -> throw PlaylistFileException(PlaylistFileException.Kind.NOT_A_PLAYLIST, "No playlist format recognised")
        }
        if (parsed.lines.isEmpty()) throw PlaylistFileException(PlaylistFileException.Kind.EMPTY, "No entries")
        return parsed
    }

    /** UTF-8 when it decodes cleanly (or the file is .m3u8), else Windows-1252 (§9.1). */
    private fun decode(bytes: ByteArray, forceUtf8: Boolean): String {
        if (forceUtf8) return String(bytes, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** Plain text whose lines are directives, comments or paths – nothing like prose or markup. */
    private fun looksLikeM3u(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false
        if (lines.first().startsWith("#EXTM3U")) return true
        val entries = lines.filter { !it.startsWith("#") }
        // A path has a file name with an extension; a sentence or a CSV row doesn't look like one
        return entries.isNotEmpty() && entries.all { line ->
            line.contains("://") || Regex("""[^/\\]\.[A-Za-z0-9]{2,5}$""").containsMatchIn(line)
        }
    }

    private fun parseM3u(text: String): ParsedPlaylist {
        val lines = ArrayList<TrackHint>()
        var info: Triple<Long?, String?, String?>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> info = extInf(line.substring(8))
                line.startsWith("#") -> Unit // #EXTM3U, #EXTGRP, comments
                else -> {
                    val (duration, artist, title) = info ?: Triple(null, null, null)
                    lines += TrackHint(line, title, listOfNotNull(artist), null, duration)
                    info = null
                }
            }
        }
        return ParsedPlaylist(null, lines)
    }

    /** `#EXTINF:369 tvg-x="y",Daft Punk - Get Lucky`: seconds (−1 unknown), then "artist - title". */
    private fun extInf(rest: String): Triple<Long?, String?, String?> {
        val comma = rest.indexOf(',')
        val head = if (comma >= 0) rest.substring(0, comma) else rest
        val name = if (comma >= 0) rest.substring(comma + 1).trim() else ""
        val seconds = head.trim().substringBefore(' ').toDoubleOrNull()
        val duration = seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        val dash = name.indexOf(" - ")
        return if (dash > 0) Triple(duration, name.substring(0, dash).trim(), name.substring(dash + 3).trim().ifEmpty { null })
        else Triple(duration, null, name.ifEmpty { null })
    }

    private fun parsePls(text: String): ParsedPlaylist {
        val values = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq > 0) values[line.substring(0, eq).trim().lowercase()] = line.substring(eq + 1).trim()
        }
        val numbers = values.keys.mapNotNull { key -> key.removePrefix("file").takeIf { key.startsWith("file") }?.toIntOrNull() }.sorted()
        val lines = numbers.mapNotNull { n ->
            val path = values["file$n"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val length = values["length$n"]?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
            val title = values["title$n"]?.ifEmpty { null }
            // PLS titles are often "Artist - Title", as players write them
            val dash = title?.indexOf(" - ") ?: -1
            if (title != null && dash > 0) TrackHint(path, title.substring(dash + 3), listOf(title.substring(0, dash)), null, length)
            else TrackHint(path, title, emptyList(), null, length)
        }
        return ParsedPlaylist(null, lines)
    }

    private fun parseXspf(text: String): ParsedPlaylist {
        // XSPF has no DOCTYPE: refusing one shuts out external entities on every parser
        if (text.contains("<!DOCTYPE", ignoreCase = true)) throw PlaylistFileException(PlaylistFileException.Kind.NOT_A_PLAYLIST, "DOCTYPE not allowed")
        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw PlaylistFileException(PlaylistFileException.Kind.NOT_A_PLAYLIST, "XML: ${e.javaClass.simpleName}")
        }
        val root = document.documentElement
        val title = root.child("title")?.textContent?.trim()?.ifEmpty { null }
        val trackList = root.child("trackList") ?: return ParsedPlaylist(title, emptyList())
        val tracks = trackList.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .filterIsInstance<Element>().filter { it.localName == "track" }
        val lines = tracks.map { track ->
            TrackHint(
                path = track.child("location")?.textContent?.trim()?.ifEmpty { null },
                title = track.child("title")?.textContent?.trim()?.ifEmpty { null },
                artists = listOfNotNull(track.child("creator")?.textContent?.trim()?.ifEmpty { null }),
                album = track.child("album")?.textContent?.trim()?.ifEmpty { null },
                durationMs = track.child("duration")?.textContent?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            )
        }.filter { it.path != null || it.title != null }
        return ParsedPlaylist(title, lines)
    }

    private fun Element.child(name: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.localName == name && (node.namespaceURI == null || node.namespaceURI == XSPF_NS)) return node
        }
        return null
    }

    /** The file's bytes (§10): every format in UTF-8; missing entries go out as they were. */
    fun write(format: PlaylistFormat, name: String, lines: List<ExportLine>): ByteArray = when (format) {
        PlaylistFormat.M3U8, PlaylistFormat.M3U -> buildString {
            append("#EXTM3U\n")
            for (line in lines) {
                val seconds = line.durationMs?.let { (it + 500) / 1000 } ?: -1
                val label = listOfNotNull(line.artists.joinToString(", ").ifEmpty { null }, line.title).joinToString(" - ")
                append("#EXTINF:").append(seconds).append(',').append(label).append('\n')
                append(line.path).append('\n')
            }
        }
        PlaylistFormat.PLS -> buildString {
            append("[playlist]\n")
            lines.forEachIndexed { index, line ->
                val n = index + 1
                append("File").append(n).append('=').append(line.path).append('\n')
                val label = listOfNotNull(line.artists.joinToString(", ").ifEmpty { null }, line.title).joinToString(" - ")
                if (label.isNotEmpty()) append("Title").append(n).append('=').append(label).append('\n')
                append("Length").append(n).append('=').append(line.durationMs?.let { (it + 500) / 1000 } ?: -1).append('\n')
            }
            append("NumberOfEntries=").append(lines.size).append('\n')
            append("Version=2\n")
        }
        PlaylistFormat.XSPF -> buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<playlist version=\"1\" xmlns=\"").append(XSPF_NS).append("\">\n")
            append("  <title>").append(xml(name)).append("</title>\n")
            append("  <trackList>\n")
            for (line in lines) {
                append("    <track>\n")
                append("      <location>").append(xml(uriPath(line.path))).append("</location>\n")
                line.title?.let { append("      <title>").append(xml(it)).append("</title>\n") }
                line.artists.joinToString(", ").ifEmpty { null }?.let { append("      <creator>").append(xml(it)).append("</creator>\n") }
                line.album?.let { append("      <album>").append(xml(it)).append("</album>\n") }
                line.durationMs?.let { append("      <duration>").append(it).append("</duration>\n") }
                append("    </track>\n")
            }
            append("  </trackList>\n</playlist>\n")
        }
    }.toByteArray(Charsets.UTF_8)

    /** A relative path as a URI reference: each part percent-encoded, "/" kept (XSPF locations). */
    private fun uriPath(path: String): String {
        if (path.contains("://")) return path
        return path.split('/').joinToString("/") { part ->
            buildString {
                for (byte in part.toByteArray(Charsets.UTF_8)) {
                    val c = byte.toInt() and 0xFF
                    if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
                    else append('%').append("%02X".format(c))
                }
            }
        }
    }

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** "{name}.{ext}" with characters file names can't hold replaced by "_" (§10). */
    fun fileName(name: String, format: PlaylistFormat): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().ifEmpty { "playlist" } + "." + format.extension
}

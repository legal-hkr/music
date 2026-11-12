package sh.hnet.music.library

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.music.data.Documents
import sh.hnet.music.data.LibraryFolderDao
import sh.hnet.music.data.PlaylistDao
import sh.hnet.music.data.PlaylistEntry
import sh.hnet.music.data.shownTitle

/**
 * Import and export of playlist files (SPEC-playlists §9, §10) through files the user picks in the
 * system picker: read once (no lasting permission), written only where the user chose.
 */
class PlaylistTransfer(
    private val context: Context,
    private val playlists: PlaylistRepository,
    private val dao: PlaylistDao,
    private val folders: LibraryFolderDao,
    private val library: LibraryRepository,
) {
    private val resolver = context.contentResolver

    enum class Failure { NOT_A_PLAYLIST, UNREADABLE, EMPTY, UNWRITABLE }

    sealed interface Result {
        class Imported(val id: Long, val name: String, val tracks: Int, val missing: Int) : Result
        class Exported(val fileName: String) : Result
        /** [details] is shown only, behind the error's Show (never logged). */
        class Failed(val failure: Failure, val fileName: String, val details: String) : Result
    }

    /** Reads [uri], matches its lines against the library and makes a new playlist of them. */
    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val data = input.readNBytes(PlaylistFiles.MAX_BYTES + 1)
                if (data.size > PlaylistFiles.MAX_BYTES) return@withContext Result.Failed(Failure.NOT_A_PLAYLIST, fileName, "Larger than ${PlaylistFiles.MAX_BYTES / 1024 / 1024} MB")
                data
            } ?: return@withContext Result.Failed(Failure.UNREADABLE, fileName, "No content")
        } catch (e: IOException) {
            return@withContext Result.Failed(Failure.UNREADABLE, fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            return@withContext Result.Failed(Failure.UNREADABLE, fileName, e.javaClass.simpleName)
        }
        val parsed = try {
            PlaylistFiles.parse(bytes, fileName)
        } catch (e: PlaylistFileException) {
            val failure = if (e.kind == PlaylistFileException.Kind.EMPTY) Failure.EMPTY else Failure.NOT_A_PLAYLIST
            return@withContext Result.Failed(failure, fileName, e.details)
        }
        // Each line matched in order: equal candidates follow the album of the line before (§9.2)
        val matcher = TrackMatcher(library.tracks.value.orEmpty())
        var previousAlbum: String? = null
        val entries = parsed.lines.map { hint ->
            val track = matcher.match(hint, previousAlbum)
            if (track != null) {
                previousAlbum = track.album?.takeIf { it.isNotBlank() }?.let { Albums.keyOf(track) }
                entryOf(0, 0, track)
            } else {
                // Kept as missing with everything the file said (§9.2 step 3)
                PlaylistEntry(playlistId = 0, position = 0, documentUri = null, folderUri = null, path = hint.path, title = hint.title, artists = hint.artists, album = hint.album, durationMs = hint.durationMs)
            }
        }
        val name = uniqueName(parsed.title ?: fileName.substringBeforeLast('.').ifBlank { fileName })
        val id = playlists.createWithEntries(name, entries)
        val missing = entries.count { it.documentUri == null }
        Result.Imported(id, name, entries.size - missing, missing)
    }

    /**
     * Writes playlist [id] to [uri] (§10): paths as the picked folder's name and the path inside it,
     * missing entries as they were stored.
     */
    suspend fun export(id: Long, format: PlaylistFormat, uri: Uri): Result = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val playlist = dao.playlist(id) ?: return@withContext Result.Failed(Failure.UNWRITABLE, fileName, "Playlist gone")
            val folderNames = folders.all().associate { it.treeUri to it.displayName }
            val visible = library.tracksByUri.value
            val lines = dao.entries(id).map { entry ->
                val track = entry.documentUri?.let(visible::get)
                if (track != null) {
                    val folder = folderNames[track.folderUri]
                    ExportLine(listOfNotNull(folder, track.relativePath).joinToString("/"), track.title ?: track.displayTitle, track.artists, track.album, track.durationMs)
                } else {
                    ExportLine(entry.path ?: entry.shownTitle, entry.title, entry.artists, entry.album, entry.durationMs)
                }
            }
            // Starred is stored under a fixed name; the file gets the one the app shows
            val name = if (playlist.builtin == sh.hnet.music.data.Playlist.STARRED) context.getString(sh.hnet.music.R.string.label_starred_playlist) else playlist.name
            val bytes = PlaylistFiles.write(format, name, lines)
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(bytes) }
            Result.Exported(fileName)
        } catch (e: IOException) {
            Result.Failed(Failure.UNWRITABLE, fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Result.Failed(Failure.UNWRITABLE, fileName, e.javaClass.simpleName)
        }
    }

    /** "Summer 2019", else "Summer 2019 (2)", "(3)"… when the name is taken (§9.3). */
    private fun uniqueName(base: String): String = PlaylistRepository.uniqueName(PlaylistRepository.importedName(base)) { playlists.nameTaken(it) }

    private fun displayName(uri: Uri): String? = Documents.displayName(resolver, uri)
}

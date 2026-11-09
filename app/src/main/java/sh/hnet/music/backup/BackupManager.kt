package sh.hnet.music.backup

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.hnet.music.BuildConfig
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.Playlist
import sh.hnet.music.data.PlaylistEntry
import sh.hnet.music.library.PlaylistRepository
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Backup and restore (SPEC-settings-v2 §7): writes the settings and all playlists to a file the
 * user picked, and reads one back. A restore replaces the settings and every playlist; entries come
 * back unresolved and find their tracks in the library (now, or after the folders are added).
 * Nothing is logged: names and paths stay private.
 */
class BackupManager(private val app: MusicApplication, private val stamps: SharedPreferences) {
    private val resolver = app.contentResolver
    private val _lastBackup = MutableStateFlow(stamps.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })

    /** When the last backup was saved on this phone; null before the first. Not a setting. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    sealed interface Created {
        data class Saved(val fileName: String) : Created
        data class Failed(val fileName: String, val details: String) : Created
    }

    /** A picked file: checked ([check]), or not readable at all ([check] null, with [details]). */
    data class Picked(val fileName: String, val check: BackupCheck?, val details: String? = null)

    suspend fun create(uri: Uri): Created = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val text = Backup.encode(snapshot())
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_BACKUP, now) }
            _lastBackup.value = now
            Created.Saved(fileName)
        } catch (e: IOException) {
            Created.Failed(fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Created.Failed(fileName, e.javaClass.simpleName)
        }
    }

    /** Reads the picked file once and checks it; nothing changes yet (§7.3 step 2). */
    suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("No input")).use { input ->
                val read = input.readNBytes(MAX_BYTES + 1)
                if (read.size > MAX_BYTES) return@withContext Picked(fileName, BackupCheck.NotOurs("Larger than ${MAX_BYTES / 1_048_576} MB"))
                read
            }
            Picked(fileName, Backup.check(bytes.toString(Charsets.UTF_8)))
        } catch (e: IOException) {
            Picked(fileName, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Picked(fileName, null, e.javaClass.simpleName)
        }
    }

    /** Replaces the settings and every playlist (§7.3 step 4); the number of playlists restored. */
    suspend fun restore(backup: BackupFile): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val taken = HashSet<String>()
        val playlists = backup.playlists.mapNotNull { stored ->
            val starred = stored.builtIn == BackupPlaylist.STARRED
            // One Starred; any other built-in a later version may add is left out
            if (stored.builtIn != null && !starred) return@mapNotNull null
            if (starred && !taken.add(STARRED_KEY)) return@mapNotNull null
            val name = if (starred) "Starred" else uniqueName(stored.name, taken)
            val playlist = Playlist(
                name = name,
                createdAt = stored.createdAt.toMillis() ?: now,
                changedAt = stored.changedAt.toMillis() ?: now,
                builtin = if (starred) Playlist.STARRED else null,
            )
            playlist to stored.entries.map { it.toEntry() }
        }
        app.preferences.restoreSettings(backup.settings)
        app.database.playlistDao().replaceAll(playlists)
        app.playlists.requestResolve()
        playlists.count { (playlist, entries) -> playlist.builtin == null || entries.isNotEmpty() }
    }

    private suspend fun snapshot(): BackupFile {
        val dao = app.database.playlistDao()
        val folderNames = app.database.libraryFolderDao().all().associate { it.treeUri to it.displayName }
        val playlists = dao.allPlaylists().map { playlist ->
            BackupPlaylist(
                name = playlist.name,
                builtIn = if (playlist.builtin == Playlist.STARRED) BackupPlaylist.STARRED else null,
                createdAt = Instant.ofEpochMilli(playlist.createdAt).toString(),
                changedAt = Instant.ofEpochMilli(playlist.changedAt).toString(),
                entries = dao.entries(playlist.id).map { entry ->
                    // A resolved entry: its folder's name and the path inside it; otherwise the path as it was kept
                    BackupEntry(
                        folderName = entry.folderUri?.let(folderNames::get),
                        path = entry.path,
                        title = entry.title,
                        artists = entry.artists,
                        album = entry.album,
                        durationMs = entry.durationMs,
                    )
                },
            )
        }
        return BackupFile(
            appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = Instant.now().toString(),
            settings = app.preferences.backupSettings(),
            playlists = playlists,
        )
    }

    /** Unresolved, with the folder's name before the path: the track matcher finds it by the trailing parts. */
    private fun BackupEntry.toEntry() = PlaylistEntry(
        playlistId = 0,
        position = 0,
        documentUri = null,
        folderUri = null,
        path = listOfNotNull(folderName?.ifBlank { null }, path?.ifBlank { null }).joinToString("/").ifEmpty { null },
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
    )

    private fun uniqueName(name: String, taken: MutableSet<String>): String {
        val base = name.trim().take(PlaylistRepository.MAX_NAME - 5).ifEmpty { "Playlist" }
        var candidate = base
        var n = 2
        while (!taken.add(PlaylistRepository.nameKey(candidate))) candidate = "$base (${n++})"
        return candidate
    }

    private fun String?.toMillis(): Long? = this?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LAST_BACKUP = "last_backup"
        const val STARRED_KEY = "\u0000starred"
        /** Far above any real backup (a 10,000-track playlist is ~3 MB). */
        const val MAX_BYTES = 32 * 1_048_576
    }
}

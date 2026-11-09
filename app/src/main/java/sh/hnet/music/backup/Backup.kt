package sh.hnet.music.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Music's backup file (SPEC-settings-v2 §7.2): the settings and every playlist, Starred included,
 * each entry with the snapshot that finds its track again on another phone. Not in it: the library
 * index, covers, the queue, folder access, the app language and the last used view. Pure Kotlin, so
 * the format is unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings,
    val playlists: List<BackupPlaylist>,
) {
    companion object {
        const val FORMAT = "sh.hnet.music.backup"
        const val FORMAT_VERSION = 1
    }
}

/** Enum values by name; an unknown name (from a later version) falls back to the default on restore. */
@Serializable
data class BackupSettings(
    val sorts: Sorts = Sorts(),
    val libraryViews: LibraryViews = LibraryViews(),
    val playback: Playback = Playback(),
    val exportFormat: String? = null,
    val skipShorterThanSeconds: Int = 0,
) {
    @Serializable
    data class Sorts(val albums: String? = null, val tracks: String? = null, val playlists: String? = null)

    /** [opensOn] a view's name or "LAST_USED". */
    @Serializable
    data class LibraryViews(val order: List<String> = emptyList(), val opensOn: String? = null)

    @Serializable
    data class Playback(val replayGain: String? = null, val preampDb: Float? = null, val preventClipping: Boolean? = null)
}

@Serializable
data class BackupPlaylist(
    val name: String,
    /** "STARRED" for Starred; absent for the user's own. */
    @SerialName("builtIn") val builtIn: String? = null,
    val createdAt: String? = null,
    val changedAt: String? = null,
    val entries: List<BackupEntry> = emptyList(),
) {
    companion object {
        const val STARRED = "STARRED"
    }
}

/** An entry: where the file was (the picked folder's name and the path inside it) and its tags. */
@Serializable
data class BackupEntry(
    val folderName: String? = null,
    val path: String? = null,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
)

/** What a picked file turned out to be (§7.3 step 2), before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck {
        val playlists: Int get() = backup.playlists.count { it.builtIn != BackupPlaylist.STARRED || it.entries.isNotEmpty() }
        val tracks: Int get() = backup.playlists.sumOf { it.entries.size }
        val hasStarred: Boolean get() = backup.playlists.any { it.builtIn == BackupPlaylist.STARRED && it.entries.isNotEmpty() }
    }

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version of Music with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}

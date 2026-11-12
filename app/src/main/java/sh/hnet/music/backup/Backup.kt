package sh.hnet.music.backup

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Music's backup file (SPEC-settings-v2 §7.2): the settings and every playlist, Starred included,
 * each entry with the snapshot that finds its track again on another phone, the equalizer with
 * the user's presets and the remembered devices with their settings (EqualizerBackup.kt). Not in it: the library
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
    /** The user's own equalizer presets; absent in backups from before the equalizer. */
    val equalizerPresets: List<BackupEqPreset>? = null,
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
    /** The Artists view's "With albums only"; absent in backups from before it existed. */
    val artistsWithAlbumsOnly: Boolean? = null,
    /** Absent in backups from before the equalizer: a restore then leaves it as it is. */
    val equalizer: BackupEqualizer? = null,
    /** The remembered devices (SPEC-output §11); absent in backups from before them: a restore then keeps the devices there are. */
    val output: BackupOutput? = null,
) {
    @Serializable
    data class Sorts(val albums: String? = null, val tracks: String? = null, val playlists: String? = null)

    /** [opensOn] a view's name or "LAST_USED". */
    @Serializable
    data class LibraryViews(val order: List<String> = emptyList(), val opensOn: String? = null)

    @Serializable
    data class Playback(
        val replayGain: String? = null,
        val preampDb: Float? = null,
        val preventClipping: Boolean? = null,
        /** The PCM rate DSD is converted to; absent in backups from before DSD was played. */
        val dsdPcmRate: Int? = null,
        /** Bit-perfect output while it was one switch for every USB device; absent before that and since each device has its own. */
        val bitPerfect: Boolean? = null,
        /** +6 dB for DSD converted to PCM; absent in backups from before the switch. */
        val dsdBoost: Boolean? = null,
        /** Whether listens are counted; absent in backups from before the switch. */
        val countPlays: Boolean? = null,
    )
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

/** A backup's ISO 8601 time as milliseconds; null when absent or not a time. */
internal fun String?.toMillis(): Long? = this?.let {
    try {
        Instant.parse(it).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** What a picked file turned out to be (§7.3 step 2), before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck {
        val playlists: Int get() = backup.playlists.count { it.builtIn != BackupPlaylist.STARRED || it.entries.isNotEmpty() }
        val tracks: Int get() = backup.playlists.sumOf { it.entries.size }
        val hasStarred: Boolean get() = backup.playlists.any { it.builtIn == BackupPlaylist.STARRED && it.entries.isNotEmpty() }
        val hasEqualizer: Boolean get() = backup.settings.equalizer != null
        val presets: Int get() = backup.equalizerPresets.orEmpty().size
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

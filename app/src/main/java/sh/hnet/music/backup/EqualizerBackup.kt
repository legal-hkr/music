package sh.hnet.music.backup

import kotlinx.serialization.Serializable
import sh.hnet.music.equalizer.BuiltInPresets
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.equalizer.OwnPreset
import sh.hnet.music.equalizer.PresetRef
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The equalizer in a backup (SPEC-equalizer §13.1): under `settings.equalizer` what is in use, and a
 * top-level `equalizerPresets` with the user's own presets. Its own classes, not the app's state, so
 * the file's field names don't move with the code. [modified] is written for readers of the file;
 * on restore it is worked out again.
 */
@Serializable
data class BackupEqualizer(
    val enabled: Boolean = false,
    val presetRef: BackupPresetRef? = null,
    val current: BackupEqValues = BackupEqValues(),
    val modified: Boolean = false,
)

@Serializable
data class BackupPresetRef(val builtIn: String? = null, val own: String? = null)

@Serializable
data class BackupEqValues(val preamp: Float = 0f, val filters: List<BackupEqFilter> = emptyList())

/** [type] a FilterType's name. */
@Serializable
data class BackupEqFilter(val enabled: Boolean = true, val type: String = "PEAK", val frequencyHz: Int = 1000, val gainDb: Float = 0f, val q: Float = 1f)

@Serializable
data class BackupEqPreset(val name: String, val changedAt: String? = null, val preamp: Float = 0f, val filters: List<BackupEqFilter> = emptyList())

object EqualizerBackup {
    fun settings(state: EqualizerState) = BackupEqualizer(
        enabled = state.enabled,
        presetRef = state.preset?.let { BackupPresetRef(it.builtIn, it.own) },
        current = BackupEqValues(state.current.preampDb, state.current.filters.map(::filter)),
        modified = state.modified,
    )

    fun presets(state: EqualizerState): List<BackupEqPreset> = state.presets.map {
        BackupEqPreset(it.name, Instant.ofEpochMilli(it.changedAt).toString(), it.settings.preampDb, it.settings.filters.map(::filter))
    }

    /**
     * The state a backup holds, replacing everything (no merging, as playlists). Values are brought
     * into range, filters of a type this version doesn't know are left out, presets with a name
     * already taken get " (2)". A reference to a preset that isn't there (a built-in one of a later
     * version) keeps the values in use, as unsaved settings.
     *
     * @param nameKey what makes two names the same (as playlist names)
     */
    fun restore(settings: BackupEqualizer, presets: List<BackupEqPreset>, now: Long, nameKey: (String) -> String): EqualizerState {
        val taken = HashSet<String>()
        // The built-in presets' names stay theirs
        BuiltInPresets.all.forEach { preset -> preset.name?.let { taken.add(nameKey(it)) } }
        val renamed = HashMap<String, String>()
        val own = presets.mapNotNull { stored ->
            val base = stored.name.trim().take(MAX_NAME - 5).ifEmpty { return@mapNotNull null }
            var name = base
            var n = 2
            while (!taken.add(nameKey(name))) name = "$base (${n++})"
            renamed.putIfAbsent(stored.name, name)
            OwnPreset(name, stored.changedAt.toMillis() ?: now, values(stored.preamp, stored.filters))
        }
        val ref = settings.presetRef
        val preset = when {
            ref?.builtIn != null -> BuiltInPresets.byId(ref.builtIn)?.let { PresetRef(builtIn = it.id) }
            ref?.own != null -> renamed[ref.own]?.let { PresetRef(own = it) }
            else -> null
        }
        return EqualizerState(enabled = settings.enabled, preset = preset, current = values(settings.current.preamp, settings.current.filters), presets = own)
    }

    private fun filter(filter: EqFilter) = BackupEqFilter(filter.enabled, filter.type.name, filter.frequencyHz, filter.gainDb, filter.q)

    private fun values(preamp: Float, filters: List<BackupEqFilter>) = EqSettings(
        preampDb = gain(preamp),
        filters = filters.mapNotNull { stored ->
            val type = FilterType.entries.firstOrNull { it.name == stored.type } ?: return@mapNotNull null
            EqFilter(
                enabled = stored.enabled,
                type = type,
                frequencyHz = stored.frequencyHz.coerceIn(Equalizer.MIN_FREQUENCY, Equalizer.MAX_FREQUENCY),
                gainDb = gain(stored.gainDb),
                q = Equalizer.roundTo(stored.q.finite(1f).coerceIn(Equalizer.MIN_Q, Equalizer.MAX_Q), 2),
            )
        }.take(Equalizer.MAX_FILTERS),
    )

    private fun gain(db: Float) = Equalizer.roundTo(db.finite(0f).coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN), 1)

    private fun Float.finite(otherwise: Float) = if (isFinite()) this else otherwise

    private fun String?.toMillis(): Long? = this?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** As a playlist's name. */
    private const val MAX_NAME = 100
}

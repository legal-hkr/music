package sh.hnet.music.backup

import java.time.Instant
import kotlinx.serialization.Serializable
import sh.hnet.music.equalizer.BuiltInPresets
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.equalizer.OwnPreset
import sh.hnet.music.equalizer.PresetRef
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.output.Device
import sh.hnet.music.output.DevicesState
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType
import sh.hnet.music.playback.OutputKind

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
    /** The preset of the Other devices slot (SPEC-output §11); absent while the slot holds unsaved settings, and in backups from before devices. */
    val otherDevicesPresetRef: BackupPresetRef? = null,
)

@Serializable
data class BackupOutput(val devices: List<BackupDevice> = emptyList())

/**
 * A remembered device: [kind] an OutputKind's name, [bitPerfect] for a USB device, [presetRef] its
 * own equalizer preset – absent when it uses the Other devices one. [address] is never written (a
 * Bluetooth address needs a permission Music doesn't ask for); the field is the format's.
 */
@Serializable
data class BackupDevice(
    val kind: String,
    val name: String = "",
    val address: String? = null,
    val bitPerfect: Boolean = false,
    val presetRef: BackupPresetRef? = null,
    val lastPlayedAt: String? = null,
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
        otherDevicesPresetRef = state.other.takeUnless { it.isUnsaved }?.let { BackupPresetRef(it.builtIn, it.own) },
    )

    /** The remembered devices, each with the preset that is its own. */
    fun devices(devices: DevicesState, state: EqualizerState) = BackupOutput(
        devices.devices.map { device ->
            BackupDevice(
                kind = device.kind.name,
                name = device.name,
                bitPerfect = device.bitPerfect,
                presetRef = state.assigned[device.key]?.takeUnless { it.isUnsaved }?.let { BackupPresetRef(it.builtIn, it.own) },
                lastPlayedAt = Instant.ofEpochMilli(device.lastPlayedAt).toString(),
            )
        },
    )

    /** The device list a backup holds, replacing the one there is; devices of a kind this version doesn't know are left out. */
    fun restoreDevices(output: BackupOutput): DevicesState = DevicesState(
        output.devices.mapNotNull { stored ->
            val kind = OutputKind.entries.firstOrNull { it.name == stored.kind } ?: return@mapNotNull null
            Device(kind, stored.name, stored.lastPlayedAt.toMillis() ?: 0, bitPerfect = stored.bitPerfect && kind == OutputKind.USB)
        }.distinctBy { it.key },
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
     * With [output] (a backup that knows devices), every device gets its own preset back and the
     * Other devices slot its own; a reference to a preset that isn't there leaves the device on the
     * slot's preset and the slot on Flat. Of the changes not saved yet, the file holds those of the
     * preset that was in use; unsaved settings come back only as the slot's. Without [output], the
     * one preset there was is every device's, in the slot.
     *
     * @param nameKey what makes two names the same (as playlist names)
     */
    fun restore(settings: BackupEqualizer, presets: List<BackupEqPreset>, now: Long, nameKey: (String) -> String, output: BackupOutput? = null): EqualizerState {
        val taken = HashSet<String>()
        // The built-in presets' names stay theirs
        BuiltInPresets.all.forEach { preset -> preset.name?.let { taken.add(nameKey(it)) } }
        val renamed = HashMap<String, String>()
        val own = presets.mapNotNull { stored ->
            val base = stored.name.trim().take(MAX_NAME - 5).ifEmpty { return@mapNotNull null }
            val name = PlaylistRepository.uniqueName(base) { nameKey(it) in taken }.also { taken.add(nameKey(it)) }
            renamed.putIfAbsent(stored.name, name)
            OwnPreset(name, stored.changedAt.toMillis() ?: now, values(stored.preamp, stored.filters))
        }
        fun resolved(ref: BackupPresetRef?): PresetRef? = when {
            ref?.builtIn != null -> BuiltInPresets.byId(ref.builtIn)?.let { PresetRef(builtIn = it.id) }
            ref?.own != null -> renamed[ref.own]?.let { PresetRef(own = it) }
            else -> null
        }
        val preset = resolved(settings.presetRef)
        val current = values(settings.current.preamp, settings.current.filters)
        if (output == null) return EqualizerState.single(settings.enabled, preset, current, own)
        val slot = settings.otherDevicesPresetRef
        val assigned = output.devices.mapNotNull { stored ->
            val kind = OutputKind.entries.firstOrNull { it.name == stored.kind } ?: return@mapNotNull null
            resolved(stored.presetRef)?.let { AudioOutput(kind, stored.name).key to it }
        }.toMap()
        // The slot first, with the values that were in use where they were its own
        val state = when {
            slot == null && preset == null -> EqualizerState.single(settings.enabled, null, current, own)
            else -> EqualizerState(enabled = settings.enabled, presets = own, other = resolved(slot) ?: PresetRef.FLAT)
        }.copy(assigned = assigned)
        return if (preset != null) state.withChanges(preset, current) else state
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

    /** As a playlist's name. */
    private const val MAX_NAME = 100
}

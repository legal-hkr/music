package sh.hnet.music.equalizer

import kotlinx.serialization.Serializable
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType

/** A preset shipped with the app; [name] null for Flat, whose name comes from the resources. */
class BuiltInPreset(val id: String, val name: String?, val maker: String?, val settings: EqSettings)

object BuiltInPresets {
    /** No filters, pre-amp 0: where the equalizer starts and what Restore defaults selects. */
    val FLAT = BuiltInPreset("flat", null, null, EqSettings())

    /** From measurements by oratory1990 (see the open-source licences page). */
    val AUDEZE_ISINE_10 = BuiltInPreset(
        "audeze_isine_10", "Audeze iSine 10", "Audeze",
        EqSettings(
            preampDb = -6.3f,
            filters = listOf(
                EqFilter(type = FilterType.LOW_SHELF, frequencyHz = 105, gainDb = 6.3f, q = 0.70f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 1492, gainDb = -7.7f, q = 2.39f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 2987, gainDb = 7.9f, q = 0.64f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 962, gainDb = -6.0f, q = 1.06f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 320, gainDb = -1.6f, q = 0.52f),
                EqFilter(type = FilterType.HIGH_SHELF, frequencyHz = 10000, gainDb = -3.6f, q = 0.70f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 5667, gainDb = 2.6f, q = 4.13f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 3371, gainDb = -1.3f, q = 5.04f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 66, gainDb = 0.3f, q = 1.85f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 2397, gainDb = 0.6f, q = 4.30f),
            ),
        ),
    )

    /** Flat first, then the shipped presets. */
    val all = listOf(FLAT, AUDEZE_ISINE_10)

    fun byId(id: String?): BuiltInPreset? = all.firstOrNull { it.id == id }
}

/** One of the user's presets. */
@Serializable
data class OwnPreset(val name: String, val changedAt: Long, val settings: EqSettings)

/** The preset in use: a built-in one by id or the user's own by name; both null after its preset was deleted. */
@Serializable
data class PresetRef(val builtIn: String? = null, val own: String? = null)

/**
 * Everything the equalizer keeps (SPEC-equalizer §3, §9): whether it is on, the preset in use, the
 * values as they stand – which are what is heard, saved or not – and the user's presets. Changes
 * are plain functions returning the new state, unit-tested.
 */
@Serializable
data class EqualizerState(
    val enabled: Boolean = false,
    val preset: PresetRef? = PresetRef(builtIn = BuiltInPresets.FLAT.id),
    val current: EqSettings = EqSettings(),
    val presets: List<OwnPreset> = emptyList(),
) {
    /** The saved values of the preset in use; null when it is gone ("Unsaved settings"). */
    val saved: EqSettings?
        get() = preset?.let { ref -> BuiltInPresets.byId(ref.builtIn)?.settings ?: presets.firstOrNull { it.name == ref.own }?.settings }

    /** The values differ from the preset's: heard and kept all the same. */
    val modified: Boolean get() = saved.let { it != null && it != current }

    /** What the audio path applies; null while the equalizer is off. */
    val applied: EqSettings? get() = current.takeIf { enabled }

    fun withPreamp(db: Float) = copy(current = current.copy(preampDb = Equalizer.roundTo(db.coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN), 1)))

    fun withFilter(index: Int, filter: EqFilter) =
        if (index !in current.filters.indices) this
        else copy(current = current.copy(filters = current.filters.toMutableList().also { it[index] = filter }))

    /** A new filter at the end; unchanged at the limit. */
    fun withNewFilter() =
        if (current.filters.size >= Equalizer.MAX_FILTERS) this else copy(current = current.copy(filters = current.filters + Equalizer.NEW_FILTER))

    fun withoutFilter(index: Int) =
        if (index !in current.filters.indices) this else copy(current = current.copy(filters = current.filters.filterIndexed { i, _ -> i != index }))

    /** Ascending by frequency; filters at the same frequency keep their order. */
    fun sortedByFrequency() = copy(current = current.copy(filters = current.filters.sortedBy { it.frequencyHz }))

    /** [preset]'s values become the current ones. */
    fun withBuiltIn(preset: BuiltInPreset) = copy(preset = PresetRef(builtIn = preset.id), current = preset.settings)
}

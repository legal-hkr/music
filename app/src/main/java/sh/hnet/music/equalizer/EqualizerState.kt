package sh.hnet.music.equalizer

import kotlinx.serialization.Serializable
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer

/** A preset shipped with the app; [name] null for Flat, whose name comes from the resources. */
class BuiltInPreset(val id: String, val name: String?, val maker: String?, val settings: EqSettings)

object BuiltInPresets {
    /** No filters, pre-amp 0: where the equalizer starts and what Restore defaults selects. */
    val FLAT = BuiltInPreset("flat", null, null, EqSettings())

    /** The presets shipped with the app: Flat only, since the Audeze iSine 10 one went (owner's decision 27.11.2026). */
    val all = listOf(FLAT)

    fun byId(id: String?): BuiltInPreset? = all.firstOrNull { it.id == id }
}

/** One of the user's presets. */
@Serializable
data class OwnPreset(val name: String, val changedAt: Long, val settings: EqSettings)

/**
 * A preset by reference: a built-in one by id or the user's own by name. Neither: the values that
 * stayed in use after their preset was deleted ("Unsaved settings").
 */
@Serializable
data class PresetRef(val builtIn: String? = null, val own: String? = null) {
    val isUnsaved: Boolean get() = builtIn == null && own == null

    /** What unsaved changes to this preset are kept under. */
    val id: String get() = if (builtIn != null) "b:$builtIn" else if (own != null) "o:$own" else ""

    companion object {
        val FLAT = PresetRef(builtIn = BuiltInPresets.FLAT.id)
        val UNSAVED = PresetRef()
    }
}

/**
 * Everything the equalizer keeps (SPEC-equalizer §3, §9; SPEC-output §2.2): whether it is on, the
 * user's presets, and which preset each device uses – its own ([assigned], by the device's key) or
 * the one of the Other devices slot ([other]). Changes to a preset's values that aren't saved yet
 * are kept with the preset ([edits]), so every device using it hears them. What the page shows and
 * the audio path applies is the preset of the device playing now ([device]): [preset], [current].
 * Changes are plain functions returning the new state, unit-tested.
 */
@Serializable
data class EqualizerState(
    val enabled: Boolean = false,
    val presets: List<OwnPreset> = emptyList(),
    val other: PresetRef = PresetRef.FLAT,
    val assigned: Map<String, PresetRef> = emptyMap(),
    val edits: Map<String, EqSettings> = emptyMap(),
    /** The values of "Unsaved settings", while a device or the slot still uses them. */
    val unsaved: EqSettings? = null,
    /** The device playing now, by key; null until it is known (then the Other devices slot stands for it). */
    val device: String? = null,
) {
    /** The preset [device] uses: its own, or the Other devices slot's. */
    fun refFor(device: String?): PresetRef = device?.let(assigned::get) ?: other

    private val ref: PresetRef get() = refFor(device)

    /** The saved values of [ref]; null for unsaved settings or a preset that isn't there. */
    fun savedOf(ref: PresetRef): EqSettings? = BuiltInPresets.byId(ref.builtIn)?.settings ?: presets.firstOrNull { it.name == ref.own }?.settings

    /** [ref]'s values as they stand: with the changes not saved yet. */
    fun valuesOf(ref: PresetRef): EqSettings = if (ref.isUnsaved) unsaved ?: EqSettings() else edits[ref.id] ?: savedOf(ref) ?: EqSettings()

    /** The preset in use on the device playing now; null for unsaved settings. */
    val preset: PresetRef? get() = ref.takeUnless { it.isUnsaved }

    /** The values in use, which are what is heard, saved or not. */
    val current: EqSettings get() = valuesOf(ref)

    /** The saved values of the preset in use; null when it is gone ("Unsaved settings"). */
    val saved: EqSettings? get() = savedOf(ref)

    /** The values differ from the preset's: heard and kept all the same. */
    val modified: Boolean get() = saved.let { it != null && it != current }

    /** What the audio path applies; null while the equalizer is off. */
    val applied: EqSettings? get() = current.takeIf { enabled }

    private fun withCurrent(values: EqSettings): EqualizerState = when {
        ref.isUnsaved -> copy(unsaved = values)
        values == saved -> copy(edits = edits - ref.id)
        else -> copy(edits = edits + (ref.id to values))
    }

    /**
     * A built-in preset this version doesn't have (Audeze iSine 10, removed 27.11.2026) gives way to Flat wherever it
     * was chosen, its changes not saved with it: otherwise the page would call its devices' values "Unsaved settings".
     */
    fun withoutMissingBuiltIns(): EqualizerState {
        fun known(ref: PresetRef) = if (ref.builtIn != null && BuiltInPresets.byId(ref.builtIn) == null) PresetRef.FLAT else ref
        if ((assigned.values + other).all { known(it) == it }) return this
        return copy(other = known(other), assigned = assigned.mapValues { known(it.value) }).tidy()
    }

    /** The device playing now (or, while unknown, the slot) uses [new]. */
    private fun withRef(new: PresetRef): EqualizerState = (device?.let { copy(assigned = assigned + (it to new)) } ?: copy(other = new)).tidy()

    /**
     * Without what nothing uses any more: changes to a preset no device is on go with the choice of
     * another one, as they always did, and so do unsaved settings.
     */
    private fun tidy(): EqualizerState {
        val used = assigned.values + other
        val ids = used.mapTo(HashSet()) { it.id }
        return copy(edits = edits.filterKeys { it in ids }, unsaved = unsaved.takeIf { used.any(PresetRef::isUnsaved) })
    }

    fun withPreamp(db: Float) = withCurrent(current.copy(preampDb = Equalizer.roundTo(db.coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN), 1)))

    fun withFilter(index: Int, filter: EqFilter) =
        if (index !in current.filters.indices) this
        else withCurrent(current.copy(filters = current.filters.toMutableList().also { it[index] = filter }))

    /** A new filter at the end; unchanged at the limit. */
    fun withNewFilter() =
        if (current.filters.size >= Equalizer.MAX_FILTERS) this else withCurrent(current.copy(filters = current.filters + Equalizer.NEW_FILTER))

    fun withoutFilter(index: Int) =
        if (index !in current.filters.indices) this else withCurrent(current.copy(filters = current.filters.filterIndexed { i, _ -> i != index }))

    /** Ascending by frequency; filters at the same frequency keep their order. */
    fun sortedByFrequency() = withCurrent(current.copy(filters = current.filters.sortedBy { it.frequencyHz }))

    /** [preset] for the device playing now; other devices keep theirs (SPEC-output §8.4). */
    fun withBuiltIn(preset: BuiltInPreset) = withRef(PresetRef(builtIn = preset.id))

    /** One of the user's presets for the device playing now; unchanged when there is none by that name. */
    fun withOwn(name: String): EqualizerState {
        val preset = presets.firstOrNull { it.name == name } ?: return this
        return withRef(PresetRef(own = preset.name))
    }

    /**
     * Undo on the equalizer's page: the device playing now uses what it used in [before], with the values
     * as they stood there. A device that followed the Other devices slot follows it again, rather than
     * getting a preset of its own; whatever else changed meanwhile (the switch, other devices) stays.
     * Nothing is put back after a change of output: what was undone was the other device's.
     */
    fun undone(before: EqualizerState): EqualizerState {
        if (device != before.device) return this
        val own = device?.let(before.assigned::get)
        val placed = when {
            device == null -> copy(other = before.other)
            own != null -> copy(assigned = assigned + (device to own))
            else -> copy(assigned = assigned - device)
        }
        return placed.withCurrent(before.current).tidy()
    }

    /** The preset in use, when it is the user's own. */
    val ownInUse: OwnPreset? get() = preset?.own?.let { name -> presets.firstOrNull { it.name == name } }

    /** Save (§9.3): the current values into the user's preset in use; built-in presets can't be saved over. */
    fun saved(now: Long): EqualizerState {
        val name = ownInUse?.name ?: return this
        val values = current
        return copy(presets = presets.map { if (it.name == name) it.copy(settings = values, changedAt = now) else it }, edits = edits - ref.id)
    }

    /** Save as new / New preset: [settings] under [name], which becomes the preset in use. */
    fun savedAsNew(name: String, now: Long, settings: EqSettings = current): EqualizerState {
        val clean = name.trim()
        val new = PresetRef(own = clean)
        return copy(presets = presets.filterNot { it.name == clean } + OwnPreset(clean, now, settings), edits = edits - new.id).withRef(new)
    }

    fun renamed(from: String, to: String, now: Long): EqualizerState {
        val old = PresetRef(own = from)
        val new = PresetRef(own = to.trim())
        fun moved(ref: PresetRef) = if (ref == old) new else ref
        return copy(
            presets = presets.map { if (it.name == from) it.copy(name = new.own!!, changedAt = now) else it },
            other = moved(other),
            assigned = assigned.mapValues { moved(it.value) },
            edits = edits.mapKeys { if (it.key == old.id) new.id else it.key },
        )
    }

    /**
     * Without the user's preset [name]. Devices that used it are back on the Other devices preset,
     * and the slot, if it held it, on Flat (SPEC-output §2.2). On the device playing now its values
     * stay in use, as "Unsaved settings" (§9.5).
     */
    fun withoutPreset(name: String): EqualizerState {
        val gone = PresetRef(own = name)
        val heard = current
        val inUse = ref == gone
        val without = copy(
            presets = presets.filterNot { it.name == name },
            other = if (other == gone) PresetRef.FLAT else other,
            assigned = assigned.filterValues { it != gone },
            edits = edits - gone.id,
        )
        return if (inUse) without.copy(unsaved = heard).withRef(PresetRef.UNSAVED) else without.tidy()
    }

    /**
     * Undo of deleting [deleted]: the preset is back, and so is every use of it there was in [before],
     * where nothing else was chosen meanwhile – the devices that had it as their own, the slot, and the
     * device left on unsaved settings by the deletion (back on the preset, or following the slot again).
     */
    fun restoredPreset(deleted: OwnPreset, before: EqualizerState): EqualizerState {
        val back = PresetRef(own = deleted.name)
        val uses = HashMap(assigned)
        // Devices the deletion moved to the slot's preset
        for ((key, ref) in before.assigned) if (ref == back && key !in assigned) uses[key] = back
        // The device the deletion left on unsaved settings: as it was, with a preset of its own or without
        for ((key, ref) in assigned) {
            if (ref.isUnsaved && before.refFor(key) == back) before.assigned[key]?.let { uses[key] = it } ?: uses.remove(key)
        }
        val slot = if (before.other == back && (other == PresetRef.FLAT || other.isUnsaved)) back else other
        val changes = before.edits[back.id]
        return copy(
            presets = presets.filterNot { it.name == deleted.name } + deleted,
            other = slot,
            assigned = uses,
            edits = if (changes != null) edits + (back.id to changes) else edits,
        ).tidy()
    }

    /** Discard changes: back to the values of the preset in use. */
    fun discarded() = if (saved == null) this else copy(edits = edits - ref.id)

    /** [values] as [preset]'s changes not saved yet, where a device or the slot uses it and they differ from the saved ones. */
    fun withChanges(preset: PresetRef, values: EqSettings): EqualizerState {
        val saved = savedOf(preset) ?: return this
        return (if (values == saved) copy(edits = edits - preset.id) else copy(edits = edits + (preset.id to values))).tidy()
    }

    /** The device playing now. */
    fun withDevice(key: String?) = if (key == device) this else copy(device = key)

    /** [device] uses [preset] from now on (ticked in Applies to, SPEC-output §8.3); unchanged when there is no such preset. */
    fun assigned(device: String, preset: PresetRef) = if (savedOf(preset) == null) this else copy(assigned = assigned + (device to preset)).tidy()

    /** [device] is back on the Other devices preset (unticked, or forgotten). */
    fun unassigned(device: String) = copy(assigned = assigned - device).tidy()

    /**
     * Undo of forgetting [device]: what it used in [before] – its own preset, the changes to it not
     * saved yet, or unsaved settings – is back, unless something else was chosen for it meanwhile.
     */
    fun restoredDevice(device: String, before: EqualizerState): EqualizerState {
        val ref = before.assigned[device] ?: return this
        if (device in assigned || (!ref.isUnsaved && savedOf(ref) == null)) return this
        val changes = before.edits[ref.id]
        return copy(
            assigned = assigned + (device to ref),
            edits = if (changes != null && ref.id !in edits) edits + (ref.id to changes) else edits,
            unsaved = if (ref.isUnsaved) unsaved ?: before.unsaved else unsaved,
        ).tidy()
    }

    /** The Other devices slot holds [preset]: new devices and devices with no preset of their own use it. */
    fun withOther(preset: PresetRef) = if (savedOf(preset) == null) this else copy(other = preset).tidy()

    /** The devices with [preset] as their own, by key. */
    fun usedBy(preset: PresetRef): Set<String> = assigned.filterValues { it == preset }.keys

    /** Restore defaults: off, Flat on every device; the user's presets are data and stay. */
    fun restoredDefaults() = copy(enabled = false, other = PresetRef.FLAT, assigned = emptyMap(), edits = emptyMap(), unsaved = null)

    /** The user's presets, most recently changed first (§9.2). */
    val presetsByChange: List<OwnPreset> get() = presets.sortedByDescending { it.changedAt }

    /**
     * Whether [name] is taken by one of the user's presets (but [except], the one being renamed) or
     * by a built-in one ([builtInNames], as shown); [key] folds what a comparison ignores, as playlist
     * names do.
     */
    fun nameTaken(name: String, builtInNames: List<String>, key: (String) -> String, except: String? = null): Boolean {
        val wanted = key(name.trim())
        return presets.any { it.name != except && key(it.name) == wanted } || builtInNames.any { key(it) == wanted }
    }

    companion object {
        /**
         * One preset for every device: the equalizer as it was before devices had their own (the
         * file of an earlier version, a backup), held by the Other devices slot (SPEC-output §10).
         *
         * @param preset null for unsaved settings
         * @param current the values in use, saved or not
         */
        fun single(enabled: Boolean, preset: PresetRef?, current: EqSettings, presets: List<OwnPreset>): EqualizerState {
            val known = EqualizerState(enabled = enabled, presets = presets)
            val ref = preset?.takeIf { known.savedOf(it) != null } ?: PresetRef.UNSAVED
            return known.copy(other = ref).withCurrent(current)
        }
    }
}

/** The equalizer's file as versions before devices wrote it. */
@Serializable
class LegacyEqualizerState(
    val enabled: Boolean = false,
    val preset: PresetRef? = PresetRef.FLAT,
    val current: EqSettings = EqSettings(),
    val presets: List<OwnPreset> = emptyList(),
) {
    fun migrated() = EqualizerState.single(enabled, preset, current, presets)
}

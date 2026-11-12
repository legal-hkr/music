package sh.hnet.music.output

import kotlinx.serialization.Serializable
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.playback.OutputKind

/**
 * An output Music has played on, with what it keeps for it (SPEC-output §2.1): when it last played
 * there and, for a USB device, whether audio goes to it bit-perfect.
 */
@Serializable
data class Device(val kind: OutputKind, val name: String, val lastPlayedAt: Long = 0, val bitPerfect: Boolean = false) {
    val output: AudioOutput get() = AudioOutput(kind, name)
    val key: String get() = output.key
}

/** The remembered devices. Changes are plain functions returning the new state, unit-tested. */
@Serializable
data class DevicesState(val devices: List<Device> = emptyList()) {
    fun byKey(key: String): Device? = devices.firstOrNull { it.key == key }

    /** The remembered device for [output], or what a new one starts as: bit-perfect off. */
    fun deviceFor(output: AudioOutput): Device = byKey(output.key) ?: Device(output.kind, output.name)

    private fun changed(output: AudioOutput, change: (Device) -> Device): DevicesState {
        val key = output.key
        return if (byKey(key) == null) copy(devices = devices + change(Device(output.kind, output.name)))
        else copy(devices = devices.map { if (it.key == key) change(it) else it })
    }

    /** Music plays on [output]: remembered from now on. */
    fun played(output: AudioOutput, now: Long) = changed(output) { it.copy(lastPlayedAt = now) }

    /** Only USB devices have the setting; setting it remembers the device. */
    fun withBitPerfect(output: AudioOutput, on: Boolean) = if (output.kind != OutputKind.USB) this else changed(output) { it.copy(bitPerfect = on) }

    /** Forgotten (§7): next time it is a new device. */
    fun without(key: String) = copy(devices = devices.filterNot { it.key == key })

    /** Undo of forgetting: [device] as it was, unless it came back by itself meanwhile. */
    fun with(device: Device) = if (byKey(device.key) != null) this else copy(devices = devices + device)

    /** The USB devices audio goes to bit-perfect, by key. */
    val bitPerfectKeys: Set<String> get() = devices.filter { it.bitPerfect && it.kind == OutputKind.USB }.mapTo(HashSet()) { it.key }

    /** Connected devices first, then by the last time played, newest first (§2.1). */
    fun ordered(connected: Set<String>): List<Device> =
        devices.sortedWith(compareByDescending<Device> { it.key in connected }.thenByDescending { it.lastPlayedAt })
}

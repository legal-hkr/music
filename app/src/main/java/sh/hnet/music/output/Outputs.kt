package sh.hnet.music.output

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import sh.hnet.music.Preferences
import sh.hnet.music.playback.CurrentOutput
import sh.hnet.music.playback.OutputKind
import sh.hnet.music.playback.OutputSnapshot
import java.io.File
import java.io.IOException

/**
 * The outputs as the system has them and the devices Music remembers (SPEC-output §2), for as long
 * as the process lives. The devices are kept in their own file, as the equalizer's state is.
 */
class Outputs(context: Context, private val file: File, private val scope: CoroutineScope, private val preferences: Preferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fresh = !file.exists()
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<DevicesState> = _devices.asStateFlow()
    private var saving: Job? = null

    /** Followed on the main thread: the system's callbacks are registered there. */
    val system: StateFlow<OutputSnapshot> = CurrentOutput.flow(context).stateIn(scope + Dispatchers.Main, SharingStarted.Eagerly, OutputSnapshot())

    init {
        if (fresh) scope.launch(Dispatchers.Main) { migrate() }
    }

    fun update(change: (DevicesState) -> DevicesState) {
        _devices.update(change)
        saving?.cancel()
        saving = scope.launch(Dispatchers.IO) { save(_devices.value) }
    }

    /** Music plays now: the output it plays on is remembered. */
    fun played() {
        val output = system.value.current ?: return
        update { it.played(output, System.currentTimeMillis()) }
    }

    /**
     * The first start of a version with devices (§10): the output in use is known from the start,
     * and the USB devices connected take over the one bit-perfect switch there was before.
     */
    private suspend fun migrate() {
        val on = preferences.legacyBitPerfect.first()
        val snapshot = system.first { it.current != null }
        update { state ->
            snapshot.connected.filter { it.kind == OutputKind.USB }.fold(state.played(snapshot.current!!, System.currentTimeMillis())) { all, usb ->
                if (on) all.withBitPerfect(usb, true) else all
            }
        }
        preferences.clearLegacyBitPerfect()
    }

    private fun load(): DevicesState = try {
        if (file.exists()) json.decodeFromString(DevicesState.serializer(), file.readText()) else DevicesState()
    } catch (_: IOException) {
        DevicesState()
    } catch (_: SerializationException) {
        DevicesState()
    } catch (_: IllegalArgumentException) {
        DevicesState()
    }

    /** Written whole to a temporary file and renamed, so a crash never leaves half a file. */
    private fun save(state: DevicesState) {
        try {
            val partial = File(file.path + ".part")
            partial.writeText(json.encodeToString(DevicesState.serializer(), state))
            partial.renameTo(file)
        } catch (_: IOException) {
            // The previous copy stays; the next change writes again
        }
    }
}

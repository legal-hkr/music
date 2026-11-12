package sh.hnet.music.output

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/**
 * The outputs as the system has them and the devices Music remembers (SPEC-output §2), for as long
 * as the process lives. The devices are kept in their own file, as the equalizer's state is.
 */
class Outputs(context: Context, private val file: File, private val scope: CoroutineScope, private val preferences: Preferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fresh = !file.exists()
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<DevicesState> = _devices.asStateFlow()
    /** One write at a time: two at once would share the temporary file and could leave the devices unreadable. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Followed on the main thread: the system's callbacks are registered there. */
    val system: StateFlow<OutputSnapshot> = CurrentOutput.flow(context).stateIn(scope + Dispatchers.Main, SharingStarted.Eagerly, OutputSnapshot())

    init {
        if (fresh) scope.launch(Dispatchers.Main) { migrate() }
        // The one bit-perfect switch of an earlier version (§10), or of a backup made by one: no USB device
        // was remembered then, so it goes to the first one to be connected – also when that is long after
        // the update, as a DAC is seldom plugged in while the app is updated
        scope.launch(Dispatchers.Main) {
            preferences.legacyBitPerfect.collectLatest { on ->
                if (!on) return@collectLatest
                val usb = system.map { snapshot -> snapshot.connected.filter { it.kind == OutputKind.USB } }.first { it.isNotEmpty() }
                update { state -> usb.fold(state) { all, device -> all.withBitPerfect(device, true) } }
                preferences.clearLegacyBitPerfect()
            }
        }
    }

    /** Called from any thread; each change is written by the one writer, the state as it stands when its turn comes. */
    fun update(change: (DevicesState) -> DevicesState) {
        _devices.update(change)
        scope.launch(writer) { save(_devices.value) }
    }

    /** Music plays now: the output it plays on is remembered. */
    fun played() {
        val output = system.value.current ?: return
        update { it.played(output, System.currentTimeMillis()) }
    }

    /** The first start of a version with devices (§10): the output in use is known from the start. */
    private suspend fun migrate() {
        val snapshot = system.first { it.current != null }
        update { it.played(snapshot.current!!, System.currentTimeMillis()) }
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

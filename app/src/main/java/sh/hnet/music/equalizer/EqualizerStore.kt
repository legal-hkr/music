package sh.hnet.music.equalizer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException

/**
 * The equalizer's state, kept in its own file in the app's files: not in the settings store, as
 * Restore defaults switches the equalizer off but keeps the user's presets (SPEC-equalizer §13.2).
 * Read once at start (a small file); every change is heard at once and written a moment later.
 */
class EqualizerStore(private val file: File, private val scope: CoroutineScope) {
    // Every field is written: a file without the devices' part is one of an earlier version
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()
    private var saving: Job? = null

    fun update(change: (EqualizerState) -> EqualizerState) {
        _state.update(change)
        saving?.cancel()
        saving = scope.launch(Dispatchers.IO) {
            // Sliders change the state many times a second
            delay(SAVE_DELAY_MS)
            save(_state.value)
        }
    }

    private fun load(): EqualizerState = try {
        if (file.exists()) {
            val text = file.readText()
            if ("assigned" in json.parseToJsonElement(text).jsonObject) json.decodeFromString(EqualizerState.serializer(), text)
            else json.decodeFromString(LegacyEqualizerState.serializer(), text).migrated()
        } else {
            EqualizerState()
        }
    } catch (_: IOException) {
        EqualizerState()
    } catch (_: SerializationException) {
        EqualizerState()
    } catch (_: IllegalArgumentException) {
        EqualizerState()
    }

    /** Written whole to a temporary file and renamed, so a crash never leaves half a file. */
    private fun save(state: EqualizerState) {
        try {
            val partial = File(file.path + ".part")
            partial.writeText(json.encodeToString(EqualizerState.serializer(), state))
            partial.renameTo(file)
        } catch (_: IOException) {
            // The previous copy stays; the next change writes again
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 500L
    }
}

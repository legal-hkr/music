@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.equalizer.PresetRef
import sh.hnet.music.output.Device
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SettingsGroup

/**
 * The devices the equalizer's pages name, in list order (SPEC-output §2.1): the remembered ones,
 * and before them the output in use while music hasn't played on it yet. With the key of the device
 * playing now.
 */
@Composable
fun rememberEqualizerDevices(): Pair<List<Device>, String?> {
    val app = LocalContext.current.applicationContext as MusicApplication
    val system by app.outputs.system.collectAsStateWithLifecycle()
    val devices by app.outputs.devices.collectAsStateWithLifecycle()
    val current = system.current
    val ordered = devices.ordered(system.connected.mapTo(HashSet()) { it.key })
    val all = if (current != null && devices.byKey(current.key) == null) listOf(devices.deviceFor(current)) + ordered else ordered
    return all to current?.key
}

/**
 * Where [preset] is used (SPEC-output §8.2): the devices that have it as their own – one or two by
 * name, more as "{first} and {n} more" – and "other devices" when the Other devices slot holds it;
 * null when nothing uses it.
 */
@Composable
fun presetUse(state: EqualizerState, preset: PresetRef, devices: List<Device>): String? {
    val keys = state.usedBy(preset)
    val names = devices.filter { it.key in keys }.map { outputName(it.output) }
    val named = when (names.size) {
        0 -> null
        1, 2 -> names.joinToString(", ")
        else -> pluralStringResource(R.plurals.desc_applies_more, names.size - 1, names[0], names.size - 1)
    }
    return when {
        state.other != preset -> named
        named == null -> stringResource(R.string.label_other_devices)
        else -> stringResource(R.string.desc_kind_state, named, stringResource(R.string.label_other_devices_lower))
    }
}

/**
 * Applies to (SPEC-output §8.3): every device with a checkbox – ticked, it uses [preset]; unticked,
 * it is back on the Other devices preset. The device playing now always uses the preset shown, and
 * the Other devices slot can only be taken, not left. Changes apply at once.
 *
 * @param bitPerfect the USB devices audio goes to unchanged, where no preset is heard
 */
@Composable
fun AppliesToSheet(
    state: EqualizerState,
    preset: PresetRef,
    devices: List<Device>,
    current: String?,
    bitPerfect: Set<String>,
    onDismiss: () -> Unit,
    onDevice: (key: String, uses: Boolean) -> Unit,
    onOther: () -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden), containerColor = container) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.label_applies_to), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.msg_applies_to, presetName(state, preset)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Read here: the group's rows are built outside composition's reach of these helpers
            val rows = devices.map { device ->
                val playing = device.key == current
                val uses = playing || state.assigned[device.key] == preset
                val line = when {
                    playing -> stringResource(R.string.state_playing_now)
                    device.key in bitPerfect -> stringResource(R.string.desc_bit_perfect_not_heard)
                    uses -> null
                    else -> stringResource(R.string.desc_now_preset, presetName(state, state.refFor(device.key)))
                }
                DeviceChoice(device, outputName(device.output), line, uses, enabled = !playing)
            }
            SettingsGroup {
                rows.forEach { choice ->
                    row { shapes ->
                        SegmentedListItem(
                            checked = choice.uses,
                            onCheckedChange = { onDevice(choice.device.key, it) },
                            enabled = choice.enabled,
                            shapes = shapes,
                            leadingContent = { RowIcon(outputIcon(choice.device.kind)) },
                            trailingContent = { Checkbox(checked = choice.uses, onCheckedChange = null, enabled = choice.enabled) },
                            supportingContent = choice.line?.let { { Text(it) } },
                        ) {
                            Text(choice.name)
                        }
                    }
                }
            }
            val held = state.other == preset
            val otherLine = stringResource(R.string.desc_other_devices, presetName(state, state.other))
            SettingsGroup {
                row { shapes ->
                    SegmentedListItem(
                        checked = held,
                        onCheckedChange = { if (it) onOther() },
                        // Another preset has to take the slot: it is never empty
                        enabled = !held,
                        shapes = shapes,
                        leadingContent = { RowIcon(R.drawable.ic_devices) },
                        trailingContent = { Checkbox(checked = held, onCheckedChange = null, enabled = !held) },
                        supportingContent = { Text(otherLine) },
                    ) {
                        Text(stringResource(R.string.label_other_devices))
                    }
                }
            }
        }
    }
}

private class DeviceChoice(val device: Device, val name: String, val line: String?, val uses: Boolean, val enabled: Boolean)

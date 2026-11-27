@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.output.Device
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.playback.BitPerfectController
import sh.hnet.music.playback.CurrentOutput
import sh.hnet.music.playback.OutputDevices
import sh.hnet.music.playback.OutputFormatGroup
import sh.hnet.music.playback.OutputKind
import sh.hnet.music.ui.components.DestructiveButton
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SettingsDescription
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsNavigationRow
import sh.hnet.music.ui.components.SettingsNote
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.player.channelsText
import sh.hnet.music.ui.player.sampleRateText

/** A device as shown: its own name, or what kind of output it is. */
@Composable
fun outputName(output: AudioOutput): String = output.name.ifEmpty {
    stringResource(
        when (output.kind) {
            OutputKind.SPEAKER -> R.string.label_output_speaker
            OutputKind.WIRED -> R.string.label_output_wired
            OutputKind.USB -> R.string.label_usb_audio
            OutputKind.BLUETOOTH -> R.string.label_output_bluetooth
            OutputKind.OTHER -> R.string.label_output_other
        },
    )
}

/** SPEC-output §2.1. */
fun outputIcon(kind: OutputKind): Int = when (kind) {
    OutputKind.SPEAKER -> R.drawable.ic_mobile_speaker
    OutputKind.WIRED -> R.drawable.ic_headphones
    OutputKind.BLUETOOTH -> R.drawable.ic_bluetooth
    OutputKind.USB -> R.drawable.ic_usb
    OutputKind.OTHER -> R.drawable.ic_devices
}

/** "USB", "Bluetooth"; null for the kinds whose name already says what they are. */
@Composable
private fun kindLabel(kind: OutputKind): String? = when (kind) {
    OutputKind.USB -> stringResource(R.string.label_kind_usb)
    OutputKind.BLUETOOTH -> stringResource(R.string.label_kind_bluetooth)
    else -> null
}

/** "24 bit, 96 kHz" ("24 bity, 96 kHz"). */
@Composable
fun streamText(bits: Int, sampleRate: Int): String =
    stringResource(R.string.label_stream_format, pluralStringResource(R.plurals.label_bit_count, bits, bits), sampleRateText(sampleRate))

/** Everything the output pages show a device by. */
private class OutputsView(
    val current: AudioOutput?,
    val connected: Set<String>,
    val stream: BitPerfectController.Stream?,
    val playing: Boolean,
    val equalizer: EqualizerState,
)

@Composable
private fun rememberOutputsView(): Pair<OutputsView, MusicApplication> {
    val app = LocalContext.current.applicationContext as MusicApplication
    val system by app.outputs.system.collectAsStateWithLifecycle()
    val stream by app.bitPerfect.stream.collectAsStateWithLifecycle()
    val playing by app.player.isPlaying.collectAsStateWithLifecycle()
    val equalizer by app.equalizer.state.collectAsStateWithLifecycle()
    return OutputsView(system.current, system.connected.mapTo(HashSet()) { it.key }, stream, playing, equalizer) to app
}

/**
 * A device row's two supporting lines (SPEC-output §4.1): its kind and state, then what shapes the
 * sound there.
 */
@Composable
private fun deviceLines(device: Device, view: OutputsView): Pair<String, String?> {
    val isCurrent = device.key == view.current?.key
    val kind = kindLabel(device.kind)
    val stream = view.stream
    val bitPerfect = device.kind == OutputKind.USB && device.bitPerfect
    val first = when {
        isCurrent && bitPerfect -> when {
            stream?.bitPerfect == true && view.playing -> stringResource(R.string.desc_bit_perfect_stream, streamText(stream.bits, stream.sampleRate))
            stream?.bitPerfect == false -> stringResource(R.string.desc_through_mixer)
            else -> stringResource(R.string.desc_usb_bit_perfect_on)
        }
        device.kind == OutputKind.SPEAKER -> stringResource(R.string.state_available)
        isCurrent -> kind ?: stringResource(R.string.state_connected)
        device.key in view.connected -> kind?.let { stringResource(R.string.desc_kind_state, it, stringResource(R.string.state_connected_lower)) } ?: stringResource(R.string.state_connected)
        else -> kind?.let { stringResource(R.string.desc_kind_state, it, stringResource(R.string.state_not_connected_lower)) } ?: stringResource(R.string.state_not_connected)
    }
    val second = when {
        isCurrent && bitPerfect -> when {
            stream == null || stream.bitPerfect -> null
            stream.bits > 0 -> stringResource(R.string.desc_not_supported_bit_perfect, streamText(stream.bits, stream.sampleRate))
            else -> stringResource(R.string.desc_rate_not_supported_bit_perfect, sampleRateText(stream.sampleRate))
        }
        bitPerfect -> stringResource(R.string.desc_bit_perfect_on)
        !view.equalizer.enabled -> stringResource(R.string.desc_equalizer_off)
        else -> stringResource(R.string.desc_equalizer_preset, presetName(view.equalizer, view.equalizer.refFor(device.key)))
    }
    return first to second
}

/**
 * Settings › Output (SPEC-output §4): the device music plays on, the system's output switcher, and
 * the other devices Music remembers. Each row opens the device's own page.
 */
@Composable
fun OutputScreen(onBack: () -> Unit, onOpenDevice: (String) -> Unit, topInsets: Boolean = true) {
    val (view, app) = rememberOutputsView()
    val context = LocalContext.current
    val devices by app.outputs.devices.collectAsStateWithLifecycle()
    // Gone once the system had no switcher to show
    var switcher by remember { mutableStateOf(true) }
    val current = view.current
    val others = devices.ordered(view.connected).filter { it.key != current?.key }
    SettingsPage(title = stringResource(R.string.title_output), onBack = onBack, topInsets = topInsets) {
        if (current != null) {
            val device = devices.deviceFor(current)
            SettingsGroup(stringResource(R.string.title_playing_on)) {
                row { shapes -> DeviceRow(device, view, shapes, onClick = { onOpenDevice(device.key) }) }
            }
        }
        if (switcher) {
            FilledTonalButton(onClick = { switcher = CurrentOutput.showSwitcher(context) }) {
                Icon(painterResource(R.drawable.ic_speaker_group), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.button_switch_output), modifier = Modifier.padding(start = 8.dp))
            }
        }
        SettingsGroup(stringResource(R.string.title_your_devices)) {
            others.forEach { device ->
                row { shapes -> DeviceRow(device, view, shapes, onClick = { onOpenDevice(device.key) }) }
            }
        }
        SettingsNote(stringResource(R.string.msg_output_note))
    }
}

/** The device playing now is highlighted as a selected row; its icon in the same circle as the others' (the owner, 27.11.2026: the circle shows on the highlight, the old square box didn't). */
@Composable
private fun DeviceRow(device: Device, view: OutputsView, shapes: ListItemShapes, onClick: () -> Unit) {
    val isCurrent = device.key == view.current?.key
    val (first, second) = deviceLines(device, view)
    SegmentedListItem(
        selected = isCurrent,
        onClick = onClick,
        shapes = shapes,
        // The highlight is the only sign of the device playing now: it keeps M3's selected colors (settingsRowColors' are the others' since 27 Nov 2026)
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { RowIcon(outputIcon(device.kind)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        supportingContent = {
            Column {
                Text(first)
                second?.let { Text(it) }
            }
        },
    ) {
        Text(outputName(device.output))
    }
}

/**
 * A device's page (SPEC-output §5–§7): for a USB device its bit-perfect switch, what is being sent
 * and the formats it takes; for every device its equalizer preset; Forget for one not connected.
 *
 * @param onOpenEqualizer Null: no Equalizer group – opened from the output page over now playing,
 *   whose Equalizer step already shows the preset and leads there (owner's decision 27.11.2026)
 * @param messageScope outlives the page, for the snackbar with Undo
 */
@Composable
fun DeviceScreen(
    key: String,
    onBack: () -> Unit,
    onOpenEqualizer: (() -> Unit)?,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    topInsets: Boolean = true,
) {
    val (view, app) = rememberOutputsView()
    val devices by app.outputs.devices.collectAsStateWithLifecycle()
    val system by app.outputs.system.collectAsStateWithLifecycle()
    // The output in use has a page before music first plays on it; a forgotten device's page is on its way out
    var forgetting by remember { mutableStateOf(false) }
    val device = devices.byKey(key) ?: view.current?.takeIf { it.key == key }?.let { devices.deviceFor(it) }
    if (device == null) {
        // Gone without being forgotten here: an output not played on yet that was disconnected. Nothing to show
        if (!forgetting) LaunchedEffect(Unit) { onBack() }
        return
    }
    val isCurrent = key == view.current?.key
    val isConnected = key in view.connected
    val name = outputName(device.output)
    val kind = kindLabel(device.kind)
    val state = when {
        isCurrent -> R.string.state_playing_now to R.string.state_playing_now_lower
        device.kind == OutputKind.SPEAKER -> R.string.state_available to R.string.state_available
        isConnected -> R.string.state_connected to R.string.state_connected_lower
        else -> R.string.state_not_connected to R.string.state_not_connected_lower
    }
    val subtitle = kind?.let { stringResource(R.string.desc_kind_state, it, stringResource(state.second)) } ?: stringResource(state.first)
    val usb = device.kind == OutputKind.USB
    val forgot = stringResource(R.string.msg_forgot_device)
    val undo = stringResource(R.string.button_undo)
    SettingsPage(title = name, subtitle = subtitle, onBack = onBack, topInsets = topInsets) {
        if (usb) {
            // Can be set while the device is away: it applies when it is connected again
            SettingsGroup {
                row { shapes ->
                    SegmentedListItem(
                        checked = device.bitPerfect,
                        onCheckedChange = { on -> app.outputs.update { it.withBitPerfect(device.output, on) } },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        trailingContent = { Switch(checked = device.bitPerfect, onCheckedChange = null) },
                    ) {
                        Text(stringResource(R.string.label_bit_perfect_output))
                    }
                }
            }
            SettingsDescription(stringResource(R.string.msg_bit_perfect_device))
        }
        val stream = view.stream
        if (usb && isCurrent && device.bitPerfect && stream?.bitPerfect == true) {
            SettingsGroup(stringResource(R.string.title_now)) {
                row { shapes ->
                    SegmentedListItem(
                        onClick = {},
                        shapes = shapes,
                        colors = settingsRowColors(),
                        leadingContent = { RowIcon(R.drawable.ic_graphic_eq) },
                        supportingContent = { Text(stringResource(R.string.label_bit_perfect_playing)) },
                    ) {
                        Text(streamText(stream.bits, stream.sampleRate))
                    }
                }
            }
        }
        if (onOpenEqualizer != null) SettingsGroup(stringResource(R.string.title_equalizer)) {
            row { shapes ->
                val preset = presetName(view.equalizer, view.equalizer.refFor(key))
                val supporting = when {
                    usb && device.bitPerfect -> stringResource(R.string.desc_eq_not_applied_bit_perfect)
                    !isCurrent -> stringResource(R.string.desc_eq_set_on_page)
                    else -> null
                }
                // The Equalizer page shows the device playing now, so only that device's row leads there
                if (isCurrent) SettingsNavigationRow(shapes, R.drawable.ic_equalizer, preset, supporting, onClick = onOpenEqualizer)
                else SegmentedListItem(
                    onClick = {},
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_equalizer) },
                    supportingContent = supporting?.let { { Text(it) } },
                ) {
                    Text(preset)
                }
            }
        }
        if (usb && isConnected) {
            val groups = OutputDevices.grouped(system.bitPerfectFormats[key].orEmpty())
            // Read here: the group's rows are built outside composition's reach of these helpers
            val rows = groups.map { sampleSize(it) to rates(it) }
            SettingsGroup(stringResource(R.string.title_bit_perfect_formats)) {
                rows.forEach { (size, rates) ->
                    row { shapes ->
                        SegmentedListItem(onClick = {}, shapes = shapes, colors = settingsRowColors(), supportingContent = { Text(rates) }) {
                            Text(size)
                        }
                    }
                }
            }
            SettingsNote(stringResource(if (groups.isEmpty()) R.string.msg_no_bit_perfect else R.string.msg_other_formats_mixer))
        }
        if (usb && !isConnected) SettingsNote(stringResource(R.string.msg_connect_for_formats, name))
        if (device.kind == OutputKind.BLUETOOTH || device.kind == OutputKind.WIRED) SettingsNote(stringResource(R.string.msg_bit_perfect_needs_usb))
        // The phone's speaker is always there; a connected device would only come back at once
        if (!isConnected && device.kind != OutputKind.SPEAKER && devices.byKey(key) != null) {
            DestructiveButton(
                stringResource(R.string.button_forget_device),
                R.drawable.ic_delete,
                onClick = {
                    forgetting = true
                    onBack()
                    // In the app's scope: the snackbar and its Undo outlive this page
                    messageScope.launch {
                        // With its preset assignment (§7); Undo gives both back
                        val equalizer = app.equalizer.state.value
                        app.equalizer.update { it.unassigned(key) }
                        app.outputs.update { it.without(key) }
                        snackbars.currentSnackbarData?.dismiss()
                        val text = AnnotatedString.fromHtml(forgot.format(name.htmlEncode())).text
                        if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                            app.outputs.update { it.with(device) }
                            app.equalizer.update { it.restoredDevice(key, equalizer) }
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** "24 bit (stereo)" ("24 bity (stereo)"), "32 bit float (stereo)". */
@Composable
private fun sampleSize(group: OutputFormatGroup): String {
    val size = when {
        group.bits == 0 -> stringResource(R.string.label_other_encoding)
        group.float -> stringResource(R.string.label_bits_float, group.bits.toString())
        else -> pluralStringResource(R.plurals.label_bit_count, group.bits, group.bits)
    }
    return stringResource(R.string.label_format_channels, size, channelsText(group.channels))
}

/** "44.1, 48, 96 kHz"; "44,1; 48; 96 kHz" where the comma is the decimal mark. */
@Composable
private fun rates(group: OutputFormatGroup): String {
    val all = group.sampleRates.map { sampleRateText(it) }
    val comma = DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]).decimalSeparator == ','
    return stringResource(R.string.label_rates_khz, all.joinToString(if (comma) "; " else ", "))
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import sh.hnet.music.R
import sh.hnet.music.playback.OutputDevices
import sh.hnet.music.playback.OutputFormat
import sh.hnet.music.playback.OutputFormatGroup
import sh.hnet.music.playback.UsbOutput
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsNote
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.player.channelsText
import sh.hnet.music.ui.player.sampleRateText

/** The connected USB output, followed while something shows it. */
@Composable
fun rememberUsbOutput(): UsbOutput? {
    val context = LocalContext.current.applicationContext
    val flow = remember(context) { usbOutputOf(context) }
    val output by flow.collectAsStateWithLifecycle(null)
    return output
}

private fun usbOutputOf(context: Context): Flow<UsbOutput?> = OutputDevices.usbOutput(context)

/**
 * Settings › Output (bit-perfect stage B0): the connected USB output and the formats the system
 * offers for it, bit-perfect and mixed, under the Bit-perfect output switch (stage B1).
 */
@Composable
fun OutputScreen(onBack: () -> Unit) {
    val output = rememberUsbOutput()
    val app = LocalContext.current.applicationContext as MusicApplication
    val on by app.preferences.bitPerfect.collectAsStateWithLifecycle(false)
    val active by app.bitPerfect.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SettingsPage(title = stringResource(R.string.title_output), onBack = onBack) {
        // Can be set with no DAC connected: it takes effect when one is
        SettingsGroup {
            row { shapes ->
                SegmentedListItem(
                    checked = on,
                    onCheckedChange = { value -> scope.launch { app.preferences.setBitPerfect(value) } },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    trailingContent = { Switch(checked = on, onCheckedChange = null) },
                ) {
                    Text(stringResource(R.string.label_bit_perfect_output))
                }
            }
        }
        SettingsNote(stringResource(R.string.desc_bit_perfect_output))
        if (output == null) {
            SettingsNote(stringResource(R.string.msg_no_usb_output))
            return@SettingsPage
        }
        SettingsGroup(stringResource(R.string.title_connected_output)) {
            row { shapes ->
                SegmentedListItem(
                    onClick = {},
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_usb) },
                    supportingContent = if (active) ({ Text(stringResource(R.string.label_bit_perfect_playing)) }) else null,
                ) {
                    Text(output.name.ifEmpty { stringResource(R.string.label_usb_audio) })
                }
            }
        }
        FormatGroups(stringResource(R.string.title_bit_perfect_formats), output.bitPerfect)
        if (output.bitPerfect.isEmpty()) SettingsNote(stringResource(R.string.msg_no_bit_perfect))
        FormatGroups(stringResource(R.string.title_mixed_formats), output.mixed)
    }
}

@Composable
private fun FormatGroups(title: String, formats: List<OutputFormat>) {
    val groups = OutputDevices.grouped(formats)
    if (groups.isEmpty()) return
    // Read here: the group's rows are built outside composition's reach of these helpers
    val rows = groups.map { sampleSize(it) to rates(it) }
    SettingsGroup(title) {
        rows.forEach { (size, rates) ->
            row { shapes ->
                SegmentedListItem(onClick = {}, shapes = shapes, colors = settingsRowColors(), supportingContent = { Text(rates) }) {
                    Text(size)
                }
            }
        }
    }
}

/** "24 bit (stereo)", "32 bit float (stereo)". */
@Composable
private fun sampleSize(group: OutputFormatGroup): String {
    val size = when {
        group.bits == 0 -> stringResource(R.string.label_other_encoding)
        group.float -> stringResource(R.string.label_bits_float, group.bits.toString())
        else -> stringResource(R.string.label_bits, group.bits.toString())
    }
    return stringResource(R.string.label_format_channels, size, channelsText(group.channels))
}

/** "44.1, 48, 96 kHz". */
@Composable
private fun rates(group: OutputFormatGroup): String {
    val all = group.sampleRates.map { sampleRateText(it) }
    return stringResource(R.string.label_rates_khz, all.joinToString(", "))
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.data.Track
import sh.hnet.music.playback.AppliedGain
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.playback.CurrentOutput
import sh.hnet.music.playback.OutputKind
import sh.hnet.music.playback.SoundPath
import sh.hnet.music.playback.gainTags
import sh.hnet.music.tags.AudioCodec
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.settings.equalizerSummary
import sh.hnet.music.ui.settings.outputIcon
import sh.hnet.music.ui.settings.outputName
import sh.hnet.music.ui.settings.streamText

/**
 * Output details (SPEC-output-and-grid §A4–§A6): the path of the sound from the file to the device
 * in three steps – File, In Music, To the device – then the system's output switcher. A page over now
 * playing, as the equalizer's (owner's decision 23.11.2026, over the SPEC's sheet), with the device
 * playing now under the title. Follows a new
 * track and a new device while open (paused, the last stream, without a line saying so – owner's
 * decision); color marks what changes the sound (§A5.2).
 *
 * @param details Null with an empty queue: only the device and the switcher show
 * @param onOpenDevice A USB device's page in Settings › Output, by its key (without its Equalizer group)
 */
@Composable
fun OutputPathScreen(
    details: Track?,
    gain: AppliedGain?,
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    /** Settings › Playback, where ReplayGain is set. */
    onOpenPlayback: () -> Unit,
    /** Settings › Playback scrolled to its DSD group. */
    onOpenDsd: () -> Unit,
    /** The equalizer of the device playing now. */
    onOpenEqualizer: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MusicApplication
    val outputs by app.outputs.system.collectAsStateWithLifecycle()
    val devices by app.outputs.devices.collectAsStateWithLifecycle()
    val stream by app.bitPerfect.stream.collectAsStateWithLifecycle()
    val equalizer by app.equalizer.state.collectAsStateWithLifecycle()
    // The settings come from DataStore a moment later than the rest: the steps wait for them (the top bar doesn't), so
    // they show as they will stay
    val gainRead by app.preferences.gain.collectAsStateWithLifecycle(null)
    val dsdPcmRateRead by app.preferences.dsdPcmRate.collectAsStateWithLifecycle(null)
    val dsdBoostRead by app.preferences.dsdBoost.collectAsStateWithLifecycle(null)
    val output = outputs.current
    SettingsPage(
        title = stringResource(R.string.title_output),
        subtitle = output?.let { outputName(it) },
        onBack = onBack,
        topInsets = false,
    ) {
        val gainSettings = gainRead ?: return@SettingsPage
        val dsdPcmRate = dsdPcmRateRead ?: return@SettingsPage
        val dsdBoost = dsdBoostRead ?: return@SettingsPage
        // One child of the page's column: its 16 dp gaps would break the timeline's line between the steps
        Column(Modifier.fillMaxWidth()) {
            val usbSwitchOn = output?.kind == OutputKind.USB && devices.deviceFor(output).bitPerfect
            val path = details?.let {
                SoundPath.of(
                    SoundPath.Input(
                        dsdRate = it.sampleRate.takeIf { _ -> it.codec == AudioCodec.DSD },
                        sampleRate = it.sampleRate,
                        // Music's ALAC decoder hands 20-bit samples on as 24-bit ones (the low bits zero), carried exactly
                        bits = it.bitsPerSample.takeIf { _ -> it.codec != AudioCodec.DSD }?.let { b -> if (it.codec == AudioCodec.ALAC && b == 20) 24 else b },
                        channels = it.channels,
                        usbSwitchOn = usbSwitchOn,
                        stream = stream,
                        gainMode = gainSettings.mode,
                        gain = gain,
                        // The same file the gain was computed from (PlayerViewModel.gain reads it from details)
                        hasGainTags = it.gainTags().let { tags -> tags.trackGainDb != null || tags.albumGainDb != null },
                        equalizerOn = equalizer.enabled,
                        equalizerChanges = SoundPath.changes(equalizer.current),
                        dsdPcmRate = dsdPcmRate,
                        dsdBoost = dsdBoost,
                    ),
                )
            }
            if (details != null && path != null) {
                Step(R.drawable.ic_audio_file, stringResource(R.string.title_step_file), NodeLook.END) {
                    SettingsGroup {
                        row { shapes ->
                            SegmentedListItem(onClick = {}, shapes = shapes, colors = settingsRowColors()) { Text(fileText(details)) }
                        }
                    }
                }
                Step(R.drawable.ic_tune, stringResource(R.string.title_step_in_music), if (path.changes) NodeLook.CHANGES else NodeLook.NEUTRAL) {
                    SettingsGroup {
                        path.dsd?.let { dsd ->
                            row { shapes ->
                                Stage(shapes, R.drawable.ic_album, stringResource(R.string.label_stage_dsd), dsdText(dsd), path.dsdChanges, onOpenDsd)
                            }
                        }
                        row { shapes ->
                            Stage(shapes, R.drawable.ic_graphic_eq, stringResource(R.string.title_replaygain), gainText(path.gain), path.gainChanges, onOpenPlayback)
                        }
                        row { shapes ->
                            val text = when (path.equalizer) {
                                SoundPath.Equalizer.OFF -> stringResource(R.string.label_eq_off)
                                SoundPath.Equalizer.SKIPPED -> stringResource(R.string.label_skipped_bit_perfect)
                                // The preset of the device playing now, "(modified)" as the Settings row has it
                                SoundPath.Equalizer.ON -> equalizerSummary(equalizer)
                            }
                            Stage(shapes, R.drawable.ic_equalizer, stringResource(R.string.title_equalizer), text, path.equalizerChanges, onOpenEqualizer)
                        }
                        row { shapes ->
                            val text = stringResource(if (path.volume == SoundPath.Volume.DEVICE) R.string.label_volume_device else R.string.label_volume_system)
                            Stage(shapes, R.drawable.ic_volume_up, stringResource(R.string.label_stage_volume), text, path.volumeChanges)
                        }
                    }
                }
            }
            if (output != null) {
                Step(outputIcon(output.kind), stringResource(R.string.title_step_to_device), NodeLook.END, last = true) {
                    SettingsGroup {
                        // Only a USB device's page has more to show here – the bit-perfect switch and formats (owner's decision 27.11.2026)
                        val usb = output.kind == OutputKind.USB
                        row { shapes ->
                            SegmentedListItem(
                                onClick = { if (usb) onOpenDevice(output.key) },
                                shapes = shapes,
                                colors = settingsRowColors(),
                                trailingContent = if (usb) {
                                    { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }
                                } else null,
                                supportingContent = path?.let { { Text(routeText(output, it.route)) } },
                            ) {
                                Text(outputName(output))
                            }
                        }
                    }
                    if (path != null && path.route is SoundPath.Route.Mixer) {
                        val note = listOfNotNull(path.why?.let { whyText(it) }, stringResource(R.string.msg_system_may_convert)).joinToString(" ")
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                }
            }
            // Gone once the system had no switcher to show, as on the Output page
            var switcher by remember { mutableStateOf(true) }
            if (switcher) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { switcher = CurrentOutput.showSwitcher(context) }) {
                    Icon(painterResource(R.drawable.ic_speaker_group), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.button_switch_output), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** The node's colors: the file and the device always, In Music by whether anything changes the sound. */
private enum class NodeLook { END, CHANGES, NEUTRAL }

/**
 * A step of the timeline: a 40 dp node with the step's icon on the rail, a 2 dp line down to the
 * next node, and the step's title and rows beside it.
 */
@Composable
private fun Step(@DrawableRes icon: Int, title: String, look: NodeLook, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    val x = 20.dp.toPx()
                    drawLine(line, Offset(x, 44.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            },
    ) {
        Node(icon, look)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 12.dp)) {
            Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
private fun Node(@DrawableRes icon: Int, look: NodeLook) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (look) {
        NodeLook.END -> colors.secondaryContainer to colors.onSecondaryContainer
        NodeLook.CHANGES -> colors.tertiaryContainer to colors.onTertiaryContainer
        NodeLook.NEUTRAL -> colors.surface to colors.onSurfaceVariant
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container)
            .then(if (look == NodeLook.NEUTRAL) Modifier.border(1.dp, colors.outline, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
    }
}

/**
 * A stage of In Music: its icon colored when it changes the sound (§A5.2), its name and state. With
 * [onClick] it leads to the stage's settings, with a chevron as the device's row (owner's decision 27.11.2026).
 */
@Composable
private fun Stage(shapes: ListItemShapes, @DrawableRes icon: Int, name: String, state: String, changes: Boolean, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = if (changes) colors.tertiaryContainer to colors.onTertiaryContainer else colors.surfaceContainerHighest to colors.onSurfaceVariant
    SegmentedListItem(
        onClick = onClick ?: {},
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { IconBox(icon, container, content) },
        trailingContent = onClick?.let { { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) } },
        supportingContent = { Text(state) },
    ) {
        Text(name)
    }
}

@Composable
private fun IconBox(@DrawableRes icon: Int, container: Color, content: Color) {
    // A circle as the rows' RowIcon (the owner, 27 Nov 2026); its colors say whether the stage changes the sound
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = content)
    }
}

/** The chip's text; lossy files add their sample rate ("AAC 256 kb/s, 44.1 kHz"). DSD's already names DSD64. */
@Composable
private fun fileText(details: Track): String {
    val chip = techLabel(details)
    val lossy = details.codec != AudioCodec.DSD && details.bitsPerSample == null && details.sampleRate > 0
    return if (lossy) stringResource(R.string.label_stream_format, chip, sampleRateText(details.sampleRate)) else chip
}

@Composable
private fun dsdText(dsd: SoundPath.Dsd): String = when (dsd) {
    SoundPath.Dsd.AsDop -> stringResource(R.string.label_dsd_as_dop)
    is SoundPath.Dsd.ToPcm -> stringResource(if (dsd.boost) R.string.label_dsd_to_pcm_boost else R.string.label_dsd_to_pcm, sampleRateText(dsd.rate))
}

@Composable
private fun gainText(gain: SoundPath.Gain): String = when (gain) {
    SoundPath.Gain.Off -> stringResource(R.string.label_eq_off)
    SoundPath.Gain.Skipped -> stringResource(R.string.label_skipped_bit_perfect)
    SoundPath.Gain.NoTags -> stringResource(R.string.label_no_gain_tags)
    is SoundPath.Gain.PreAmp -> stringResource(R.string.label_gain_no_tags, decibels(gain.db))
    is SoundPath.Gain.Tagged -> stringResource(if (gain.album) R.string.label_gain_album else R.string.label_gain_track, decibels(gain.db))
}

/** "USB, bit-perfect: 24 bity, 96 kHz", "USB, DoP: DSD64 w 176,4 kHz, 24 bity", "Bluetooth, mikser systemowy: …". */
@Composable
private fun routeText(output: AudioOutput, route: SoundPath.Route): String = when (route) {
    is SoundPath.Route.BitPerfect -> stringResource(R.string.desc_bit_perfect_stream, streamText(route.bits, route.rate))
    is SoundPath.Route.Dop -> stringResource(R.string.desc_path_dop, dsdName(route.dsdRate), sampleRateText(route.rate), pluralStringResource(R.plurals.label_bit_count, route.bits, route.bits))
    is SoundPath.Route.Mixer -> {
        val kind = when (output.kind) {
            OutputKind.USB -> stringResource(R.string.label_kind_usb)
            OutputKind.BLUETOOTH -> stringResource(R.string.label_kind_bluetooth)
            OutputKind.WIRED -> stringResource(R.string.label_kind_wired)
            // The speaker and other outputs: the name already says what they are
            OutputKind.SPEAKER, OutputKind.OTHER -> null
        }
        if (kind != null) stringResource(R.string.desc_path_mixer, kind, sampleRateText(route.rate))
        else stringResource(R.string.desc_path_mixer_plain, sampleRateText(route.rate))
    }
}

@Composable
private fun whyText(why: SoundPath.Why): String = when (why) {
    is SoundPath.Why.NoDop -> stringResource(R.string.msg_why_not_dop, sampleRateText(why.rate))
    is SoundPath.Why.Format -> stringResource(R.string.msg_why_not_bit_perfect, pluralStringResource(R.plurals.label_bit_count, why.bits, why.bits), sampleRateText(why.rate))
    SoundPath.Why.Depth -> stringResource(R.string.msg_why_depth)
    SoundPath.Why.Channels -> stringResource(R.string.msg_why_channels)
}

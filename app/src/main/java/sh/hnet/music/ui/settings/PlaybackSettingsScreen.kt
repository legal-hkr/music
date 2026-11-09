@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.playback.GainSettings
import sh.hnet.music.playback.ReplayGainMode
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.player.decibels
import kotlin.math.roundToInt

fun ReplayGainMode.label(): Int = when (this) {
    ReplayGainMode.OFF -> R.string.label_rg_off
    ReplayGainMode.TRACK -> R.string.label_rg_track
    ReplayGainMode.ALBUM -> R.string.label_rg_album
    ReplayGainMode.AUTO -> R.string.label_rg_auto
}

private fun ReplayGainMode.description(): Int = when (this) {
    ReplayGainMode.OFF -> R.string.desc_rg_off
    ReplayGainMode.TRACK -> R.string.desc_rg_track
    ReplayGainMode.ALBUM -> R.string.desc_rg_album
    ReplayGainMode.AUTO -> R.string.desc_rg_auto
}

/** Settings › Playback (SPEC §11.4): ReplayGain mode, then the volume options it uses. */
@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit) {
    val preferences = (LocalContext.current.applicationContext as MusicApplication).preferences
    val settings by preferences.gain.collectAsStateWithLifecycle(GainSettings())
    val scope = rememberCoroutineScope()
    val groupLabel = stringResource(R.string.title_replaygain)

    SettingsPage(title = stringResource(R.string.title_settings_playback), onBack = onBack) {
        Column(Modifier.selectableGroup().semantics { contentDescription = groupLabel }) {
            SettingsGroup(stringResource(R.string.title_replaygain)) {
                ReplayGainMode.entries.forEach { mode ->
                    row { shapes ->
                        SegmentedListItem(
                            selected = settings.mode == mode,
                            onClick = { scope.launch { preferences.setGainMode(mode) } },
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { RadioButton(selected = settings.mode == mode, onClick = null) },
                            supportingContent = { Text(stringResource(mode.description())) },
                        ) {
                            Text(stringResource(mode.label()))
                        }
                    }
                }
            }
        }
        if (settings.mode != ReplayGainMode.OFF) {
            SettingsGroup(stringResource(R.string.title_volume)) {
                row { shapes -> PreampRow(shapes, settings.preampDb) { db -> scope.launch { preferences.setPreamp(db) } } }
                row { shapes ->
                    SegmentedListItem(
                        checked = settings.preventClipping,
                        onCheckedChange = { on -> scope.launch { preferences.setPreventClipping(on) } },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        trailingContent = { Switch(checked = settings.preventClipping, onCheckedChange = null) },
                        supportingContent = { Text(stringResource(R.string.desc_prevent_clipping)) },
                    ) {
                        Text(stringResource(R.string.label_prevent_clipping))
                    }
                }
            }
        }
    }
}

/** Pre-amp for untagged files: −12 to +6 dB in 0.5 dB steps; saved when the finger lets go. */
@Composable
private fun PreampRow(shapes: ListItemShapes, saved: Float, onChange: (Float) -> Unit) {
    val slider = remember(saved) { SliderState(value = saved, steps = 35, trackRange = -12f..6f) }
    val value = slider.value
    Surface(shape = shapes.shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_preamp), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.desc_preamp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.label_db, decibels(value)),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
            Slider(
                state = slider,
                onValueChange = { slider.value = (it * 2).roundToInt() / 2f },
                onValueChangeFinished = { onChange(slider.value) },
            )
        }
    }
}

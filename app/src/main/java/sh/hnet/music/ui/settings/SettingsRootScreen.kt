package sh.hnet.music.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.playback.GainSettings
import sh.hnet.music.playback.OutputKind
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsNavigationRow
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.formatCount
import sh.hnet.music.ui.library.LibraryView

/**
 * The Settings root, grouped as ComfyChair's: Library (music folders, views), Sound, App (language), Data (backup, reset), then About on its own.
 */
@Composable
fun SettingsRootScreen(
    viewModel: LibraryViewModel,
    onOpenLibrary: () -> Unit,
    onOpenViews: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as MusicApplication
    val version = sh.hnet.music.BuildConfig.VERSION_NAME.removePrefix("v")
    val gain by app.preferences.gain.collectAsStateWithLifecycle(GainSettings())
    // Read on every composition: changing it recreates the activity anyway
    val startView by app.preferences.startView.collectAsStateWithLifecycle(LibraryView.ALBUMS)
    val lastBackup by app.backup.lastBackup.collectAsStateWithLifecycle()
    val equalizer by app.equalizer.state.collectAsStateWithLifecycle()
    val outputs by app.outputs.system.collectAsStateWithLifecycle()
    val devices by app.outputs.devices.collectAsStateWithLifecycle()
    val bitPerfectActive by app.bitPerfect.active.collectAsStateWithLifecycle()
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = null) {
        SettingsGroup(stringResource(R.string.title_settings_group_library)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_folder, stringResource(R.string.title_music_folders), librarySummary(summary), onClick = onOpenLibrary)
            }
            row { shapes ->
                val opensOn = startView?.let { stringResource(R.string.label_opens_on_view, stringResource(it.label)) } ?: stringResource(R.string.label_opens_on_last)
                SettingsNavigationRow(shapes, R.drawable.ic_view_carousel, stringResource(R.string.title_library_views), opensOn, onClick = onOpenViews)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_sound)) {
            row { shapes ->
                SettingsNavigationRow(
                    shapes,
                    R.drawable.ic_volume_up,
                    stringResource(R.string.title_settings_playback),
                    stringResource(R.string.label_settings_replaygain_value, stringResource(gain.mode.label())),
                    onClick = onOpenPlayback,
                )
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_equalizer, stringResource(R.string.title_equalizer), equalizerSummary(equalizer, bitPerfectActive), onClick = onOpenEqualizer)
            }
            row { shapes ->
                // The device playing now (SPEC-output §3)
                val current = outputs.current ?: AudioOutput(OutputKind.SPEAKER, "")
                val name = outputName(current)
                val summary = if (devices.deviceFor(current).bitPerfect) stringResource(R.string.label_output_bit_perfect, name) else name
                SettingsNavigationRow(shapes, R.drawable.ic_speaker_group, stringResource(R.string.title_output), summary, onClick = onOpenOutput)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                val last = lastBackup?.let { stringResource(R.string.label_last_backup, backupDate(context, it)) } ?: stringResource(R.string.label_no_backup)
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup_restore), last, onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}

@Composable
private fun librarySummary(summary: LibrarySummary): String = when {
    summary.folders == 0 -> stringResource(R.string.label_no_folders)
    summary.scanning > 0 -> pluralStringResource(R.plurals.label_scanning_folders, summary.scanning, summary.scanning)
    summary.unavailable > 0 -> stringResource(R.string.label_folder_needs_attention)
    summary.tracks == 0 -> stringResource(R.string.label_no_playable_files)
    else -> stringResource(
        R.string.label_library_summary,
        pluralStringResource(R.plurals.label_folder_count, summary.folders, formatCount(summary.folders)),
        pluralStringResource(R.plurals.label_track_count, summary.tracks, formatCount(summary.tracks)),
    )
}

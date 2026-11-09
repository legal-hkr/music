package sh.hnet.music.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsNavigationRow
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.formatCount

/**
 * The Settings root, grouped as ComfyChair's: Library, Sound, App (language), Data (reset; backup
 * comes with its stage), then About on its own.
 */
@Composable
fun SettingsRootScreen(
    viewModel: LibraryViewModel,
    onOpenLibrary: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { appVersion(context).first }
    val gain by (context.applicationContext as sh.hnet.music.MusicApplication).preferences.gain.collectAsStateWithLifecycle(sh.hnet.music.playback.GainSettings())
    // Read on every composition: changing it recreates the activity anyway
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = null) {
        SettingsGroup(stringResource(R.string.title_settings_group_library)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_library_music, stringResource(R.string.title_settings_library), librarySummary(summary), onClick = onOpenLibrary)
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
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
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

/** Version name and code from the installed package (no BuildConfig needed). */
fun appVersion(context: android.content.Context): Pair<String, Long> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.versionName.orEmpty().removePrefix("v") to info.longVersionCode
}

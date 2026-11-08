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

@Composable
fun SettingsRootScreen(viewModel: LibraryViewModel, onOpenLibrary: () -> Unit, onOpenAbout: () -> Unit) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { appVersion(context).first }
    SettingsPage(title = stringResource(R.string.title_settings), onBack = null) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_library_music, stringResource(R.string.title_settings_library), librarySummary(summary), onOpenLibrary)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onOpenAbout)
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

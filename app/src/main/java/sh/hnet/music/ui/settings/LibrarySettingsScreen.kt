@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import sh.hnet.music.ui.components.SingleChoiceDialog
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.SKIP_SHORT_CHOICES
import sh.hnet.music.data.LibraryFolders
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsNavigationRow
import sh.hnet.music.ui.components.SettingsNote
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.formatCount
import sh.hnet.music.ui.components.settingsRowColors

/**
 * Settings › Music folders: the picked folders, their scans and access, and adding one; then
 * Scanning – Scan now and Skip short files (SPEC-settings-v2 §5).
 */
@Composable
fun LibrarySettingsScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scan by viewModel.scanStatus.collectAsStateWithLifecycle()
    val lastScan by viewModel.lastScan.collectAsStateWithLifecycle()
    val skipShort by viewModel.skipShortSeconds.collectAsStateWithLifecycle()
    var choosingSkip by rememberSaveable { mutableStateOf(false) }
    // The folder being picked again after it lost access; saved across the picker's activity
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.onFolderPicked(uri, replacing)
        replacing = null
    }
    // Grants can be taken away in the system's settings while the app is in the background
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }

    SettingsPage(title = stringResource(R.string.title_music_folders), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.title_folders)) {
            folders.forEach { folder ->
                row { shapes ->
                    FolderRowItem(shapes, folder) {
                        if (folder.unavailable) {
                            replacing = folder.folder.treeUri
                            pick.launch(LibraryFolders.documentUri(folder.folder.treeUri))
                        } else {
                            onOpenFolder(folder.folder.treeUri)
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { replacing = null; pick.launch(null) }) {
                Icon(painterResource(R.drawable.ic_create_new_folder), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.button_add_folder), modifier = Modifier.padding(start = 8.dp))
            }
            SettingsNote(stringResource(R.string.msg_only_listed_folders))
        }
        if (folders.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.title_scanning_group)) {
                row { shapes -> ScanNowRow(shapes, scan, lastScan, viewModel::scanNow) }
                row { shapes ->
                    SettingsNavigationRow(
                        shapes,
                        R.drawable.ic_timer_off,
                        stringResource(R.string.label_skip_short),
                        skipValue(skipShort),
                        onClick = { choosingSkip = true },
                    )
                }
            }
        }
    }

    if (choosingSkip) {
        SkipShortDialog(
            initial = skipShort,
            onDismiss = { choosingSkip = false },
            onSave = {
                choosingSkip = false
                viewModel.setSkipShortSeconds(it)
            },
        )
    }
}

/**
 * Scan now (§5.1); while any scan runs, the same row shows it: a loading indicator for the icon,
 * "Scanning…" and the files read so far over all folders, and it can't be tapped.
 */
@Composable
private fun ScanNowRow(shapes: ListItemShapes, scan: ScanStatus?, lastScan: Long?, onScan: () -> Unit) {
    val scanningState = stringResource(R.string.label_scanning_headline)
    SegmentedListItem(
        onClick = onScan,
        enabled = scan == null,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            if (scan == null) RowIcon(R.drawable.ic_refresh)
            else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
        },
        supportingContent = {
            Text(
                when {
                    scan == null -> stringResource(R.string.label_last_scan, lastScan?.let { lastScanText(it) } ?: stringResource(R.string.label_never_scanned))
                    !scan.counted -> stringResource(R.string.msg_scan_counting)
                    else -> stringResource(R.string.label_scanning_files, formatCount(scan.done), formatCount(scan.total))
                },
            )
        },
        modifier = Modifier.semantics { if (scan != null) stateDescription = scanningState },
    ) {
        Text(stringResource(if (scan == null) R.string.label_scan_now else R.string.label_scanning_headline))
    }
}

/** "today, 14:02", "yesterday, 9:15", "3 days ago, 20:40" – the day relative, lower-case as in a sentence. */
@Composable
private fun lastScanText(time: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val text = DateUtils.getRelativeDateTimeString(context, time, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
    return text.replaceFirstChar { it.lowercase(locale) }
}

@Composable
private fun skipValue(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.label_skip_off) else stringResource(R.string.label_skip_value, seconds)

/** Skip short files (§5.2): a single choice, as the export dialog; Save applies it at once, no rescan. */
@Composable
private fun SkipShortDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) =
    SingleChoiceDialog(
        title = AnnotatedString(stringResource(R.string.label_skip_short)),
        options = SKIP_SHORT_CHOICES,
        initial = initial,
        label = { seconds -> if (seconds == 0) stringResource(R.string.label_skip_off) else stringResource(R.string.label_shorter_than, seconds) },
        confirmLabel = stringResource(R.string.button_save),
        onConfirm = onSave,
        onDismiss = onDismiss,
        description = stringResource(R.string.msg_skip_short_explain),
    )

@Composable
private fun FolderRowItem(shapes: ListItemShapes, row: FolderRow, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            if (row.unavailable) RowIcon(R.drawable.ic_folder_off, error = true) else RowIcon(R.drawable.ic_folder)
        },
        trailingContent = if (row.unavailable) null else {
            { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.location?.let { Text(it, maxLines = 1) }
                val scan = row.scan
                when {
                    row.unavailable -> {
                        Text(stringResource(R.string.msg_folder_no_access))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_folder_open),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.button_choose_folder_again), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    scan != null -> ScanProgress(scan)
                    else -> Text(folderCounts(row.tracks, row.skipped))
                }
            }
        },
    ) {
        Text(row.folder.displayName)
    }
}

@Composable
private fun ScanProgress(scan: sh.hnet.music.library.FolderScan) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        if (scan.listing || scan.total == 0) {
            Text(stringResource(R.string.msg_scan_listing))
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        } else {
            // Announced politely, at 25 % steps only
            val step = scan.done * 4 / scan.total
            Text(
                stringResource(R.string.label_scanning_progress, formatCount(scan.done), formatCount(scan.total)),
                modifier = Modifier.semantics { if (step >= 0) liveRegion = LiveRegionMode.Polite },
            )
            LinearWavyProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun folderCounts(tracks: Int, skipped: Int): String {
    val trackText = pluralStringResource(R.plurals.label_track_count, tracks, formatCount(tracks))
    if (skipped == 0) return trackText
    return stringResource(R.string.label_folder_counts, trackText, pluralStringResource(R.plurals.label_skipped_count, skipped, formatCount(skipped)))
}

/** Shows the view model's messages in the app's snackbar host. */
@Composable
fun LibraryMessages(viewModel: LibraryViewModel, show: suspend (LibraryMessage) -> Unit) {
    LaunchedEffect(viewModel) { viewModel.messages.collect { show(it) } }
}

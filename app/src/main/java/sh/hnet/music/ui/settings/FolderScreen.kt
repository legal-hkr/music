@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.ui.components.formatDuration
import sh.hnet.music.data.SkipReason
import sh.hnet.music.data.SkippedFile
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.boldNamesResource
import sh.hnet.music.ui.components.formatCount
import sh.hnet.music.ui.components.settingsRowColors

private const val SHOWN_PER_GROUP = 50

/** How a skipped file is presented: the scanner's reason refined by what the file claims to be. */
private enum class SkipGroup(val title: Int, @param:DrawableRes val icon: Int) {
    /** In the index, left out by Skip short files (SPEC-settings-v2 §5.3); first. */
    TOO_SHORT(R.string.title_skipped_short, R.drawable.ic_timer_off),
    NOT_SUPPORTED(R.string.title_skipped_not_supported, R.drawable.ic_music_off),
    DRM(R.string.title_skipped_drm, R.drawable.ic_lock),
    UNREADABLE(R.string.title_skipped_unreadable, R.drawable.ic_error),
}

private val DSD = setOf("dsf", "dff")

/** Extensions of formats the parser reads: such a file that wasn't read is damaged, not unsupported. */
private val READABLE = setOf("flac", "mp3", "m4a", "m4b", "mp4", "ogg", "oga", "opus", "mp2")

private fun SkippedFile.extension() = relativePath.substringAfterLast('.', "").lowercase()

private fun SkippedFile.group(): SkipGroup = when (reason) {
    SkipReason.PROTECTED -> SkipGroup.DRM
    SkipReason.UNREADABLE -> SkipGroup.UNREADABLE
    SkipReason.UNSUPPORTED -> if (extension() in READABLE) SkipGroup.UNREADABLE else SkipGroup.NOT_SUPPORTED
}

private fun SkippedFile.message(): Int = when {
    reason == SkipReason.PROTECTED -> R.string.msg_skipped_drm
    reason == SkipReason.UNREADABLE -> R.string.msg_skipped_unreadable
    extension() in READABLE -> R.string.msg_skipped_damaged
    extension() in DSD -> R.string.msg_skipped_dsd
    else -> R.string.msg_skipped_format
}

/** One folder: the files that aren't in the library and why, and removing the folder. */
@Composable
fun FolderScreen(viewModel: LibraryViewModel, treeUri: String, onBack: () -> Unit) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val row = folders.firstOrNull { it.folder.treeUri == treeUri }
    val skipped by remember(treeUri) { viewModel.skippedFiles(treeUri) }.collectAsStateWithLifecycle(emptyList())
    val short by remember(treeUri) { viewModel.shortFiles(treeUri) }.collectAsStateWithLifecycle(emptyList())
    val threshold by viewModel.skipShortSeconds.collectAsStateWithLifecycle()
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateOf(setOf<SkipGroup>()) }
    val name = row?.folder?.displayName.orEmpty()

    SettingsPage(title = name, subtitle = row?.location, onBack = onBack) {
        // Each group's files as path and reason
        val groups = buildMap<SkipGroup, List<Pair<String, String>>> {
            if (short.isNotEmpty()) put(SkipGroup.TOO_SHORT, short.map { it.relativePath to stringResource(R.string.msg_skipped_short, threshold, formatDuration(it.durationMs)) })
            skipped.groupBy { it.group() }.forEach { (group, files) -> put(group, files.map { it.relativePath to stringResource(it.message()) }) }
        }
        if (groups.isEmpty()) {
            Text(
                stringResource(R.string.msg_no_skipped_files),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        SkipGroup.entries.forEach { group ->
            val files = groups[group] ?: return@forEach
            val all = group in expanded.value
            SettingsGroup(title = stringResource(group.title)) {
                (if (all) files else files.take(SHOWN_PER_GROUP)).forEach { (path, reason) ->
                    row { shapes ->
                        SegmentedListItem(
                            onClick = {},
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { RowIcon(group.icon) },
                            supportingContent = { Text(reason) },
                        ) {
                            Text(path)
                        }
                    }
                }
            }
            if (!all && files.size > SHOWN_PER_GROUP) {
                TextButton(onClick = { expanded.value += group }) {
                    Text(stringResource(R.string.button_show_all, formatCount(files.size)))
                }
            }
        }
        OutlinedButton(
            onClick = { confirmRemove = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.button_remove_folder), modifier = Modifier.padding(start = 8.dp))
        }
    }

    if (confirmRemove && row != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(boldNamesResource(R.string.title_remove_folder, name)) },
            text = { Text(pluralStringResource(R.plurals.msg_remove_folder, row.tracks, formatCount(row.tracks))) },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.button_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        viewModel.removeFolder(treeUri)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.button_remove)) }
            },
        )
    }
}

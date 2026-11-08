package sh.hnet.music.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.data.LibraryFolders
import sh.hnet.music.library.ScanProgress

/** The folders the app may read, what a scan found in each, and the scan's progress. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FoldersScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MusicApplication
    val resolver = context.contentResolver
    val folderDao = remember { app.database.libraryFolderDao() }
    val trackDao = remember { app.database.trackDao() }
    val scanner = app.scanner
    val folders by remember { folderDao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val trackCounts by remember { trackDao.observeTrackCounts() }.collectAsStateWithLifecycle(emptyList())
    val skippedCounts by remember { trackDao.observeSkippedCounts() }.collectAsStateWithLifecycle(emptyList())
    val progress by scanner.progress.collectAsStateWithLifecycle()
    val unavailable by scanner.unavailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            LibraryFolders.add(resolver, folderDao, uri)
            scanner.requestScan()
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.title_folders)) },
                    actions = {
                        IconButton(onClick = scanner::requestScan, enabled = progress == null && folders.isNotEmpty()) {
                            Icon(
                                painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.content_description_rescan),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                progress?.let { ScanStatus(it) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.button_add_folder)) },
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.msg_no_folders),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val tracksByFolder = trackCounts.associate { it.folderUri to it.count }
            val skippedByFolder = skippedCounts.associate { it.folderUri to it.count }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(folders, key = { it.treeUri }) { folder ->
                    val tracks = tracksByFolder[folder.treeUri] ?: 0
                    val skipped = skippedByFolder[folder.treeUri] ?: 0
                    ListItem(
                        headlineContent = { Text(folder.displayName) },
                        supportingContent = {
                            if (folder.treeUri in unavailable) {
                                Text(stringResource(R.string.error_folder_unavailable), color = MaterialTheme.colorScheme.error)
                            } else {
                                val text = pluralStringResource(R.plurals.label_folder_tracks, tracks, tracks) +
                                    if (skipped > 0) " – " + pluralStringResource(R.plurals.label_folder_skipped, skipped, skipped) else ""
                                Text(text)
                            }
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                        trailingContent = {
                            IconButton(
                                onClick = { scope.launch { LibraryFolders.remove(resolver, folderDao, trackDao, folder.treeUri) } },
                                enabled = progress == null,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.content_description_remove_folder),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanStatus(progress: ScanProgress) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress.listing || progress.total == 0) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.msg_scan_listing), style = MaterialTheme.typography.bodyMedium)
        } else {
            LinearWavyProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.msg_scan_reading, progress.done, progress.total),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

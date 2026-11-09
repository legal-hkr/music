@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.music.R
import sh.hnet.music.ui.welcome.PlaylistsReadyCard
import androidx.compose.foundation.layout.Arrangement
import sh.hnet.music.ui.components.EmptyState

/**
 * No folder (any more): the first run has its own wizard (ui/welcome), so this is the plain empty
 * state with Add folder, as ComfyChair doesn't return to its wizard either. Playlists restored
 * before any folder was added wait for one (SPEC-settings-v2 §7.4).
 */
@Composable
fun NoFoldersState(readyPlaylists: Int, onFolderPicked: (Uri) -> Unit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let(onFolderPicked) }
    EmptyState(R.drawable.ic_folder, stringResource(R.string.msg_no_folders), Modifier.statusBarsPadding()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (readyPlaylists > 0) PlaylistsReadyCard(readyPlaylists)
            Button(onClick = { pick.launch(null) }) {
                Icon(painterResource(R.drawable.ic_create_new_folder), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.button_add_folder), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Folders added, none of their files plays (e.g. only DSD) – SPEC §12, 11c. */
@Composable
fun NothingPlayableState(onSeeSkippedFiles: () -> Unit) {
    EmptyState(R.drawable.ic_music_off, stringResource(R.string.msg_nothing_playable), Modifier.statusBarsPadding()) {
        TextButton(onClick = onSeeSkippedFiles) { Text(stringResource(R.string.button_see_skipped_files)) }
    }
}


/** No track to show because folders lost access (SPEC §13 leaves the Library's look open). */
@Composable
fun LostAccessState(onFix: () -> Unit) {
    EmptyState(R.drawable.ic_folder_off, stringResource(R.string.label_folder_needs_attention), Modifier.statusBarsPadding()) {
        TextButton(onClick = onFix) { Text(stringResource(R.string.button_fix)) }
    }
}

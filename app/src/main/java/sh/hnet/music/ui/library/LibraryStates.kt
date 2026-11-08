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
import sh.hnet.music.ui.components.EmptyState

/**
 * First run, no folder yet (SPEC §12, 11a): what the app is about and one action. The app mark is
 * a stand-in until the launcher icon exists.
 */
@Composable
fun WelcomeState(onFolderPicked: (Uri) -> Unit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let(onFolderPicked) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(144.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_library_music), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.title_welcome), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.msg_welcome), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        val height = ButtonDefaults.MediumContainerHeight
        Button(onClick = { pick.launch(null) }, modifier = Modifier.fillMaxWidth().height(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
            Icon(painterResource(R.drawable.ic_create_new_folder), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Text(stringResource(R.string.button_add_folder), style = ButtonDefaults.textStyleFor(height), modifier = Modifier.padding(start = 8.dp))
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

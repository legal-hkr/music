package sh.hnet.music.ui.library

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.music.R
import sh.hnet.music.ui.components.EmptyState

/** The Library destination. Its views (Albums, Artists, Tracks) arrive in S2–S3. */
@Composable
fun LibraryScreen() {
    EmptyState(R.drawable.ic_library_music, stringResource(R.string.msg_library_filling), Modifier.statusBarsPadding())
}

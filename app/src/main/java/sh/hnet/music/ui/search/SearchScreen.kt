package sh.hnet.music.ui.search

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.music.R
import sh.hnet.music.ui.components.EmptyState

/** The Search destination. The field and results arrive in S6. */
@Composable
fun SearchScreen() {
    EmptyState(R.drawable.ic_search, stringResource(R.string.msg_search_empty), Modifier.statusBarsPadding())
}

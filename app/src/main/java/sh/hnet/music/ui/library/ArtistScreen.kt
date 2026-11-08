@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import sh.hnet.music.R
import sh.hnet.music.ui.components.EmptyState
import sh.hnet.music.ui.components.TooltipIconButton

/** An artist's page; its content arrives in S3. */
@Composable
fun ArtistScreen(name: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        EmptyState(R.drawable.ic_person, stringResource(R.string.msg_view_coming))
    }
}

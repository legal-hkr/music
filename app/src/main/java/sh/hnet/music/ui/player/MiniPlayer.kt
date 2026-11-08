@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.playback.PlayerState
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.TooltipIconButton

/** The bar above the navigation bar while something is queued (SPEC §9.1). */
@Composable
fun MiniPlayer(
    track: LibraryTrack?,
    state: PlayerState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = track?.displayTitle.orEmpty()
    val artists = track?.artists?.joinToString(", ").orEmpty()
    val description = stringResource(R.string.content_description_now_playing, title, artists)
    val playLabel = stringResource(if (state.isPlaying) R.string.content_description_pause else R.string.content_description_play)
    val nextLabel = stringResource(R.string.content_description_next)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Cover(track, RoundedCornerShape(12.dp), Modifier.size(48.dp), player = true)
            Spacer(Modifier.width(12.dp))
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = description
                    customActions = listOf(
                        CustomAccessibilityAction(playLabel) { onPlayPause(); true },
                        CustomAccessibilityAction(nextLabel) { onNext(); true },
                    )
                },
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artists.isNotEmpty()) {
                    Text(artists, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TooltipIconButton(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, playLabel, onPlayPause)
            TooltipIconButton(R.drawable.ic_skip_next, nextLabel, onNext, enabled = state.hasNext)
        }
    }
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.library.Album
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.components.TrackRow
import sh.hnet.music.ui.components.formatCount

/** An album: header, Play and Shuffle, its tracks by disc (SPEC §4.3). */
@Composable
fun AlbumScreen(viewModel: BrowseViewModel, key: String, navigation: TrackNavigation, onBack: () -> Unit, onOpenArtist: (String) -> Unit) {
    val album by remember(key) { viewModel.album(key) }.collectAsStateWithLifecycle(null)
    val current by viewModel.playState.collectAsStateWithLifecycle(null to false)
    val list = rememberLazyListState()
    val titleShown by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val shown = album

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                AnimatedVisibility(titleShown && shown != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(shown?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        if (shown == null) return@Column
        val rowNavigation = navigation.withoutAlbum()
        val multiDisc = shown.tracks.mapNotNull { it.discNumber }.distinct().size > 1
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") { Header(shown, onOpenArtist) }
            item(key = "actions") {
                PlayButtons(onPlay = { viewModel.play(shown.tracks, shuffle = false) }, onShuffle = { viewModel.play(shown.tracks, shuffle = true) })
            }
            var lastDisc: Int? = null
            shown.tracks.forEachIndexed { index, track ->
                val disc = track.discNumber ?: 1
                if (multiDisc && disc != lastDisc) {
                    item(key = "disc$disc") {
                        Text(
                            stringResource(R.string.label_disc, disc),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                }
                lastDisc = disc
                item(key = track.documentUri) {
                    TrackRow(
                        track = track,
                        supporting = if (shown.showsArtistOf(track)) track.artists.joinToString(", ").ifEmpty { null } else null,
                        state = BrowseViewModel.stateOf(track, current),
                        navigation = rowNavigation,
                        onClick = { viewModel.playFrom(shown.tracks, index) },
                        number = track.trackNumber,
                        showNumberColumn = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(album: Album, onOpenArtist: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Cover(album.coverTrack, RoundedCornerShape(12.dp), Modifier.size(136.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(
                album.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (album.isVarious) {
                Text(stringResource(R.string.label_various_artists), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (album.shownArtists.isNotEmpty()) {
                // The album artist leads to the artist page (the first one when there are several)
                Text(
                    album.shownArtists.joinToString(", "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenArtist(album.shownArtists.first()) },
                )
            }
            val parts = listOfNotNull(
                album.year?.toString(),
                pluralStringResource(R.plurals.label_track_count, album.tracks.size, formatCount(album.tracks.size)),
                albumDuration(album.durationMs),
            )
            Text(parts.joinToString(" – "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Absent for an unknown length. */
@Composable
private fun albumDuration(ms: Long): String? = if (ms <= 0) null else sh.hnet.music.ui.components.durationText(ms)

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import sh.hnet.music.library.Artist
import sh.hnet.music.library.Tracks
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.components.TrackRow
import sh.hnet.music.ui.components.formatCount
import java.util.Locale

private const val TRACKS_SHOWN = 5

/** An artist (SPEC §6.2): own albums, Appears on with their tracks, then their tracks. */
@Composable
fun ArtistScreen(
    viewModel: BrowseViewModel,
    name: String,
    navigation: TrackNavigation,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onShowAllTracks: () -> Unit,
) {
    val artist by remember(name) { viewModel.artist(name) }.collectAsStateWithLifecycle(null)
    val current by viewModel.playState.collectAsStateWithLifecycle(null to false)
    val list = rememberLazyListState()
    val titleShown by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val shown = artist

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                AnimatedVisibility(titleShown, enter = fadeIn(), exit = fadeOut()) {
                    Text(shown?.name ?: name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        if (shown == null) return@Column
        val byTitle = remember(shown) { Tracks.sort(shown.tracks, sh.hnet.music.library.TrackSort.TITLE, Locale.getDefault()).items }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") { Header(shown) }
            item(key = "actions") {
                val order = remember(shown) { shown.playOrder() }
                PlayButtons(onPlay = { viewModel.play(order, shuffle = false) }, onShuffle = { viewModel.play(order, shuffle = true) })
            }
            if (shown.ownAlbums.isNotEmpty()) {
                item(key = "albumsTitle") { SectionTitle(stringResource(R.string.title_artist_albums)) }
                item(key = "albums") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shown.ownAlbums, key = { it.key }) { album -> SmallAlbumTile(album) { onOpenAlbum(album.key) } }
                    }
                }
            }
            if (shown.appearsOn.isNotEmpty()) {
                item(key = "appearsTitle") { SectionTitle(stringResource(R.string.title_artist_appears_on)) }
                shown.appearsOn.forEach { album ->
                    item(key = "appears:${album.key}") { AlbumRow(album) { onOpenAlbum(album.key) } }
                    val onAlbum = shown.tracksOn(album)
                    onAlbum.forEachIndexed { index, track ->
                        item(key = "appears:${album.key}:${track.documentUri}") {
                            TrackRow(
                                track = track,
                                supporting = track.artists.joinToString(", "),
                                state = BrowseViewModel.stateOf(track, current),
                                navigation = navigation,
                                onClick = { viewModel.playFrom(onAlbum, index) },
                                startPadding = 80,
                            )
                        }
                    }
                }
            }
            if (shown.tracks.isNotEmpty()) {
                item(key = "tracksTitle") { SectionTitle(stringResource(R.string.title_artist_tracks)) }
                byTitle.take(TRACKS_SHOWN).forEachIndexed { index, track ->
                    item(key = "tracks:${track.documentUri}") {
                        TrackRow(
                            track = track,
                            supporting = trackLine(track),
                            state = BrowseViewModel.stateOf(track, current),
                            navigation = navigation,
                            onClick = { viewModel.playFrom(byTitle, index) },
                        )
                    }
                }
                if (byTitle.size > TRACKS_SHOWN) {
                    item(key = "showAll") {
                        TextButton(onClick = onShowAllTracks, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(pluralStringResource(R.plurals.button_show_all_tracks, byTitle.size, formatCount(byTitle.size)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(artist: Artist) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            artist.name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        val parts = listOfNotNull(
            artist.ownAlbums.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.label_album_count, it, formatCount(it)) },
            pluralStringResource(R.plurals.label_track_count, artist.tracks.size, formatCount(artist.tracks.size)),
        )
        Text(parts.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun SmallAlbumTile(album: Album, onClick: () -> Unit) {
    Column(Modifier.width(136.dp).clickable(onClick = onClick)) {
        Cover(album.coverTrack, RoundedCornerShape(12.dp), Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.year?.let {
            Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An album as a row: cover, title, "album artist – year" (Appears on, search results). */
@Composable
fun AlbumRow(album: Album, onClick: () -> Unit) {
    val artist = albumArtistText(album)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Cover(album.coverTrack, RoundedCornerShape(12.dp), Modifier.size(48.dp)) },
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(artist.ifEmpty { null }, album.year?.toString()).joinToString(" – "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
    )
}

/** Play and Shuffle side by side, medium buttons (SPEC §4.3, §6.2). */
@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    val height = ButtonDefaults.MediumContainerHeight
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f).height(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
            Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Text(stringResource(R.string.button_play), style = ButtonDefaults.textStyleFor(height), modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f).height(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Text(stringResource(R.string.button_shuffle), style = ButtonDefaults.textStyleFor(height), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** An artist's tracks, all of them, with the Tracks view's sort (SPEC §6.2 "Show all"). */
@Composable
fun ArtistTracksScreen(viewModel: BrowseViewModel, name: String, navigation: TrackNavigation, onBack: () -> Unit) {
    val tracks by remember(name) { viewModel.artistTracks(name) }.collectAsStateWithLifecycle(null)
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        tracks?.let { TrackList(viewModel, it, state, navigation) }
    }
}

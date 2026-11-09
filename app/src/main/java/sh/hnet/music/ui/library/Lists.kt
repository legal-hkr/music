@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.library

import sh.hnet.music.ui.components.LocalBottomOverlay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.Artist
import sh.hnet.music.library.Sorted
import sh.hnet.music.library.TrackSort
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.FastScroller
import sh.hnet.music.ui.components.SortChip
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.components.TrackRow
import sh.hnet.music.ui.components.formatCount

/** A list's header row: the count at start, optionally the sort chip at end (SPEC §4.1). */
@Composable
fun ListHeader(count: String, modifier: Modifier = Modifier, sort: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            count,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        sort?.invoke()
    }
}

fun TrackSort.label(): Int = when (this) {
    TrackSort.TITLE -> R.string.label_sort_title
    TrackSort.ARTIST -> R.string.label_sort_artist
    TrackSort.ALBUM -> R.string.label_sort_album
    TrackSort.RECENTLY_ADDED -> R.string.label_sort_recently_added
    TrackSort.MOST_PLAYED -> R.string.label_sort_most_played
}

/** "Artists – Album", each part left out when missing (SPEC §5.1). */
fun trackLine(track: LibraryTrack): String? =
    listOfNotNull(track.artists.joinToString(", ").ifEmpty { null }, track.album?.ifBlank { null })
        .joinToString(" – ").ifEmpty { null }

/** Tracks with count, sort chip and fast scroller: the Tracks view and an artist's "Show all" page. */
@Composable
fun TrackList(
    viewModel: BrowseViewModel,
    sorted: Sorted<LibraryTrack>,
    state: LazyListState,
    navigation: TrackNavigation,
    modifier: Modifier = Modifier,
) {
    val sort by viewModel.trackSort.collectAsStateWithLifecycle()
    val current by viewModel.playState.collectAsStateWithLifecycle(null to false)
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp + LocalBottomOverlay.current), modifier = Modifier.fillMaxSize()) {
            item(key = "header", contentType = "header") {
                ListHeader(
                    pluralStringResource(R.plurals.label_track_count, sorted.items.size, formatCount(sorted.items.size)),
                    Modifier.padding(horizontal = 16.dp),
                ) {
                    SortChip(TrackSort.entries, sort, { stringResource(it.label()) }, viewModel::setTrackSort)
                }
            }
            itemsIndexed(sorted.items, key = { _, track -> track.documentUri }, contentType = { _, _ -> "track" }) { index, track ->
                TrackRow(
                    track = track,
                    supporting = trackLine(track),
                    state = BrowseViewModel.stateOf(track, current),
                    navigation = navigation,
                    onClick = { viewModel.playFrom(sorted.items, index) },
                )
            }
        }
        FastScroller(state, sorted.sections, firstItem = 1, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** An artist row (SPEC §6.1): the newest own album's cover in a circle, name, what they have. */
@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        leadingContent = { ArtistAvatar(artist, 48) },
        supportingContent = { Text(artistSummary(artist), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ArtistAvatar(artist: Artist, size: Int) {
    val avatar = artist.avatar
    if (avatar != null) {
        Cover(avatar, CircleShape, Modifier.size(size.dp))
    } else {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** "3 albums", else "Appears on 2 albums", else the number of tracks. */
@Composable
fun artistSummary(artist: Artist): String = when {
    artist.ownAlbums.isNotEmpty() -> pluralStringResource(R.plurals.label_album_count, artist.ownAlbums.size, formatCount(artist.ownAlbums.size))
    artist.appearsOn.isNotEmpty() -> pluralStringResource(R.plurals.label_artist_appears_on, artist.appearsOn.size, formatCount(artist.appearsOn.size))
    else -> pluralStringResource(R.plurals.label_track_count, artist.tracks.size, formatCount(artist.tracks.size))
}

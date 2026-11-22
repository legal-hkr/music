@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.Artist
import sh.hnet.music.library.SectionRow
import sh.hnet.music.library.Sections
import sh.hnet.music.library.Sorted
import sh.hnet.music.library.TrackSort
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.FastScroller
import sh.hnet.music.ui.components.LocalBottomOverlay
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

/** A section's name as shown: the app's name for "Various artists", else the label itself. */
@Composable
fun sectionLabel(label: String): String = if (label == Sections.VARIOUS) stringResource(R.string.label_various_artists) else label

/**
 * The header before a section of a Library list (owner's decision, 22.11.2026): primary, title-large
 * – larger than the names under it (title-small, as Contacts' letters, read smaller than an album's
 * title) –, 16 dp above and 8 below; [modifier] places it in its list.
 */
@Composable
fun SectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        sectionLabel(label),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}

/** The fast scroller's label of every row, headers included, as the headers name them. */
@Composable
fun <T> scrollerLabels(rows: List<SectionRow<T>>): List<String> {
    val various = stringResource(R.string.label_various_artists)
    return remember(rows, various) { rows.map { if (it.label == Sections.VARIOUS) various else it.label } }
}

/** A key for a section header that no item's key can equal. */
fun SectionRow.Header.key(): String = "\u0002section:$start"

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

/**
 * Tracks with count, sort chip and fast scroller: the Tracks view and an artist's "Show all" page.
 *
 * @param headers Section headers between the groups: the Tracks view has them, an artist's page doesn't
 */
@Composable
fun TrackList(
    viewModel: BrowseViewModel,
    sorted: Sorted<LibraryTrack>,
    state: LazyListState,
    navigation: TrackNavigation,
    modifier: Modifier = Modifier,
    headers: Boolean = false,
) {
    val sort by viewModel.trackSort.collectAsStateWithLifecycle()
    val current by viewModel.playState.collectAsStateWithLifecycle(null to false)
    val rows = if (headers) sorted.rows else remember(sorted) { Sections.rows(sorted.items, sorted.sections, headers = false) }
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
            items(
                rows,
                key = { row -> if (row is SectionRow.Item) row.item.documentUri else (row as SectionRow.Header).key() },
                contentType = { row -> if (row is SectionRow.Item) "track" else "section" },
            ) { row ->
                when (row) {
                    is SectionRow.Header -> SectionHeader(row.label, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
                    is SectionRow.Item -> TrackRow(
                        track = row.item,
                        supporting = trackLine(row.item),
                        state = BrowseViewModel.stateOf(row.item, current),
                        navigation = navigation,
                        onClick = { viewModel.playFrom(sorted.items, row.index) },
                    )
                }
            }
        }
        FastScroller(state, scrollerLabels(rows), firstItem = 1, modifier = Modifier.align(Alignment.TopEnd), snapToSections = headers && sorted.headers)
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

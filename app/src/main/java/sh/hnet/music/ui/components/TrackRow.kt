@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.components

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.ui.library.AddRequest
import sh.hnet.music.ui.library.LocalAddToPlaylist
import sh.hnet.music.ui.library.LocalStarring
import sh.hnet.music.data.LibraryTrack

/** How a track relates to what plays now. */
enum class PlayState { NONE, PLAYING, PAUSED }

/** Where a row's menu can lead; absent ones are left out of the menu, never disabled (SPEC §5.4). */
class TrackNavigation(
    val onGoToArtist: ((String) -> Unit)?,
    val onGoToAlbum: ((LibraryTrack) -> Unit)?,
) {
    /** The same, without Go to album (on the album's own page). */
    fun withoutAlbum() = TrackNavigation(onGoToArtist, null)
}

/**
 * A track row (SPEC §5.1): optional number, title, supporting line, duration and a menu.
 *
 * @param number Track number on album pages; null elsewhere
 */
@Composable
fun TrackRow(
    track: LibraryTrack,
    supporting: String?,
    state: PlayState,
    navigation: TrackNavigation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    showNumberColumn: Boolean = false,
    /** Indent in dp, e.g. under an album row on the artist page. */
    startPadding: Int = 0,
    /** A last menu group removing the row from its playlist. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** After the menu button, e.g. a playlist entry's drag handle. */
    endContent: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val playingLabel = stringResource(R.string.desc_playing)
    val pausedLabel = stringResource(R.string.desc_paused)
    val tabular = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (menuOpen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        ),
        onClick = onClick,
        modifier = modifier
            .padding(start = startPadding.dp)
            .semantics {
                when (state) {
                    PlayState.PLAYING -> stateDescription = playingLabel
                    PlayState.PAUSED -> stateDescription = pausedLabel
                    PlayState.NONE -> Unit
                }
            },
        leadingContent = if (showNumberColumn) {
            {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    if (state != PlayState.NONE) {
                        PlayingIndicator(state == PlayState.PLAYING, MaterialTheme.colorScheme.primary)
                    } else if (number != null) {
                        Text(number.toString(), style = tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else null,
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state != PlayState.NONE && !showNumberColumn) {
                    PlayingIndicator(state == PlayState.PLAYING, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(formatDuration(track.durationMs), style = tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.content_description_more_for, track.displayTitle), { menuOpen = true })
                    TrackMenu(track, menuOpen, navigation, onRemoveFromPlaylist) { menuOpen = false }
                }
                endContent?.invoke()
            }
        },
    ) {
        Text(
            track.displayTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (state != PlayState.NONE) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
    }
}

/** The row menu (SPEC §5.4): queue actions, then where to go. Several artists: a list of them first. */
@Composable
private fun TrackMenu(track: LibraryTrack, expanded: Boolean, navigation: TrackNavigation, onRemoveFromPlaylist: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = (context.applicationContext as MusicApplication).player
    var choosingArtist by remember(expanded) { mutableStateOf(false) }
    val playsNext = stringResource(R.string.msg_plays_next)
    val addedToQueue = stringResource(R.string.msg_added_to_queue)
    val addToPlaylist = LocalAddToPlaylist.current
    val starring = LocalStarring.current
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        if (choosingArtist) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                Text(
                    stringResource(R.string.menu_go_to_artist),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                track.artists.forEachIndexed { index, artist ->
                    MenuItem(artist, R.drawable.ic_person, index, track.artists.size) {
                        onDismiss()
                        navigation.onGoToArtist?.invoke(artist)
                    }
                }
            }
            return@DropdownMenuPopup
        }
        val goTo = buildList<Pair<Int, () -> Unit>> {
            if (navigation.onGoToArtist != null && track.artists.isNotEmpty()) {
                add(R.string.menu_go_to_artist to {
                    if (track.artists.size > 1) {
                        choosingArtist = true
                    } else {
                        onDismiss()
                        navigation.onGoToArtist.invoke(track.artists.first())
                    }
                })
            }
            navigation.onGoToAlbum?.let { go -> if (!track.album.isNullOrBlank()) add(R.string.menu_go_to_album to { onDismiss(); go(track) }) }
        }
        val groupCount = 1 + (if (goTo.isNotEmpty()) 1 else 0) + (if (onRemoveFromPlaylist != null) 1 else 0)
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, groupCount)) {
            val items = if (starring != null) 4 else 3
            MenuItem(stringResource(R.string.menu_play_next), R.drawable.ic_queue_play_next, 0, items) {
                onDismiss()
                player.playNext(track)
                Toast.makeText(context, playsNext, Toast.LENGTH_SHORT).show()
            }
            MenuItem(stringResource(R.string.menu_add_to_queue), R.drawable.ic_queue_music, 1, items) {
                onDismiss()
                player.addToQueue(track)
                Toast.makeText(context, addedToQueue, Toast.LENGTH_SHORT).show()
            }
            MenuItem(stringResource(R.string.menu_add_to_playlist), R.drawable.ic_playlist_add, 2, items) {
                onDismiss()
                addToPlaylist(AddRequest.Track(track))
            }
            if (starring != null) {
                val starred = starring.isStarred(track)
                MenuItem(stringResource(if (starred) R.string.menu_unstar else R.string.menu_star), if (starred) R.drawable.ic_star_filled else R.drawable.ic_star, 3, items) {
                    onDismiss()
                    starring.set(track, !starred)
                }
            }
        }
        if (goTo.isNotEmpty()) {
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, groupCount)) {
                goTo.forEachIndexed { index, (label, action) ->
                    MenuItem(stringResource(label), if (label == R.string.menu_go_to_artist) R.drawable.ic_person else R.drawable.ic_album, index, goTo.size, onClick = action)
                }
            }
        }
        if (onRemoveFromPlaylist != null) {
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(if (goTo.isNotEmpty()) 2 else 1, if (goTo.isNotEmpty()) 3 else 2)) {
                MenuItem(stringResource(R.string.menu_remove_from_playlist), R.drawable.ic_playlist_remove, 0, 1) { onDismiss(); onRemoveFromPlaylist() }
            }
        }
    }
}

/** An item of an Expressive menu group; [error] for a destructive action (last in its menu). */
@Composable
fun MenuItem(text: String, @DrawableRes icon: Int, index: Int, count: Int, error: Boolean = false, onClick: () -> Unit) {
    val errorColor = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (error) errorColor else Color.Unspecified) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (error) errorColor else LocalContentColor.current) },
        shape = MenuDefaults.itemShape(index, count).shape,
        onClick = onClick,
    )
}

/** "4:34", "1:02:15" past an hour. */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

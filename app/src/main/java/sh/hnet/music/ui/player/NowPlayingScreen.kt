@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.Track
import sh.hnet.music.playback.PlayerConnection
import sh.hnet.music.playback.PlayerState
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.formatDuration

/**
 * Now playing (SPEC §9.2), full screen. [dragArea] goes on the top bar and the cover: a downward
 * drag there closes the screen. The cover's colours are never used for the UI.
 */
// Two modifiers on purpose: [modifier] for the screen, [dragArea] for the parts that close it on a drag
@android.annotation.SuppressLint("ModifierParameter")
@Composable
fun NowPlayingScreen(
    track: LibraryTrack?,
    details: Track?,
    state: PlayerState,
    player: PlayerConnection,
    dragArea: Modifier,
    onClose: () -> Unit,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (LibraryTrack) -> Unit,
    onDetails: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A Surface, not just a background: it also sets the content colour (onSurface), which this
    // overlay outside the Scaffold wouldn't get otherwise (black text and icons in dark theme)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).then(dragArea).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.content_description_close_now_playing), onClose)
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f).fillMaxSize().then(dragArea), contentAlignment = Alignment.Center) {
                        Cover(track, RoundedCornerShape(28.dp), Modifier.padding(start = 24.dp).aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                    Column(Modifier.weight(1f).padding(end = 24.dp), verticalArrangement = Arrangement.Center) {
                        Controls(track, details, state, player, onGoToArtist, onGoToAlbum, onDetails, onQueue)
                    }
                }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp).then(dragArea).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Cover(track, RoundedCornerShape(28.dp), Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                    Controls(track, details, state, player, onGoToArtist, onGoToAlbum, onDetails, onQueue)
                }
            }
        }
    }
    }
}

@Composable
private fun ColumnScope.Controls(
    track: LibraryTrack?,
    details: Track?,
    state: PlayerState,
    player: PlayerConnection,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (LibraryTrack) -> Unit,
    onDetails: () -> Unit,
    onQueue: () -> Unit,
) {
    // Title block and the track's actions
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track?.displayTitle.orEmpty(), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track?.artists?.joinToString(", ")?.ifEmpty { null }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            track?.album?.ifBlank { null }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (track != null) TrackActions(track, onGoToArtist, onGoToAlbum, onDetails)
    }
    Spacer(Modifier.height(12.dp))
    // Technical details: only here and on the details page (SPEC §2.5)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 32.dp)) {
        if (details != null) item { AssistChip(onClick = onDetails, label = { Text(techLabel(details)) }) }
    }
    Spacer(Modifier.height(12.dp))
    SeekBar(state, player)
    Spacer(Modifier.height(16.dp))
    Transport(state, player)
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shuffleState = stringResource(if (state.shuffle) R.string.desc_on else R.string.desc_off)
        IconToggleButton(
            checked = state.shuffle,
            onCheckedChange = player::setShuffle,
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.semantics { stateDescription = shuffleState },
        ) {
            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = stringResource(R.string.content_description_shuffle))
        }
        val (repeatIcon, repeatState) = when (state.repeatMode) {
            Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_filled to R.string.desc_repeat_all
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one to R.string.desc_repeat_one
            else -> R.drawable.ic_repeat to R.string.desc_repeat_off
        }
        val repeatDescription = stringResource(repeatState)
        IconToggleButton(
            checked = state.repeatMode != Player.REPEAT_MODE_OFF,
            onCheckedChange = { player.cycleRepeat() },
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.semantics { stateDescription = repeatDescription },
        ) {
            Icon(painterResource(repeatIcon), contentDescription = stringResource(R.string.content_description_repeat))
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onQueue, contentPadding = ButtonDefaults.SmallContentPadding, modifier = Modifier.heightIn(min = 40.dp)) {
            Icon(painterResource(R.drawable.ic_queue_music), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.button_queue), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Slider with elapsed and total time; the label follows a drag, the seek happens on release. */
@Composable
private fun SeekBar(state: PlayerState, player: PlayerConnection) {
    var position by remember { mutableLongStateOf(player.positionMs()) }
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val touching = dragged || pressed
    val sliderState = remember { SliderState() }
    LaunchedEffect(state.currentId, state.isPlaying) {
        while (true) {
            position = player.positionMs()
            if (!state.isPlaying) break
            delay(250)
        }
    }
    val duration = state.durationMs.coerceAtLeast(1)
    // The player leads unless the finger does
    SideEffect { if (!touching) sliderState.value = (position.toFloat() / duration).coerceIn(0f, 1f) }
    val shown = if (touching) (sliderState.value * duration).toLong() else position.coerceAtMost(duration)
    val value = stringResource(R.string.desc_seek_position, formatDuration(shown), formatDuration(state.durationMs))
    val back = stringResource(R.string.label_seek_back)
    val forward = stringResource(R.string.label_seek_forward)
    Slider(
        state = sliderState,
        onValueChange = { sliderState.value = it },
        onValueChangeFinished = {
            val target = (sliderState.value * duration).toLong()
            player.seekTo(target)
            position = target
        },
        interactionSource = interaction,
        thumb = { SliderDefaults.Thumb(interactionSource = interaction, isVertical = false, thumbSize = DpSize(4.dp, 44.dp)) },
        track = { current -> SliderDefaults.Track(sliderState = current, modifier = Modifier.height(16.dp)) },
        modifier = Modifier.semantics {
            stateDescription = value
            customActions = listOf(
                CustomAccessibilityAction(back) { player.seekTo((position - 10_000).coerceAtLeast(0)); true },
                CustomAccessibilityAction(forward) { player.seekTo(position + 10_000); true },
            )
        },
    )
    Row {
        Text(formatDuration(shown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(formatDuration(state.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Previous, play/pause, next in a button group (SPEC §9.2.6); play/pause is round while playing. */
@Composable
private fun Transport(state: PlayerState, player: PlayerConnection) {
    val previous = remember { MutableInteractionSource() }
    val playPause = remember { MutableInteractionSource() }
    val next = remember { MutableInteractionSource() }
    val previousLabel = stringResource(R.string.content_description_previous)
    val playLabel = stringResource(if (state.isPlaying) R.string.content_description_pause else R.string.content_description_play)
    val nextLabel = stringResource(R.string.content_description_next)
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().height(72.dp),
    ) {
        customItem(
            buttonGroupContent = {
                TransportButton(R.drawable.ic_skip_previous, previousLabel, previous, Modifier.animateWidth(previous).width(80.dp)) { player.previous() }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                FilledIconToggleButton(
                    checked = state.isPlaying,
                    onCheckedChange = { player.togglePlayPause() },
                    shapes = IconButtonDefaults.toggleableShapes(
                        shape = RoundedCornerShape(20.dp),
                        checkedShape = RoundedCornerShape(50),
                    ),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    interactionSource = playPause,
                    modifier = Modifier.animateWidth(playPause).weight(1f).fillMaxSize(),
                ) {
                    Icon(painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow), contentDescription = playLabel, modifier = Modifier.size(40.dp))
                }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                TransportButton(R.drawable.ic_skip_next, nextLabel, next, Modifier.animateWidth(next).width(80.dp), enabled = state.hasNext) { player.next() }
            },
            menuContent = {},
        )
    }
}

@Composable
private fun TransportButton(
    @DrawableRes icon: Int,
    label: String,
    interaction: MutableInteractionSource,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.fillMaxSize().widthIn(min = 56.dp),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp))
    }
}

/** Track actions (SPEC §9.4): Go to artist / album, then Track details. */
@Composable
private fun TrackActions(track: LibraryTrack, onGoToArtist: (String) -> Unit, onGoToAlbum: (LibraryTrack) -> Unit, onDetails: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var choosingArtist by remember(open) { mutableStateOf(false) }
    Box {
        TooltipIconButton(R.drawable.ic_more_horiz, stringResource(R.string.content_description_track_actions), { open = true })
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            if (choosingArtist) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    Text(
                        stringResource(R.string.menu_go_to_artist),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    track.artists.forEachIndexed { index, artist ->
                        Item(artist, R.drawable.ic_person, index, track.artists.size) { open = false; onGoToArtist(artist) }
                    }
                }
                return@DropdownMenuPopup
            }
            val goTo = buildList<Triple<Int, Int, () -> Unit>> {
                if (track.artists.isNotEmpty()) add(Triple(R.string.menu_go_to_artist, R.drawable.ic_person) {
                    if (track.artists.size > 1) choosingArtist = true else { open = false; onGoToArtist(track.artists.first()) }
                })
                if (!track.album.isNullOrBlank()) add(Triple(R.string.menu_go_to_album, R.drawable.ic_album) { open = false; onGoToAlbum(track) })
            }
            if (goTo.isNotEmpty()) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                    goTo.forEachIndexed { index, (label, icon, action) -> Item(stringResource(label), icon, index, goTo.size, action) }
                }
                Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            }
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(if (goTo.isEmpty()) 0 else 1, if (goTo.isEmpty()) 1 else 2)) {
                Item(stringResource(R.string.menu_track_details), R.drawable.ic_info, 0, 1) { open = false; onDetails() }
            }
        }
    }
}

@Composable
private fun Item(text: String, @DrawableRes icon: Int, index: Int, count: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
        shape = MenuDefaults.itemShape(index, count).shape,
        onClick = onClick,
    )
}

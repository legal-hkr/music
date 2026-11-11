@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.hnet.music.R
import sh.hnet.music.ui.library.AddRequest
import sh.hnet.music.ui.library.LocalAddToPlaylist
import sh.hnet.music.ui.library.LocalStarring
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.data.Track
import sh.hnet.music.playback.AppliedGain
import sh.hnet.music.playback.PlayerConnection
import sh.hnet.music.playback.PlayerState
import sh.hnet.music.ui.components.Cover
import sh.hnet.music.ui.components.TooltipIconButton
import sh.hnet.music.ui.components.formatDuration

/**
 * Now playing (SPEC §9.2), full screen. [dragArea] goes on the top bar and the cover: a downward
 * drag there closes the screen. The cover's colors are never used for the UI.
 */
// Two modifiers on purpose: [modifier] for the screen, [dragArea] for the parts that close it on a drag
@android.annotation.SuppressLint("ModifierParameter")
@Composable
fun NowPlayingScreen(
    track: LibraryTrack?,
    details: Track?,
    gain: AppliedGain?,
    state: PlayerState,
    player: PlayerConnection,
    dragArea: Modifier,
    onClose: () -> Unit,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (LibraryTrack) -> Unit,
    onDetails: () -> Unit,
    onQueue: () -> Unit,
    onEqualizer: () -> Unit,
    onOutputSettings: () -> Unit,
    onPlaybackSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A Surface, not just a background: it also sets the content color (onSurface), which this
    // overlay outside the Scaffold wouldn't get otherwise (black text and icons in dark theme)
    val volumeBar = remember { VolumeBarState() }
    Surface(modifier.fillMaxSize().closesVolumeBar(volumeBar), color = MaterialTheme.colorScheme.surface) {
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
                        Cover(track, RoundedCornerShape(28.dp), Modifier.padding(start = 24.dp).aspectRatio(1f, matchHeightConstraintsFirst = true), player = true)
                    }
                    Column(Modifier.weight(1f).padding(end = 24.dp), verticalArrangement = Arrangement.Center) {
                        Controls(track, details, gain, state, player, volumeBar, onGoToArtist, onGoToAlbum, onDetails, onQueue, onEqualizer, onOutputSettings, onPlaybackSettings)
                    }
                }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp).then(dragArea).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Cover(track, RoundedCornerShape(28.dp), Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), player = true)
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                    Controls(track, details, gain, state, player, volumeBar, onGoToArtist, onGoToAlbum, onDetails, onQueue, onEqualizer, onOutputSettings, onPlaybackSettings)
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
    gain: AppliedGain?,
    state: PlayerState,
    player: PlayerConnection,
    volumeBar: VolumeBarState,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (LibraryTrack) -> Unit,
    onDetails: () -> Unit,
    onQueue: () -> Unit,
    onEqualizer: () -> Unit,
    onOutputSettings: () -> Unit,
    onPlaybackSettings: () -> Unit,
) {
    // Title block and the track's actions
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // A title too long for the line scrolls after a moment, pauses at its start and goes again; a new
            // track starts from the beginning
            val title = track?.displayTitle.orEmpty()
            key(title) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    modifier = Modifier.titleMarquee(),
                )
            }
            track?.artists?.joinToString(", ")?.ifEmpty { null }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            track?.album?.ifBlank { null }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (track != null) {
            StarButton(track)
            TrackActions(track, onGoToArtist, onGoToAlbum, onDetails)
        }
    }
    Spacer(Modifier.height(12.dp))
    val bitPerfect by (LocalContext.current.applicationContext as sh.hnet.music.MusicApplication).bitPerfect.active.collectAsStateWithLifecycle()
    // Technical details: only here and on the details page (SPEC §2.5)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 32.dp)) {
        if (details != null) item { AssistChip(onClick = onDetails, label = { Text(techLabel(details)) }) }
        // Each chip opens what it is about: the file's details, the output's or the playback settings
        // Sent to the USB DAC unchanged: ReplayGain doesn't apply, so its chip gives way (SPEC §9.3)
        if (bitPerfect) item { AssistChip(onClick = onOutputSettings, label = { Text(stringResource(R.string.label_bit_perfect)) }) }
        // The gain actually applied, while ReplayGain is on (SPEC §9.3)
        if (gain != null && !bitPerfect) item {
            AssistChip(onClick = onPlaybackSettings, label = { Text(stringResource(R.string.label_replaygain_applied, decibels(gain.db))) })
        }
    }
    Spacer(Modifier.height(12.dp))
    SeekBar(state, player, track?.durationMs)
    Spacer(Modifier.height(16.dp))
    Transport(state, player)
    Spacer(Modifier.height(16.dp))
    BottomRow(volumeBar, onQueue = onQueue, onEqualizer = onEqualizer)
}

/** Slider with elapsed and total time; the label follows a drag, the seek happens on release. */
@Composable
private fun SeekBar(state: PlayerState, player: PlayerConnection, libraryDurationMs: Long?) {
    // Read again for a new track: the previous track's position must not be drawn against the new
    // track's length
    var position by remember(state.currentId) { mutableLongStateOf(player.positionMs()) }
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
    // Right after a change of track the player doesn't know the new length yet (0): the length from
    // the library stands in, and without either the bar stays where it was instead of jumping to the end
    val known = state.durationMs.takeIf { it > 0 } ?: libraryDurationMs?.takeIf { it > 0 }
    val duration = known ?: 1
    // The player leads unless the finger does
    // The queue has ended: rewound to 0:00 (SPEC-now-playing-v2 §5.2)
    val at = if (state.ended) 0L else position
    SideEffect { if (!touching && known != null) sliderState.value = (at.toFloat() / duration).coerceIn(0f, 1f) }
    val shown = if (touching) (sliderState.value * duration).toLong() else at.coerceAtMost(duration)
    val value = stringResource(R.string.desc_seek_position, formatDuration(shown), formatDuration(known ?: 0))
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
        Text(formatDuration(known ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The star (SPEC-now-playing-v2 §3.1): follows the current track and changes made elsewhere; the
 * icon is the feedback, no snackbar.
 */
@Composable
private fun StarButton(track: LibraryTrack) {
    val starring = LocalStarring.current ?: return
    val starred = starring.isStarred(track)
    val state = stringResource(if (starred) R.string.desc_starred else R.string.desc_not_starred)
    IconToggleButton(
        checked = starred,
        onCheckedChange = { starring.set(track, it) },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.semantics { stateDescription = state },
    ) {
        Icon(
            painterResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star),
            contentDescription = stringResource(if (starred) R.string.content_description_unstar else R.string.content_description_star),
        )
    }
}

/** Track actions (SPEC §9.4): Add to playlist, Go to artist / album, then Track details. */
@Composable
private fun TrackActions(track: LibraryTrack, onGoToArtist: (String) -> Unit, onGoToAlbum: (LibraryTrack) -> Unit, onDetails: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var choosingArtist by remember(open) { mutableStateOf(false) }
    val addToPlaylist = LocalAddToPlaylist.current
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
            val groups = if (goTo.isEmpty()) 2 else 3
            val starring = LocalStarring.current
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, groups)) {
                val count = if (starring != null) 2 else 1
                Item(stringResource(R.string.menu_add_to_playlist), R.drawable.ic_playlist_add, 0, count) { open = false; addToPlaylist(AddRequest.Track(track)) }
                if (starring != null) {
                    val starred = starring.isStarred(track)
                    Item(stringResource(if (starred) R.string.menu_unstar else R.string.menu_star), if (starred) R.drawable.ic_star_filled else R.drawable.ic_star, 1, count) {
                        open = false
                        starring.set(track, !starred)
                    }
                }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            if (goTo.isNotEmpty()) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, groups)) {
                    goTo.forEachIndexed { index, (label, icon, action) -> Item(stringResource(label), icon, index, goTo.size, action) }
                }
                Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            }
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(groups - 1, groups)) {
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

/**
 * The title's scrolling, with both ends fading out instead of being cut. The fade at the start lies
 * in the screen's side margin, so a title at rest stays whole and in line with the texts under it.
 */
private fun Modifier.titleMarquee(): Modifier = this
    .layout { measurable, constraints ->
        val fade = TITLE_FADE.roundToPx()
        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + fade))
        layout(placeable.width - fade, placeable.height) { placeable.place(-fade, 0) }
    }
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = TITLE_FADE.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f),
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
    }
    .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = TITLE_PAUSE_MS, initialDelayMillis = TITLE_PAUSE_MS)
    .padding(start = TITLE_FADE)

/** How long a scrolling title rests at its start, before the first pass and between passes: time to read it. */
private const val TITLE_PAUSE_MS = 5000

/** The width over which a scrolling title fades out at each end. */
private val TITLE_FADE = 16.dp

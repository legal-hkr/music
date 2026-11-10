@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)

package sh.hnet.music.ui.player

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.ButtonGroup
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import sh.hnet.music.R
import sh.hnet.music.playback.PlayerConnection
import sh.hnet.music.playback.PlayerState
import kotlin.math.roundToInt

private val MODE_BUTTON = 48.dp

/** The space around the group (between it and shuffle/repeat) and between its buttons. */
private val ROW_GAP = 8.dp
private val GROUP_GAP = 8.dp

/** The largest play gets; every transport button is square at rest (owner's decision). */
private val MAX_PLAY = 96.dp

/** Previous and next against play: smaller squares, 72 to 96 (owner's decision). */
private const val SIDE_RATIO = 0.75f

/** Play's corner radius while paused; while playing it is round. */
private val PAUSED_RADIUS = 28.dp

/**
 * Shuffle – previous – play – next – repeat in one mirrored row (§2). Previous, play and next form a
 * ButtonGroup side by side (owner's decision, after the overlapping cluster), each square at rest:
 * play as large as the row allows, up to [MAX_PLAY], previous and next [SIDE_RATIO] of it. Widths
 * are weighted by size, so the pressed one widens and its neighbours give way (animateWidth on all
 * three) and all come back square; each takes its own shape on press. Play is a toggle, round
 * while playing.
 */
@Composable
fun Transport(state: PlayerState, player: PlayerConnection) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val play = ((maxWidth - MODE_BUTTON * 2 - ROW_GAP * 2 - GROUP_GAP * 2) / (1 + SIDE_RATIO * 2)).coerceAtMost(MAX_PLAY)
    val side = play * SIDE_RATIO
    Row(
        modifier = Modifier.fillMaxWidth().height(play),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleState = stringResource(if (state.shuffle) R.string.desc_on else R.string.desc_off)
        ModeToggle(state.shuffle, R.drawable.ic_shuffle, stringResource(R.string.content_description_shuffle), shuffleState) { player.setShuffle(it) }

        val previous = remember { MutableInteractionSource() }
        val playPause = remember { MutableInteractionSource() }
        val next = remember { MutableInteractionSource() }
        val endReason = stringResource(R.string.desc_end_of_queue)
        // Weights as the sizes, in a group exactly as wide as the three squares: square at rest. The
        // group stretches its buttons to its height, so previous and next wrap theirs, centred
        ButtonGroup(
            overflowIndicator = {},
            horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
            modifier = Modifier.width(play + side * 2 + GROUP_GAP * 2).height(play),
        ) {
            customItem(
                buttonGroupContent = {
                    SkipButton(R.drawable.ic_skip_previous, stringResource(R.string.content_description_previous), previous, Modifier.animateWidth(previous).weight(SIDE_RATIO).wrapContentHeight().height(side)) {
                        player.previous()
                    }
                },
                menuContent = {},
            )
            customItem(
                buttonGroupContent = {
                    val (icon, label) = when {
                        state.ended -> R.drawable.ic_replay to R.string.content_description_replay
                        state.isPlaying -> R.drawable.ic_pause to R.string.content_description_pause
                        else -> R.drawable.ic_play_arrow to R.string.content_description_play
                    }
                    FilledIconToggleButton(
                        checked = state.isPlaying,
                        onCheckedChange = { if (state.ended) player.playFromStart() else player.togglePlayPause() },
                        shapes = IconButtonDefaults.toggleableShapes(shape = RoundedCornerShape(PAUSED_RADIUS), checkedShape = CircleShape),
                        // The same colours playing or paused: the shape tells them apart
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        interactionSource = playPause,
                        modifier = Modifier.animateWidth(playPause).weight(1f).fillMaxHeight(),
                    ) {
                        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(40.dp))
                    }
                },
                menuContent = {},
            )
            customItem(
                buttonGroupContent = {
                    SkipButton(
                        R.drawable.ic_skip_next,
                        stringResource(R.string.content_description_next),
                        next,
                        Modifier.animateWidth(next).weight(SIDE_RATIO).wrapContentHeight().height(side).semantics { if (state.ended) stateDescription = endReason },
                        enabled = state.hasNext,
                    ) { player.next() }
                },
                menuContent = {},
            )
        }

        val (repeatIcon, repeatState) = when (state.repeatMode) {
            Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_filled to R.string.desc_repeat_all
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one to R.string.desc_repeat_one
            else -> R.drawable.ic_repeat to R.string.desc_repeat_off
        }
        ModeToggle(state.repeatMode != Player.REPEAT_MODE_OFF, repeatIcon, stringResource(R.string.content_description_repeat), stringResource(repeatState)) { player.cycleRepeat() }
    }
}

/** Previous or next: a tonal icon button of the group, changing shape on press; [modifier] sizes it. */
@Composable
private fun SkipButton(
    @DrawableRes icon: Int,
    label: String,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun ModeToggle(checked: Boolean, @DrawableRes icon: Int, label: String, state: String, onChange: (Boolean) -> Unit) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = onChange,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = Modifier.size(MODE_BUTTON).semantics { stateDescription = state },
    ) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

/** The system media volume (STREAM_MUSIC), followed live: hardware keys and other apps included (§4.2). */
@Stable
class MediaVolume internal constructor(private val audio: AudioManager) {
    val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val fixed = audio.isVolumeFixed
    var index by mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        private set
    var muted by mutableStateOf(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        private set

    internal fun refresh() {
        index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    /** Without the system's volume panel (flag 0). */
    fun set(value: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(min, max), 0)
        refresh()
    }

    fun toggleMute() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        refresh()
    }

    val silent: Boolean get() = muted || index <= min
    val percent: Int get() = if (max == min) 100 else ((index - min) * 100f / (max - min)).roundToInt()

    /** volume_off (muted or 0), volume_down (≤ 50 %), volume_up (§4.1). */
    @get:DrawableRes
    val icon: Int get() = when {
        silent -> R.drawable.ic_volume_off
        percent <= 50 -> R.drawable.ic_volume_down
        else -> R.drawable.ic_volume_up
    }
}

@Composable
fun rememberMediaVolume(): MediaVolume {
    val context = LocalContext.current
    val volume = remember { MediaVolume(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager) }
    DisposableEffect(volume) {
        // Volume changes are written to the system settings, so an observer sees keys and other apps
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = volume.refresh()
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volume.refresh()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return volume
}

/** How long the volume bar stays after the last touch (§4.2). */
private const val VOLUME_BAR_MS = 5_000L

/** Whether the volume bar is open and where it is, shared with the screen that closes it on outside taps. */
@Stable
class VolumeBarState {
    var open by mutableStateOf(false)
    internal var touched by mutableIntStateOf(0)
    internal var bounds by mutableStateOf(Rect.Zero)
}

/**
 * For the whole now-playing screen: a tap anywhere outside the open volume bar closes it (§4.2).
 * Seen before the children and not taken, so the tap still does what it does there.
 */
fun Modifier.closesVolumeBar(state: VolumeBarState): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coordinates = it }.pointerInput(state.open) {
        if (!state.open) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val at = coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            if (!state.bounds.contains(at)) state.open = false
        }
    }
}

/**
 * Volume (start) and Queue (end); Volume grows into the volume bar (§4), which closes on Back, a tap
 * outside it, or 5 s after the last touch.
 */
@Composable
fun BottomRow(bar: VolumeBarState, onQueue: () -> Unit, onEqualizer: () -> Unit, modifier: Modifier = Modifier) {
    val volume = rememberMediaVolume()
    val open = bar.open
    BackHandler(enabled = open) { bar.open = false }
    LaunchedEffect(open, bar.touched) {
        if (open) {
            delay(VOLUME_BAR_MS)
            bar.open = false
        }
    }
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    SharedTransitionLayout(modifier.fillMaxWidth().height(56.dp)) {
        AnimatedContent(open, transitionSpec = { fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec) }, label = "volume") { isOpen ->
            val shared = Modifier.sharedBounds(rememberSharedContentState("volume"), this@AnimatedContent)
            if (!isOpen) {
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { bar.open = true; bar.touched++ }, modifier = shared.size(48.dp)) {
                        Icon(painterResource(volume.icon), contentDescription = stringResource(R.string.content_description_volume))
                    }
                    FilledTonalIconButton(onClick = onQueue, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_queue_music), contentDescription = stringResource(R.string.content_description_queue))
                    }
                }
            } else {
                VolumeBar(volume, onTouch = { bar.touched++ }, onEqualizer = { bar.open = false; onEqualizer() }, modifier = shared.onGloballyPositioned { bar.bounds = it.boundsInRoot() })
            }
        }
    }
}

/** The pill (§4.2): mute, the volume slider, and the equalizer button (SPEC-equalizer §2.2). */
@Composable
private fun VolumeBar(volume: MediaVolume, onTouch: () -> Unit, onEqualizer: () -> Unit, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape, modifier = modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!volume.fixed) {
                IconButton(onClick = { volume.toggleMute(); onTouch() }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(volume.icon),
                        contentDescription = stringResource(if (volume.muted) R.string.content_description_unmute else R.string.content_description_mute),
                    )
                }
            }
            val slider = remember(volume.min, volume.max) {
                SliderState(value = volume.index.toFloat(), steps = (volume.max - volume.min - 1).coerceAtLeast(0), trackRange = volume.min.toFloat()..volume.max.toFloat())
            }
            // The system leads (keys, other apps) unless the finger does
            LaunchedEffect(volume.index) { slider.value = volume.index.toFloat() }
            val percent = stringResource(R.string.desc_volume_percent, volume.percent)
            val fixedReason = stringResource(R.string.desc_volume_fixed)
            val label = stringResource(R.string.content_description_volume)
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Slider(
                state = slider,
                onValueChange = { slider.value = it; volume.set(it.roundToInt()); onTouch() },
                enabled = !volume.fixed,
                interactionSource = interaction,
                thumb = { SliderDefaults.Thumb(interactionSource = interaction, isVertical = false, thumbSize = DpSize(4.dp, 40.dp), enabled = !volume.fixed) },
                track = { current -> SliderDefaults.Track(sliderState = current, modifier = Modifier.height(16.dp), enabled = !volume.fixed) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .semantics {
                        contentDescription = label
                        stateDescription = if (volume.fixed) fixedReason else percent
                    },
            )
            IconButton(onClick = onEqualizer, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_equalizer), contentDescription = stringResource(R.string.title_equalizer))
            }
        }
    }
}

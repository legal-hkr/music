@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.Albums

/**
 * A track's cover – the file's own picture, else its folder image – square and cropped, or the
 * placeholder (SPEC §4.5) when it has none.
 * The placeholder also shows while the picture loads, so nothing jumps. [player] marks the mini
 * player and now playing, whose neighboring tracks' covers are loaded ahead.
 */
@Composable
fun Cover(track: LibraryTrack?, shape: Shape, modifier: Modifier = Modifier, player: Boolean = false) {
    BoxWithConstraints(modifier.clip(shape)) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val covers = (LocalContext.current.applicationContext as MusicApplication).covers
        val size = covers.sizeFor(px)
        // The player's surfaces tell the cache which sizes to load ahead for the next track
        if (player) SideEffect { covers.notePlayerSize(size) }
        val shown = remember { ShownCover() }
        val image by produceState<ImageBitmap?>(
            initialValue = track?.let { covers.cached(it, size)?.asImageBitmap() },
            // The folder image too: one added or replaced beside an unchanged file shows without a restart
            track?.documentUri, track?.lastModified, track?.folderCover, size,
        ) {
            // The state outlives a change of track (the mini player keeps its place). The next track
            // of the same album keeps the picture shown until its own is decoded (usually the same
            // cover), so skipping doesn't flash the placeholder; another album's picture is dropped
            val album = track?.let(::albumOf)
            val cached = track?.let { covers.cached(it, size) }
            val sameAlbum = album != null && album == shown.album && track.hasCover
            value = cached?.asImageBitmap() ?: if (sameAlbum) value else null
            shown.album = album
            if (cached == null && track?.hasCover == true) value = covers.load(track, size)?.asImageBitmap()
        }
        val picture = image
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            CoverPlaceholder(iconSize(maxWidth))
        }
    }
}

/** The album of the picture a cover shows, kept across changes of track. */
private class ShownCover {
    var album: String? = null
}

private fun albumOf(track: LibraryTrack): String? = if (track.album.isNullOrBlank()) null else Albums.keyOf(track)

private fun iconSize(width: Dp): Dp = when {
    width <= 48.dp -> 24.dp
    width < 136.dp -> 40.dp
    else -> 48.dp
}

@Composable
private fun CoverPlaceholder(icon: Dp) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize(0.62f).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_album), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(icon))
        }
    }
}

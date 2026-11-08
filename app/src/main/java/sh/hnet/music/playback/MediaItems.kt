package sh.hnet.music.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.Albums

/** A track as the player's item: the document URI as ID and source, tags for the notification. */
fun LibraryTrack.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(documentUri)
    .setUri(documentUri.toUri())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(artists.joinToString(", ").ifEmpty { null })
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtists.joinToString(", ").ifEmpty { null })
            .setTrackNumber(trackNumber)
            .setDiscNumber(discNumber)
            .setExtras(gainExtras())
            .build(),
    )
    .build()

// What the audio renderer needs for ReplayGain travels with the item: it runs on the playback
// thread, where the library isn't at hand, and has to decide before the first sample is output
private const val TRACK_GAIN = "rg_track_gain"
private const val TRACK_PEAK = "rg_track_peak"
private const val ALBUM_GAIN = "rg_album_gain"
private const val ALBUM_PEAK = "rg_album_peak"
private const val ALBUM_KEY = "album_key"

private fun LibraryTrack.gainExtras() = Bundle().apply {
    trackGainDb?.let { putFloat(TRACK_GAIN, it) }
    trackPeak?.let { putFloat(TRACK_PEAK, it) }
    albumGainDb?.let { putFloat(ALBUM_GAIN, it) }
    albumPeak?.let { putFloat(ALBUM_PEAK, it) }
    if (!album.isNullOrBlank()) putString(ALBUM_KEY, Albums.keyOf(this@gainExtras))
}

private fun Bundle.floatOrNull(key: String) = if (containsKey(key)) getFloat(key) else null

/** The item's ReplayGain tags (all null for an item built elsewhere). */
fun MediaItem.gainTags(): GainTags {
    val extras = mediaMetadata.extras ?: return GainTags(null, null, null, null)
    return GainTags(extras.floatOrNull(TRACK_GAIN), extras.floatOrNull(TRACK_PEAK), extras.floatOrNull(ALBUM_GAIN), extras.floatOrNull(ALBUM_PEAK))
}

/** The album the item belongs to, as the library groups albums; null without an album tag. */
val MediaItem.albumKey: String? get() = mediaMetadata.extras?.getString(ALBUM_KEY)

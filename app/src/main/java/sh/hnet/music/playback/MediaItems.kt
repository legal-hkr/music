package sh.hnet.music.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import sh.hnet.music.data.LibraryTrack

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
            .build(),
    )
    .build()

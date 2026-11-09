package sh.hnet.music.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * How many times a track was listened to (at least half of it in one play), nothing else: no dates,
 * no history (owner's decision). Kept by document URI beside the index, so a rescan that rewrites a
 * track's row keeps it; it goes when the track leaves the library (TrackDao removes it with the file).
 */
@Entity(tableName = "play_count")
data class PlayCount(
    @PrimaryKey val documentUri: String,
    val count: Int,
)

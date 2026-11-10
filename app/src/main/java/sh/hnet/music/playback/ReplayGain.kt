package sh.hnet.music.playback

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

enum class ReplayGainMode { OFF, TRACK, ALBUM, AUTO }

/** Settings › Playback (SPEC §11.4). */
data class GainSettings(
    val mode: ReplayGainMode = ReplayGainMode.OFF,
    /** For files without ReplayGain tags. */
    val preampDb: Float = -6f,
    val preventClipping: Boolean = true,
)

/** A track's ReplayGain tags; any of them may be missing. */
data class GainTags(
    val trackGainDb: Float?,
    val trackPeak: Float?,
    val albumGainDb: Float?,
    val albumPeak: Float?,
)

/** The gain actually applied to a track, and whether it is the album's (for the chip). */
data class AppliedGain(val db: Float, val album: Boolean) {
    val linear: Float get() = 10f.pow(db / 20f)
}

object ReplayGain {
    /**
     * The gain for a track. Album mode (or Automatic with [albumInOrder]) uses the album gain, the
     * track gain otherwise, each falling back to the other and then to the pre-amp. With
     * [GainSettings.preventClipping] the gain is lowered so the peak stays at or below full scale.
     *
     * Positive gain is applied too (samples are scaled as float); without clipping prevention a
     * peak may then exceed full scale and clip at the output.
     *
     * @return null when ReplayGain is off, or the file has no tags and the pre-amp is 0 dB
     */
    fun compute(tags: GainTags, settings: GainSettings, albumInOrder: Boolean): AppliedGain? {
        val wantAlbum = when (settings.mode) {
            ReplayGainMode.OFF -> return null
            ReplayGainMode.TRACK -> false
            ReplayGainMode.ALBUM -> true
            ReplayGainMode.AUTO -> albumInOrder
        }
        val albumChoice = tags.albumGainDb?.let { Triple(it, tags.albumPeak ?: tags.trackPeak, true) }
        val trackChoice = tags.trackGainDb?.let { Triple(it, tags.trackPeak, false) }
        val (gain, peak, isAlbum) = (if (wantAlbum) albumChoice ?: trackChoice else trackChoice ?: albumChoice)
            ?: run {
                if (settings.preampDb == 0f) return null
                Triple(settings.preampDb, null, wantAlbum)
            }
        var db = gain
        if (settings.preventClipping && peak != null && peak > 0f) db = min(db, -20f * log10(peak))
        return AppliedGain(db, isAlbum)
    }
}

/** Tags of a library track. */
fun sh.hnet.music.data.Track.gainTags() = GainTags(trackGainDb, trackPeak, albumGainDb, albumPeak)

fun sh.hnet.music.data.LibraryTrack.gainTags() = GainTags(trackGainDb, trackPeak, albumGainDb, albumPeak)

/**
 * "An album plays in order" (SPEC §11.4) as the queue shows it: shuffle off and a neighbor in the
 * queue is from the same album, so the album's own steps between tracks are what's heard.
 */
fun albumInOrder(shuffle: Boolean, albumKeyOf: (Int) -> String?, current: Int, count: Int): Boolean {
    if (shuffle) return false
    val key = albumKeyOf(current) ?: return false
    return (current > 0 && albumKeyOf(current - 1) == key) || (current + 1 < count && albumKeyOf(current + 1) == key)
}

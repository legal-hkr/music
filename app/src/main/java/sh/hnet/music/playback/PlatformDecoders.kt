package sh.hnet.music.playback

import android.media.MediaCodecList
import sh.hnet.music.tags.AudioCodec

/**
 * Which formats this phone can play, for the scanner (owner's decision 27.11.2026, GH #8: a file the phone
 * can't decode is listed among the skipped ones with the reason, not left to play as silence). Only formats
 * Android doesn't require a decoder for are asked about: FLAC, MP3, AAC, Vorbis and Opus decoders are part
 * of every Android device (CDD), ALAC and DSD are decoded by Music itself – so a phone answering wrongly
 * can't take a whole library away. Anything else the player still catches (PlaybackService).
 */
object PlatformDecoders {
    /** The formats asked about, with the MIME type Media3 asks the phone's decoders for. */
    private val OPTIONAL = mapOf(
        AudioCodec.MP1 to "audio/mpeg-L1",
        AudioCodec.MP2 to "audio/mpeg-L2",
    )

    /** Of the formats asked about, those none of [decoderTypes] (the phone's decoders' MIME types, lower case) takes. */
    fun missing(decoderTypes: Set<String>): Set<AudioCodec> = OPTIONAL.filterValues { it.lowercase() !in decoderTypes }.keys

    /** Whether [decoderTypes] can be a real phone's: every Android device has an MP3 decoder (CDD). */
    fun isPlausible(decoderTypes: Set<String>): Boolean = "audio/mpeg" in decoderTypes

    /** The MIME types the phone's decoders take, lower case. */
    fun phoneDecoderTypes(): Set<String> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder }
        .flatMapTo(HashSet()) { info -> info.supportedTypes.map { it.lowercase() } }
}

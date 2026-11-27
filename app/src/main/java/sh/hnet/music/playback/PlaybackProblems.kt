package sh.hnet.music.playback

import android.os.Bundle
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionCommand

/**
 * A track the service couldn't play and skipped (or stopped at): sent to Music's own controller, which
 * shows it as a snackbar while the app is on screen (owner's decision 27.11.2026: nothing is skipped
 * silently). [title] is the item's title; [format] the codec's name when the phone has no decoder for it.
 */
data class PlaybackProblem(val kind: Kind, val title: String?, val format: String? = null) {
    enum class Kind {
        /** No decoder on this phone takes the file's audio: it would play as silence. */
        NO_DECODER,

        /** The file is gone. */
        MISSING,

        /** Music lost access to the file's folder. */
        NO_ACCESS,

        /** The file is damaged or incomplete. */
        DAMAGED,

        /** The audio output wouldn't open or take the sound: playback stops there, the next track would fail the same way. */
        OUTPUT,

        /** Anything else (a decoder that failed to start, say). */
        FAILED,

        /** Every track of the queue failed in a row: the service stopped trying. */
        NOTHING_PLAYS,
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KIND, kind.name)
        title?.let { putString(TITLE, it) }
        format?.let { putString(FORMAT, it) }
    }

    companion object {
        const val ACTION = "sh.hnet.music.PLAYBACK_PROBLEM"
        val COMMAND by lazy { SessionCommand(ACTION, Bundle.EMPTY) }
        private const val KIND = "kind"
        private const val TITLE = "title"
        private const val FORMAT = "format"

        fun from(args: Bundle): PlaybackProblem? {
            val kind = args.getString(KIND)?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: return null
            return PlaybackProblem(kind, args.getString(TITLE), args.getString(FORMAT))
        }

        /** What a player error means for the listener: the file, its folder, or something else. */
        fun kindOf(errorCode: Int): Kind = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Kind.MISSING
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Kind.NO_ACCESS
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> Kind.DAMAGED
            // A container nothing recognizes is a format the player can't read, not a damaged file
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> Kind.NO_DECODER
            else -> if (isOutputError(errorCode)) Kind.OUTPUT else Kind.FAILED
        }

        /** Errors of the audio output (5xxx: the AudioTrack wouldn't open or take data), not of the file. */
        fun isOutputError(errorCode: Int): Boolean = errorCode in
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED

        /** The codec's usual name for a Media3 sample MIME type; null when there is none to show. */
        fun formatName(mimeType: String?): String? = when (mimeType) {
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_MPEG_L1 -> "MP1"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_MPEGH_MHA1, MimeTypes.AUDIO_MPEGH_MHM1 -> "MPEG-H"
            else -> if (mimeType?.startsWith("audio/vnd.dts") == true) "DTS" else null
        }
    }
}

package sh.hnet.music.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.music.playback.PlaybackProblem.Kind

class PlaybackProblemTest {
    @Test
    fun `errors are told by what they mean for the file`() {
        assertEquals(Kind.MISSING, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(Kind.NO_ACCESS, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertEquals(Kind.DAMAGED, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(Kind.DAMAGED, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(Kind.NO_DECODER, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(Kind.NO_DECODER, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(Kind.OUTPUT, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(Kind.OUTPUT, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED))
        assertEquals(Kind.FAILED, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(Kind.FAILED, PlaybackProblem.kindOf(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun `formats are named as people know them`() {
        assertEquals("ALAC", PlaybackProblem.formatName("audio/alac"))
        assertEquals("MP2", PlaybackProblem.formatName("audio/mpeg-L2"))
        assertEquals("E-AC-3", PlaybackProblem.formatName("audio/eac3-joc"))
        assertEquals("DTS", PlaybackProblem.formatName("audio/vnd.dts.hd"))
        assertNull(PlaybackProblem.formatName("audio/x-unknown"))
        assertNull(PlaybackProblem.formatName(null))
    }
}

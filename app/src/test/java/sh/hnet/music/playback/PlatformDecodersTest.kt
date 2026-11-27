package sh.hnet.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.music.tags.AudioCodec

class PlatformDecodersTest {
    @Test
    fun `only the formats Android doesn't promise are missing, and only without a decoder`() {
        // A phone with Android's own decoders only (AOSP): MPEG audio layers I and II have none
        val aosp = setOf("audio/mpeg", "audio/mp4a-latm", "audio/flac", "audio/vorbis", "audio/opus", "audio/raw")
        assertEquals(setOf(AudioCodec.MP1, AudioCodec.MP2), PlatformDecoders.missing(aosp))
        assertEquals(setOf(AudioCodec.MP1), PlatformDecoders.missing(aosp + "audio/mpeg-l2"))
        assertEquals(emptySet<AudioCodec>(), PlatformDecoders.missing(aosp + "audio/mpeg-l1" + "audio/mpeg-l2"))
        // Never the required ones, whatever the phone says
        assertEquals(setOf(AudioCodec.MP1, AudioCodec.MP2), PlatformDecoders.missing(emptySet()))
    }
}

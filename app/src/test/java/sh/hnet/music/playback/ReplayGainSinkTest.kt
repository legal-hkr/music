package sh.hnet.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class ReplayGainSinkTest {
    /** A sink that takes [takeFirst] bytes on the first write, then everything; records samples. */
    private class FakeSink(val takeFirst: Int) {
        var configured: Format? = null
        val samples = mutableListOf<Float>()
        private var writes = 0
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> { configured = (args[0] as AudioSink.AudioSinkConfig).format; null }
                "handleBuffer" -> {
                    val buffer = args[0] as ByteBuffer
                    val take = if (writes++ == 0) minOf(takeFirst, buffer.remaining()) else buffer.remaining()
                    val view = buffer.duplicate().order(ByteOrder.nativeOrder())
                    repeat(take / 4) { samples += view.getFloat() }
                    buffer.position(buffer.position() + take)
                    !buffer.hasRemaining()
                }
                else -> null
            }
        } as AudioSink
    }

    private fun pcm16(vararg values: Short): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it) }; flip() }

    private fun format(encoding: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(encoding)
        .setChannelCount(2).setSampleRate(44100).build()

    @Test
    fun sixteenBitBecomesFloatWithGainAcrossPartialWrites() {
        val fake = FakeSink(takeFirst = 8)
        val sink = ReplayGainSink(fake.sink)
        sink.settings = GainSettings(ReplayGainMode.TRACK, preventClipping = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(format(C.ENCODING_PCM_16BIT)).build())
        assertEquals(C.ENCODING_PCM_FLOAT, fake.configured!!.pcmEncoding)
        sink.startTrack(TrackGain(GainTags(-6.0206f, null, null, null), albumInOrder = false))

        val input = pcm16(16384, -16384, 8192, 0)
        assertFalse(sink.handleBuffer(input, 0, 1))
        // Two of four samples taken: the input advanced by two 16-bit samples
        assertEquals(4, input.position())
        assertTrue(sink.handleBuffer(input, 0, 1))
        assertFalse(input.hasRemaining())
        val expected = listOf(0.25f, -0.25f, 0.125f, 0f)
        expected.zip(fake.samples).forEach { (e, a) -> assertEquals(e, a, 1e-4f) }
    }

    @Test
    fun gainSwitchesAtTheTrackBoundaryAndOffMeansUnchanged() {
        val fake = FakeSink(takeFirst = Int.MAX_VALUE)
        val sink = ReplayGainSink(fake.sink)
        sink.configure(AudioSink.AudioSinkConfig.Builder(format(C.ENCODING_PCM_16BIT)).build())
        sink.startTrack(TrackGain(GainTags(-6f, null, -12f, null), albumInOrder = true))
        assertTrue(sink.handleBuffer(pcm16(16384, 16384), 0, 1))
        assertEquals(0.5f, fake.samples.last(), 1e-6f) // ReplayGain off by default
        sink.settings = GainSettings(ReplayGainMode.AUTO, preventClipping = false)
        sink.startTrack(TrackGain(GainTags(-6.0206f, null, -12.0412f, null), albumInOrder = true))
        assertTrue(sink.handleBuffer(pcm16(16384, 16384), 1, 1))
        assertEquals(0.125f, fake.samples.last(), 1e-4f) // album gain: in order
        sink.shuffle = true
        assertTrue(sink.handleBuffer(pcm16(16384, 16384), 2, 1))
        assertEquals(0.25f, fake.samples.last(), 1e-4f) // shuffled: track gain
    }
}

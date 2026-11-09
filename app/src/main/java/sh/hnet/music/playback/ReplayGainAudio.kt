@file:androidx.annotation.OptIn(UnstableApi::class)

package sh.hnet.music.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The player's renderers with ReplayGain (SPEC §11.4) applied in the audio path: every sample is
 * converted to float and scaled, so the gain changes exactly at the first sample of a track (gapless
 * included), may be positive, and hi-res sources keep their resolution (float output; Media3's own
 * audio processors would only run on a 16-bit path).
 */
class ReplayGainRenderersFactory(context: Context, private val sink: ReplayGainSink) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink = sink

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(ReplayGainRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, sink))
    }

    companion object {
        fun sink(context: Context) = ReplayGainSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(true).build())
    }
}

/**
 * Tells the sink which track's samples come next. A stream (one queue item) is identified by its
 * offset: noted when the decoder starts reading it, applied when its first sample reaches the output
 * (the decoder reads ahead, so the two moments differ by everything still buffered).
 */
private class ReplayGainRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    fallback: Boolean,
    handler: Handler,
    listener: AudioRendererEventListener,
    private val gainSink: ReplayGainSink,
) : MediaCodecAudioRenderer(context, codecAdapterFactory, selector, fallback, handler, listener, gainSink) {
    private val streams = HashMap<Long, TrackGain?>()

    override fun onStreamChanged(formats: Array<out Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        // Before super: the first stream's output starts inside it
        streams[offsetUs] = trackGain(timeline, mediaPeriodId)
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onOutputStreamOffsetUsChanged(outputStreamOffsetUs: Long) {
        super.onOutputStreamOffsetUsChanged(outputStreamOffsetUs)
        gainSink.startTrack(streams.remove(outputStreamOffsetUs))
    }

    override fun onDisabled() {
        streams.clear()
        super.onDisabled()
    }

    private fun trackGain(timeline: Timeline, id: MediaSource.MediaPeriodId): TrackGain? {
        if (timeline.getIndexOfPeriod(id.periodUid) == C.INDEX_UNSET) return null
        val windowIndex = timeline.getPeriodByUid(id.periodUid, Timeline.Period()).windowIndex
        fun item(index: Int): MediaItem? = if (index in 0 until timeline.windowCount) timeline.getWindow(index, Timeline.Window()).mediaItem else null
        val current = item(windowIndex) ?: return null
        // Neighbours in queue order; with shuffle on the album never counts as playing in order
        val inOrder = albumInOrder(false, { i -> item(i)?.albumKey }, windowIndex, timeline.windowCount)
        return TrackGain(current.gainTags(), inOrder)
    }
}

/** What decides a track's gain besides the settings. */
data class TrackGain(val tags: GainTags, val albumInOrder: Boolean)

/**
 * Converts PCM to float and applies the current track's gain on the way into the real sink. Input
 * buffers are consumed through a converted copy, kept until the real sink has taken all of it.
 */
class ReplayGainSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile var settings = GainSettings()
        set(value) { field = value; update() }

    @Volatile var shuffle = false
        set(value) { field = value; update() }

    @Volatile private var track: TrackGain? = null
    @Volatile private var gain = 1f

    private var encoding = C.ENCODING_INVALID
    private var converted: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var source: ByteBuffer? = null
    private var sourceTimeUs = C.TIME_UNSET
    private var sourceStart = 0

    /** Called on the playback thread as a track's first sample is about to be written. */
    fun startTrack(next: TrackGain?) {
        track = next
        update()
    }

    private fun update() {
        val current = track
        gain = if (current == null) 1f else ReplayGain.compute(current.tags, settings, current.albumInOrder && !shuffle)?.linear ?: 1f
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        encoding = if (format.pcmEncoding in CONVERTIBLE) format.pcmEncoding else C.ENCODING_INVALID
        if (encoding == C.ENCODING_INVALID) {
            super.configure(audioSinkConfig)
        } else {
            val asFloat = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
            super.configure(
                AudioSink.AudioSinkConfig.Builder(asFloat)
                    .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                    .setTimeline(audioSinkConfig.timeline)
                    .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                    .build(),
            )
        }
        clearPending()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (encoding == C.ENCODING_INVALID) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // Already float and nothing to scale (ReplayGain off, or 0 dB): passed on as it is, no copy
        if (encoding == C.ENCODING_PCM_FLOAT && gain == 1f && source == null) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val inBytes = bytesPerSample(encoding)
        // The same buffer comes back until it's consumed; a new one (or the same object reused by
        // the codec for the next output) is converted once
        if (buffer !== source || presentationTimeUs != sourceTimeUs || !converted.hasRemaining()) {
            convert(buffer, inBytes)
            source = buffer
            sourceTimeUs = presentationTimeUs
            sourceStart = buffer.position()
        }
        val done = super.handleBuffer(converted, presentationTimeUs, encodedAccessUnitCount)
        // Mirror what the real sink took, in the input's sample size
        buffer.position(sourceStart + converted.position() / 4 * inBytes)
        if (done) clearPending()
        return done
    }

    private fun convert(input: ByteBuffer, inBytes: Int) {
        val samples = input.remaining() / inBytes
        if (converted.capacity() < samples * 4) converted = ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.nativeOrder())
        converted.clear()
        converted.order(ByteOrder.nativeOrder())
        val g = gain
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> repeat(samples) { converted.putFloat(src.getFloat() * g) }
            C.ENCODING_PCM_16BIT -> repeat(samples) { converted.putFloat(src.getShort() / 32768f * g) }
            C.ENCODING_PCM_24BIT -> repeat(samples) {
                val v = (src.get().toInt() and 0xFF) or ((src.get().toInt() and 0xFF) shl 8) or (src.get().toInt() shl 16)
                converted.putFloat(v / 8388608f * g)
            }
            C.ENCODING_PCM_32BIT -> repeat(samples) { converted.putFloat((src.getInt() / 2147483648.0).toFloat() * g) }
        }
        converted.flip()
    }

    private fun clearPending() {
        source = null
        sourceTimeUs = C.TIME_UNSET
        converted.clear().limit(0)
    }

    override fun flush() {
        clearPending()
        super.flush()
    }

    override fun reset() {
        clearPending()
        super.reset()
    }

    private companion object {
        val CONVERTIBLE = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        fun bytesPerSample(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
    }
}

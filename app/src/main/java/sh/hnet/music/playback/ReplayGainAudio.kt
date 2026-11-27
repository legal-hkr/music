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
        // First: ALAC goes to Music's own decoder even on phones with one of their own (owner's decision 27.11.2026,
        // GH #8 – one decoder tested bit for bit everywhere); the track selector takes the first renderer that handles a format
        out.add(AlacRenderer(eventHandler, eventListener, sink))
        out.add(ReplayGainRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, sink))
    }

    companion object {
        /** The sink over Media3's own, whose outputs [bitPerfect] opens (the usual AudioTrack, or the device's own format). */
        fun sink(context: Context, bitPerfect: BitPerfectController) = ReplayGainSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setAudioOutputProvider(BitPerfectOutputProvider.create(context, bitPerfect))
                .build(),
            bitPerfect,
        )
    }
}

/**
 * Media3's renderer over the phone's decoders, telling the sink which track each stream is
 * (ReplayGainSink.noteStream) as the decoder starts reading it.
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
    override fun onStreamChanged(formats: Array<out Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        // Before super: the first stream's output starts inside it
        gainSink.noteStream(this, offsetUs, trackGain(timeline, mediaPeriodId))
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onDisabled() {
        gainSink.forgetStreams(this)
        super.onDisabled()
    }
}

/** What decides the gain of the queue item [id] plays: its tags and whether its album plays in order. */
internal fun trackGain(timeline: Timeline, id: MediaSource.MediaPeriodId): TrackGain? {
    if (timeline.getIndexOfPeriod(id.periodUid) == C.INDEX_UNSET) return null
    val windowIndex = timeline.getPeriodByUid(id.periodUid, Timeline.Period()).windowIndex
    fun item(index: Int): MediaItem? = if (index in 0 until timeline.windowCount) timeline.getWindow(index, Timeline.Window()).mediaItem else null
    val current = item(windowIndex) ?: return null
    // Neighbors in queue order; with shuffle on the album never counts as playing in order
    val inOrder = albumInOrder(false, { i -> item(i)?.albumKey }, windowIndex, timeline.windowCount)
    return TrackGain(current.gainTags(), inOrder)
}

/** What decides a track's gain besides the settings. */
data class TrackGain(val tags: GainTags, val albumInOrder: Boolean)

/**
 * Converts PCM to float and applies the current track's gain, then the equalizer, on the way into
 * the real sink. Input buffers are consumed through a converted copy, kept until the real sink has
 * taken all of it. A stream that goes out bit-perfect is converted only (exactly, for 16- and
 * 24-bit sources): neither the gain nor the equalizer touches it.
 */
class ReplayGainSink(sink: AudioSink, private val bitPerfect: BitPerfectController? = null) : ForwardingAudioSink(sink) {
    @Volatile var settings = GainSettings()
        set(value) { field = value; update() }

    @Volatile var shuffle = false
        set(value) { field = value; update() }

    /** What the equalizer applies, or null while it is off (SPEC-equalizer §11.1: after ReplayGain). */
    @Volatile var equalizer: EqSettings? = null

    private val eq = EqProcessor()
    private var channels = 0

    @Volatile private var track: TrackGain? = null
    @Volatile private var gain = 1f

    private var encoding = C.ENCODING_INVALID
    private var converted: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var source: ByteBuffer? = null
    private var sourceTimeUs = C.TIME_UNSET
    private var sourceStart = 0
    /** A buffer handed on unconverted that the real sink hasn't finished yet. */
    private var passing = false
    /** The stream goes out bit-perfect: nothing changes its samples. */
    private var exact = false
    private var sampleRate = 0

    /**
     * Which track each stream is, by its offset: noted by a renderer as its decoder starts reading the stream, taken
     * when the stream's first sample reaches the output (the decoder reads ahead, so the two moments differ by
     * everything still buffered). Both audio renderers (the phone's decoders, Music's ALAC) share this sink.
     * Playback thread only.
     */
    private val streams = HashMap<Long, Pair<Any, TrackGain?>>()

    fun noteStream(renderer: Any, offsetUs: Long, gain: TrackGain?) {
        streams[offsetUs] = renderer to gain
    }

    /** A renderer disabled: its streams won't be output. */
    fun forgetStreams(renderer: Any) {
        streams.values.removeAll { it.first === renderer }
    }

    /**
     * Both renderers say here when output moves to their next stream. Media3 1.11 never passes TIME_UNSET (no stream);
     * should it, the gain stays: every stream's start sets its own.
     */
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        if (outputStreamOffsetUs == C.TIME_UNSET) return
        startTrack(streams.remove(outputStreamOffsetUs)?.second)
    }

    /** The gain of the track whose samples come next. Playback thread. */
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
        channels = format.channelCount
        sampleRate = format.sampleRate
        if (encoding != C.ENCODING_INVALID) eq.configure(format.sampleRate, channels)
        // Decided here, per stream; the output is opened to match when this stream's first sample is due
        exact = bitPerfect?.prepare(format.sampleRate, exactBits(encoding), channels) == true
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
        eq.set(if (exact) null else equalizer)
        val applied = if (exact) 1f else gain
        // Already float and nothing to do (ReplayGain off or 0 dB, equalizer off): passed on as it is, no
        // copy. A buffer the real sink has begun stays its own until it has taken all of it: the sink
        // refuses another buffer in its place, so the equalizer switched on meanwhile starts with the next
        if (passing || (encoding == C.ENCODING_PCM_FLOAT && applied == 1f && (exact || eq.idle) && source == null)) {
            val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            passing = !done
            return done
        }
        val inBytes = bytesPerSample(encoding)
        // The same buffer comes back until it's consumed; a new one (or the same object reused by
        // the codec for the next output) is converted once
        if (buffer !== source || presentationTimeUs != sourceTimeUs || !converted.hasRemaining()) {
            convert(buffer, inBytes, applied)
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

    private fun convert(input: ByteBuffer, inBytes: Int, g: Float) {
        val samples = input.remaining() / inBytes
        if (converted.capacity() < samples * 4) converted = ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.nativeOrder())
        converted.clear()
        converted.order(ByteOrder.nativeOrder())
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> repeat(samples) { converted.putFloat(src.getFloat() * g) }
            C.ENCODING_PCM_16BIT -> repeat(samples) { converted.putFloat(src.getShort() / 32768f * g) }
            // DSD prepared as DoP while bit-perfect output was possible, and it no longer is: as PCM it
            // would be noise, so silence until the player has opened the file again (as PCM)
            C.ENCODING_PCM_24BIT if !exact && sampleRate >= DOP_MIN_RATE && channels > 0 && BitPerfect.isDop24(input, channels) ->
                repeat(samples) { converted.putFloat(0f) }
            C.ENCODING_PCM_24BIT -> repeat(samples) {
                val v = (src.get().toInt() and 0xFF) or ((src.get().toInt() and 0xFF) shl 8) or (src.get().toInt() shl 16)
                converted.putFloat(v / 8388608f * g)
            }
            C.ENCODING_PCM_32BIT -> repeat(samples) { converted.putFloat((src.getInt() / 2147483648.0).toFloat() * g) }
        }
        converted.flip()
        // The equalizer over the converted samples, channel by channel as they are interleaved
        if (!exact && !eq.idle && channels > 0) {
            var channel = 0
            var at = 0
            repeat(samples) {
                converted.putFloat(at, eq.process(channel, converted.getFloat(at)))
                at += 4
                if (++channel == channels) channel = 0
            }
        }
    }

    private fun clearPending() {
        passing = false
        source = null
        sourceTimeUs = C.TIME_UNSET
        converted.clear().limit(0)
    }

    override fun flush() {
        clearPending()
        eq.reset()
        super.flush()
    }

    override fun reset() {
        clearPending()
        super.reset()
    }

    private companion object {
        /** DSD64 as DoP; nothing slower can be DoP. */
        const val DOP_MIN_RATE = 176_400
        val CONVERTIBLE = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        /** The size of integer samples the float path carries exactly; 0 for the rest (32-bit, float, not PCM). */
        fun exactBits(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 0
        }

        fun bytesPerSample(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
    }
}

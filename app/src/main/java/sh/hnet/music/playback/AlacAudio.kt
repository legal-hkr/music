@file:androidx.annotation.OptIn(UnstableApi::class)

package sh.hnet.music.playback

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.source.MediaSource

/**
 * ALAC through Music's own decoder ([AlacDecoder]) as a Media3 renderer, into the same ReplayGainSink as the phone's
 * decoders: the gain, the equalizer and bit-perfect output work as for FLAC (16- and 24-bit integer PCM out).
 */
internal class AlacRenderer(
    handler: Handler?,
    listener: AudioRendererEventListener?,
    private val gainSink: ReplayGainSink,
) : DecoderAudioRenderer<AlacMediaDecoder>(handler, listener, gainSink) {
    override fun getName() = "AlacRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_ALAC) return C.FORMAT_UNSUPPORTED_TYPE
        val config = format.alacConfig() ?: return C.FORMAT_UNSUPPORTED_SUBTYPE
        return if (sinkSupportsFormat(config.pcmFormat())) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AlacMediaDecoder {
        val config = format.alacConfig() ?: throw AlacDecoderException("No ALAC config")
        return AlacMediaDecoder(config, format.maxInputSize)
    }

    override fun getOutputFormat(decoder: AlacMediaDecoder): Format = decoder.config.pcmFormat()

    override fun onStreamChanged(formats: Array<out Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        // Before super: the first stream's output starts inside it (as ReplayGainRenderer)
        gainSink.noteStream(this, offsetUs, trackGain(timeline, mediaPeriodId))
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onDisabled() {
        gainSink.forgetStreams(this)
        super.onDisabled()
    }
}

private fun Format.alacConfig(): AlacConfig? = initializationData.firstOrNull()?.let {
    try {
        AlacConfig.parse(it)
    } catch (_: AlacException) {
        null
    }
}

private fun AlacConfig.pcmFormat(): Format {
    val encoding = when (outputBytes) {
        2 -> C.ENCODING_PCM_16BIT
        3 -> C.ENCODING_PCM_24BIT
        else -> C.ENCODING_PCM_32BIT
    }
    return Util.getPcmFormat(encoding, outputChannels, sampleRate)
}

/** One ALAC frame per input buffer (one MP4 sample), decoded on Media3's decoder thread. Frames don't depend on each other. */
internal class AlacMediaDecoder(val config: AlacConfig, maxInputSize: Int) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AlacDecoderException>(
        // Only the arrays' sizes count: SimpleDecoder fills them with createInputBuffer and createOutputBuffer
        Array(BUFFERS) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED) },
        Array(BUFFERS) { SimpleDecoderOutputBuffer {} },
    ) {
    private val alac = AlacDecoder(config)
    private val outputSize = config.frameLength * config.outputChannels * config.outputBytes
    private val silence by lazy { ByteArray(outputSize) }
    private var damagedInARow = 0

    init {
        // An uncompressed frame is the largest an encoder writes; a little over it for the headers
        setInitialInputBufferSize(if (maxInputSize != Format.NO_VALUE) maxInputSize else outputSize + INPUT_SLACK)
    }

    override fun getName() = "sh.hnet.music.alac"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable) = AlacDecoderException("Unexpected decode error", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): AlacDecoderException? {
        val input = inputBuffer.data ?: return AlacDecoderException("No input")
        val output = outputBuffer.init(inputBuffer.timeUs, outputSize)
        try {
            alac.decode(input.array(), input.arrayOffset() + input.position(), input.remaining(), output)
            damagedInARow = 0
        } catch (e: AlacException) {
            // One damaged frame plays as silence, as the phone's decoders do with FLAC; a file that keeps failing is
            // an error (the player skips it and says the file is damaged), not a track of silence
            if (++damagedInARow > MAX_DAMAGED_IN_A_ROW) return AlacDecoderException("Damaged ALAC frames", e)
            output.clear()
            output.put(silence)
        }
        output.flip()
        return null
    }

    private companion object {
        const val BUFFERS = 16
        const val INPUT_SLACK = 1024

        /** About 0.7 s at 44.1 kHz. */
        const val MAX_DAMAGED_IN_A_ROW = 8
    }
}

internal class AlacDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

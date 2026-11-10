@file:androidx.annotation.OptIn(UnstableApi::class)

package sh.hnet.music.playback

// DRAFT: written against the Media3 extractor API from memory and not compiled (the place it was
// written in had no Media3 artifacts). The logic it leans on – DsdFormat and DsdToPcm – is tested.

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import sh.hnet.music.tags.ByteInput
import sh.hnet.music.tags.DsdFormat
import sh.hnet.music.tags.DsdStream
import sh.hnet.music.tags.TagFormatException
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media3's own extractors, with DSD asked first. */
class DsdExtractorsFactory(private val preferredPcmRate: () -> Int) : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> =
        arrayOf<Extractor>(DsdExtractor(preferredPcmRate)) + defaults.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf<Extractor>(DsdExtractor(preferredPcmRate)) + defaults.createExtractors(uri, responseHeaders)
}

/**
 * Plays DSF and DSDIFF files by handing the player float PCM: the 1-bit audio is converted here
 * ([DsdToPcm]), so everything after the extractor – the renderer, ReplayGain, gapless queueing –
 * sees an ordinary raw audio track.
 *
 * The audio is read in groups of 4096 bytes per channel (a DSF block; 12 ms of DSD64), each one
 * sample for the player. A seek lands on a group's start, where the converter starts afresh.
 *
 * Each file has its own extractor, so the converter's filter doesn't run across two tracks of a
 * gapless album: the last 0.15 ms of one and the first of the next fade to and from silence.
 *
 * @param preferredPcmRate the output rate from the settings (88 200 or 176 400), read when a file is opened
 */
class DsdExtractor(private val preferredPcmRate: () -> Int) : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput

    private var stream: DsdStream? = null
    private lateinit var converter: DsdToPcm

    /** Bytes of one channel that hold audio (the last DSF block is filled up with padding). */
    private var totalFrames = 0L
    private var nextFrame = 0L
    private var finished = false

    private var input = ByteArray(0)
    private var pcmBytes = ByteArray(0)
    private var pcm: ByteBuffer = ByteBuffer.wrap(pcmBytes)
    private val sample = ParsableByteArray()

    /** Time of the group the converter last started at, and the PCM frames written since. */
    private var startTimeUs = 0L
    private var framesOut = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val magic = ByteArray(16)
        return try {
            input.peekFully(magic, 0, magic.size)
            DsdFormat.isDsf(magic) || DsdFormat.isDff(magic)
        } catch (_: EOFException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val s = stream ?: return readHeader(input)
        if (finished) return Extractor.RESULT_END_OF_INPUT

        val frames = minOf(GROUP.toLong(), totalFrames - nextFrame).toInt()
        // DSF: always a whole block of every channel, whatever part of it is audio
        val bytes = if (s.blockSize > 0) s.blockSize * s.channels else frames * s.channels
        val read = frames > 0 && try {
            input.readFully(this.input, 0, bytes, /* allowEndOfInput = */ true)
        } catch (_: EOFException) {
            false // cut off inside a group: the file ends here
        }
        pcm.clear()
        if (!read) {
            converter.finish(pcm)
            writeSample()
            finished = true
            return Extractor.RESULT_END_OF_INPUT
        }
        if (s.blockSize > 0) converter.planar(this.input, s.blockSize, frames, s.lsbFirst, pcm)
        else converter.interleaved(this.input, 0, frames, s.lsbFirst, pcm)
        nextFrame += frames
        writeSample()
        return Extractor.RESULT_CONTINUE
    }

    private fun readHeader(input: ExtractorInput): Int {
        val magic = ByteArray(16)
        input.peekFully(magic, 0, magic.size)
        val s = try {
            if (DsdFormat.isDsf(magic)) DsdFormat.readDsf(InputBytes(input)) else DsdFormat.readDff(InputBytes(input))
        } catch (e: TagFormatException) {
            throw ParserException.createForMalformedContainer(e.message, e)
        } ?: throw ParserException.createForUnsupportedContainerFeature("DST-compressed DSD")
        // DSF blocks of another size than ours would need groups of their own
        if (s.blockSize > 0 && s.blockSize != GROUP) throw ParserException.createForUnsupportedContainerFeature("DSF block size")

        val pcmRate = DsdToPcm.pcmRateFor(s.sampleRate, preferredPcmRate())
        converter = DsdToPcm(s.channels, s.sampleRate, pcmRate)
        totalFrames = (s.sampleCount + 7) / 8
        nextFrame = 0
        startTimeUs = 0
        framesOut = 0
        finished = false
        this.input = ByteArray(GROUP * s.channels)
        pcmBytes = ByteArray(converter.maxOutputBytes(GROUP))
        pcm = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        stream = s

        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setChannelCount(s.channels)
                .setSampleRate(pcmRate)
                .setAverageBitrate(pcmRate * s.channels * 32)
                .build(),
        )
        output.seekMap(Seeking(s, totalFrames))
        // Both headers end where the audio starts
        return Extractor.RESULT_CONTINUE
    }

    /** What the converter put into [pcm], as one sample; nothing when it wrote nothing. */
    private fun writeSample() {
        val size = pcm.position()
        if (size == 0) return
        val s = stream ?: return
        sample.reset(pcmBytes, size)
        track.sampleData(sample, size)
        val timeUs = startTimeUs + framesOut * C.MICROS_PER_SECOND / converter.pcmRate
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        framesOut += size / (s.channels * 4)
    }

    override fun seek(position: Long, timeUs: Long) {
        val s = stream
        if (s == null || position < s.dataOffset) {
            // From the top (a retry, a loop): the header is read again
            stream = null
            return
        }
        // Seek points are group starts, where the converter can begin as at a file's start
        nextFrame = (position - s.dataOffset) / s.channels
        startTimeUs = nextFrame * 8 * C.MICROS_PER_SECOND / s.sampleRate
        framesOut = 0
        finished = false
        converter.reset()
    }

    override fun release() = Unit

    /** A group every 4096 bytes per channel; the stream's rate is constant, so time is position. */
    private class Seeking(private val stream: DsdStream, private val totalFrames: Long) : SeekMap {
        override fun isSeekable() = true

        override fun getDurationUs() = stream.sampleCount * C.MICROS_PER_SECOND / stream.sampleRate

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val lastGroup = ((totalFrames - 1) / GROUP).coerceAtLeast(0L)
            val group = (timeUs * stream.sampleRate / (8 * C.MICROS_PER_SECOND) / GROUP).coerceIn(0L, lastGroup)
            val frame = group * GROUP
            return SeekMap.SeekPoints(
                SeekPoint(frame * 8 * C.MICROS_PER_SECOND / stream.sampleRate, stream.dataOffset + frame * stream.channels),
            )
        }
    }

    /** The header parsers' view of the player's input. */
    private class InputBytes(private val input: ExtractorInput) : ByteInput {
        private val one = ByteArray(1)

        override fun readByte(): Int {
            input.readFully(one, 0, 1)
            return one[0].toInt() and 0xFF
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) = input.readFully(buffer, offset, length)

        override fun skip(count: Long) {
            // Chunks before the audio are small; anything else isn't a file we play
            if (count < 0 || count > MAX_SKIP) throw TagFormatException("Chunk too large")
            input.skipFully(count.toInt())
        }
    }

    private companion object {
        /** Bytes of one channel per sample handed on: DSF's block size, used for DSDIFF too. */
        const val GROUP = 4096
        const val MAX_SKIP = 1L shl 20
    }
}

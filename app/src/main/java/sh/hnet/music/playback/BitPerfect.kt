@file:androidx.annotation.OptIn(UnstableApi::class)

package sh.hnet.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The arithmetic of bit-perfect output (stage B1), pure and unit-tested: which of a device's formats
 * carries a stream unchanged, and the samples' way back from float to the integers they were.
 */
/** Why a stream goes through the mixer although the USB device's bit-perfect switch is on. */
enum class Refusal {
    /** The device lists no bit-perfect format for the stream's rate and depth. */
    FORMAT,

    /** 32-bit and float sources (DSD converted to PCM among them): Music's float path can't hold them bit for bit. */
    DEPTH,

    /** More than two channels. */
    CHANNELS,
}

object BitPerfect {
    /**
     * The device format a stream of [sourceBits] integer samples at [sampleRate] goes out in
     * unchanged: stereo, the same rate, the narrowest sample that holds every bit (16 in 24 or 32 is
     * padding). Null when the device has none, or the stream isn't one we carry exactly: more than
     * two channels, or 32-bit and float sources, which our float path can't hold bit for bit.
     */
    /**
     * Why [choose] found no format for a stream: Music doesn't carry it exactly ([Refusal.CHANNELS],
     * [Refusal.DEPTH]) or the device doesn't take it ([Refusal.FORMAT]). The same checks, in the same order.
     */
    fun refusal(sourceBits: Int, channels: Int): Refusal = when {
        channels !in 1..2 -> Refusal.CHANNELS
        sourceBits !in EXACT_BITS -> Refusal.DEPTH
        else -> Refusal.FORMAT
    }

    fun choose(formats: List<OutputFormat>, sampleRate: Int, sourceBits: Int, channels: Int): OutputFormat? {
        if (channels !in 1..2 || sourceBits !in EXACT_BITS) return null
        return formats
            .filter { !it.float && it.bits in OUTPUT_BITS && it.channels == 2 && it.sampleRate == sampleRate && it.bits >= sourceBits }
            .minByOrNull { it.bits }
    }

    /** The bytes [inputBytes] of float samples in [channels] channels become as stereo [bits]-bit integers. */
    fun outputBytes(inputBytes: Int, channels: Int, bits: Int): Int = inputBytes / (4 * channels) * (bits / 8) * 2

    /**
     * [input]'s float samples (from 16- or 24-bit integers, so exact) written to [output] as
     * [bits]-bit little-endian integers; mono goes to both channels. [input]'s position doesn't move.
     */
    fun toIntegers(input: ByteBuffer, channels: Int, bits: Int, output: ByteBuffer) {
        val source = input.duplicate().order(ByteOrder.nativeOrder())
        output.order(ByteOrder.LITTLE_ENDIAN)
        val frames = source.remaining() / (4 * channels)
        repeat(frames) {
            repeat(channels) {
                // The sample on a 24-bit scale: a whole number for every 16- and 24-bit source
                val value = (source.getFloat() * SCALE_24).roundToInt().coerceIn(-8_388_608, 8_388_607)
                repeat(if (channels == 1) 2 else 1) { put(output, value, bits) }
            }
        }
    }

    private fun put(output: ByteBuffer, value24: Int, bits: Int) {
        when (bits) {
            16 -> output.putShort((value24 shr 8).toShort())
            24 -> {
                output.put(value24.toByte())
                output.put((value24 shr 8).toByte())
                output.put((value24 shr 16).toByte())
            }
            else -> output.putInt(value24 shl 8)
        }
    }

    /**
     * Whether [input] (24-bit little-endian samples, interleaved) is DoP: the marker byte on top of
     * every sample, alternating from frame to frame. Checked over the first frames; music doesn't
     * look like this.
     */
    fun isDop24(input: ByteBuffer, channels: Int): Boolean =
        isDop(input.remaining() / (3 * channels), channels) { frame, channel -> input.get(input.position() + (frame * channels + channel) * 3 + 2) }

    /** The same for float samples made from such 24-bit ones. */
    fun isDopFloat(input: ByteBuffer, channels: Int): Boolean {
        val source = input.duplicate().order(ByteOrder.nativeOrder())
        return isDop(source.remaining() / (4 * channels), channels) { frame, channel ->
            ((source.getFloat(source.position() + (frame * channels + channel) * 4) * SCALE_24).roundToInt() shr 16).toByte()
        }
    }

    private inline fun isDop(frames: Int, channels: Int, top: (frame: Int, channel: Int) -> Byte): Boolean {
        if (channels <= 0 || frames < 2) return false
        var expected = top(0, 0)
        if (expected != DsdToDop.MARKER_A && expected != DsdToDop.MARKER_B) return false
        for (frame in 0 until minOf(frames, DOP_FRAMES_CHECKED)) {
            for (channel in 0 until channels) if (top(frame, channel) != expected) return false
            expected = if (expected == DsdToDop.MARKER_A) DsdToDop.MARKER_B else DsdToDop.MARKER_A
        }
        return true
    }

    private const val DOP_FRAMES_CHECKED = 8

    /** Sources the float path carries exactly. */
    private val EXACT_BITS = setOf(16, 24)
    private val OUTPUT_BITS = setOf(16, 24, 32)
    private const val SCALE_24 = 8_388_608f
}

/**
 * Bit-perfect output to a USB DAC (stage B1): asks the system to send our stream to the device as it
 * is, without mixing, volume or effects (AudioManager's preferred mixer attributes, API 34). The
 * format follows each stream: whatever the connected device lists is used, nothing is assumed about it.
 *
 * The sink decides per stream ([prepare], at configure); the output provider acts on it when the
 * output for that stream is opened ([open]), so a format change never cuts the end of the track before.
 */
class BitPerfectController(context: Context) {
    /** A stream to send unchanged: where, as what, and how its samples are laid out. */
    class Choice(val device: AudioDeviceInfo, val attributes: AudioMixerAttributes, val bits: Int, val sourceChannels: Int) {
        /** Two choices are the same output when these agree. */
        val key = Triple(device.id, attributes.format.sampleRate, bits to sourceChannels)
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    /** The USB devices whose own switch is on (SPEC-output §9), by [AudioOutput.key]. */
    @Volatile var enabledKeys: Set<String> = emptySet()

    /**
     * What goes to the USB device now: [bitPerfect] or through the mixer although its switch is on; [bits] 0 when
     * not known; [refusal] why it went through the mixer (SPEC-output-and-grid §A5.5).
     */
    data class Stream(val bitPerfect: Boolean, val bits: Int, val sampleRate: Int, val refusal: Refusal? = null)

    private val _stream = MutableStateFlow<Stream?>(null)
    /** For the Output pages; null when nothing was opened for a device with the switch on. */
    val stream: StateFlow<Stream?> = _stream.asStateFlow()

    /** The stream last refused although the device's switch is on, to say why it plays through the mixer. */
    @Volatile private var refused: Stream? = null

    /** The decision for the stream being configured; null: it plays the usual way. */
    @Volatile var pending: Choice? = null
        private set

    /** The device whose mixer we have set, to clear it again, and what it was set for. */
    @Volatile private var set: AudioDeviceInfo? = null
    @Volatile private var setKey: Any? = null

    private val _active = MutableStateFlow(false)
    /** Whether what plays now goes out bit-perfect (the now-playing chip, the equalizer's note). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Called on the main thread when what plays has to be opened again: it would now go out another way. */
    var onOutputChanged: (() -> Unit)? = null
    private var lastUsb: Int? = null
    private var lastWanted = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        lastUsb = usbDevice()?.id
        lastWanted = wanted()
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun release() {
        audio.unregisterAudioDeviceCallback(callback)
        onOutputChanged = null
        pending = null
        refused = null
        close()
    }

    /**
     * To be called on the main thread when something a decision rests on has changed: a USB output came
     * or went (seen here), the devices' switches, or the output music is sent to. The output is opened
     * again only when that changes how it goes out – not, say, for the switch of a device that isn't
     * connected, which would only interrupt the music.
     */
    fun refresh() {
        val now = usbDevice()?.id
        val changed = now != lastUsb
        lastUsb = now
        if (changed && now == null) {
            close()
            refused = null
            _stream.value = null
        }
        val wanted = wanted()
        if (wanted != lastWanted || (wanted && changed)) {
            lastWanted = wanted
            onOutputChanged?.invoke()
        }
    }

    /** Whether music goes to a USB device whose switch is on: the only case in which anything of ours depends on the output. */
    private fun wanted(): Boolean = usbDevice()?.let(::chosen) == true

    /** [device] is where music is sent now – a USB device can be connected while music plays elsewhere – and its switch is on. */
    private fun chosen(device: AudioDeviceInfo): Boolean =
        CurrentOutput.output(device).key in enabledKeys && audio.getAudioDevicesForAttributes(media).any { it.id == device.id }

    private fun usbDevice(): AudioDeviceInfo? = OutputDevices.usbDevice(audio)

    /**
     * Decides for a stream of [sourceBits]-bit integers ([sourceBits] 0: anything else) whether it goes
     * out bit-perfect; true when it does, and nothing may change its samples.
     */
    fun prepare(sampleRate: Int, sourceBits: Int, channels: Int): Boolean {
        pending = choose(sampleRate, sourceBits, channels)
        refused = if (pending == null && wanted()) Stream(false, sourceBits, sampleRate, BitPerfect.refusal(sourceBits, channels)) else null
        // Already through the mixer, where one output serves stream after stream: the reason follows the stream
        if (pending == null && _stream.value?.bitPerfect == false) _stream.value = refused
        return pending != null
    }

    private fun choose(sampleRate: Int, sourceBits: Int, channels: Int): Choice? {
        val device = usbDevice() ?: return null
        if (!chosen(device)) return null
        val offered = audio.getSupportedMixerAttributes(device).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        val formats = offered.map { OutputDevices.format(it.format) }
        val chosen = BitPerfect.choose(formats, sampleRate, sourceBits, channels) ?: return null
        val attributes = offered.firstOrNull { OutputDevices.format(it.format) == chosen } ?: return null
        return Choice(device, attributes, chosen.bits, channels)
    }

    /**
     * Whether a DSD stream is handed on as DoP rather than converted to PCM: bit-perfect output is
     * on and the connected device takes 24-bit samples at the rate DoP needs. Asked when a file is opened.
     */
    fun wantsDop(dsdRate: Int, channels: Int): Boolean {
        val rate = DsdToDop.pcmRate(dsdRate)
        return rate > 0 && choose(rate, 24, channels) != null
    }

    /** Sets the device's mixer for [choice]; false when the system refuses (the stream then plays the usual way). */
    fun open(choice: Choice): Boolean {
        // A seek opens a new output for the same stream: the mixer is as we left it, and setting it
        // again would only make the system reopen the device
        if (set != null && setKey == choice.key) {
            _active.value = true
            _stream.value = Stream(true, choice.bits, choice.attributes.format.sampleRate)
            return true
        }
        val done = try {
            audio.setPreferredMixerAttributes(media, choice.device, choice.attributes)
        } catch (_: RuntimeException) {
            false
        }
        if (done) {
            set = choice.device
            setKey = choice.key
        }
        _active.value = done
        _stream.value = if (done) Stream(true, choice.bits, choice.attributes.format.sampleRate) else refused
        return done
    }

    /** Back to the system's own mixing. */
    fun close() {
        _active.value = false
        _stream.value = refused
        val device = set ?: return
        set = null
        setKey = null
        try {
            audio.clearPreferredMixerAttributes(media, device)
        } catch (_: RuntimeException) {
            // The device is gone, and its mixer with it
        }
    }

}

/**
 * Opens the player's outputs (Media3's AudioOutputProvider): the usual AudioTrack, or, for a stream
 * the controller chose, one in the device's own integer format behind a converter. The sink above
 * always delivers float; it never learns the difference.
 */
class BitPerfectOutputProvider private constructor(
    private val controller: BitPerfectController,
    private val opening: Array<BitPerfectController.Choice?>,
    provider: AudioOutputProvider,
) : ForwardingAudioOutputProvider(provider) {

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val choice = controller.pending
        val fits = choice != null && config.encoding == C.ENCODING_PCM_FLOAT && !config.isOffload && !config.isTunneling &&
            config.sampleRate == choice.attributes.format.sampleRate
        if (choice != null && fits && controller.open(choice)) {
            val exact = config.buildUpon()
                .setEncoding(choice.attributes.format.encoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(BitPerfect.outputBytes(config.bufferSize, choice.sourceChannels, choice.bits))
                .build()
            try {
                // The track takes the very format the mixer was set to
                opening[0] = choice
                return ChosenOutput(super.getAudioOutput(exact), choice, controller)
            } catch (_: AudioOutputProvider.InitializationException) {
                // The device took the setting but not the track: the usual way
            } finally {
                opening[0] = null
            }
        }
        controller.close()
        // A stream prepared as bit-perfect that couldn't be opened so: DoP must not be heard as PCM
        return ChosenOutput(super.getAudioOutput(config), null, controller, guardChannels = if (choice != null) Integer.bitCount(config.channelMask) else 0)
    }

    companion object {
        fun create(context: Context, controller: BitPerfectController): BitPerfectOutputProvider {
            val opening = arrayOfNulls<BitPerfectController.Choice>(1)
            val tracks = AudioTrackAudioOutputProvider.Builder(context)
                .setAudioTrackBuilderModifier { builder, _ -> opening[0]?.let { builder.setAudioFormat(it.attributes.format) } }
                .build()
            return BitPerfectOutputProvider(controller, opening, tracks)
        }
    }
}

/**
 * An output as the controller chose it. With a [choice], the float samples written to it go on as
 * the device's integers. Either way it is reused for the next stream only while the choice stays
 * the same: 16- and 24-bit files at one rate look alike to the sink, but go out differently.
 */
private class ChosenOutput(
    private val output: AudioOutput,
    private val choice: BitPerfectController.Choice?,
    private val controller: BitPerfectController,
    /** Without a [choice]: the channels of a float stream to check for DoP and silence; 0 not to. */
    private val guardChannels: Int = 0,
) : ForwardingAudioOutput(output) {
    private var converted: ByteBuffer = ByteBuffer.allocateDirect(0).apply { limit(0) }
    private var sourceStart = 0
    private var inFrame = 1
    private var outFrame = 1

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        // The same buffer comes back until the track has taken all of it
        if (!converted.hasRemaining()) {
            when {
                choice != null -> {
                    val size = BitPerfect.outputBytes(buffer.remaining(), choice.sourceChannels, choice.bits)
                    if (converted.capacity() < size) converted = ByteBuffer.allocateDirect(size)
                    converted.clear()
                    BitPerfect.toIntegers(buffer, choice.sourceChannels, choice.bits, converted)
                    converted.flip()
                    inFrame = 4 * choice.sourceChannels
                    outFrame = choice.bits / 8 * 2
                }
                guardChannels > 0 && BitPerfect.isDopFloat(buffer, guardChannels) -> {
                    // Silence of the same length
                    val size = buffer.remaining()
                    if (converted.capacity() < size) converted = ByteBuffer.allocateDirect(size)
                    converted.clear()
                    repeat(size) { converted.put(0) }
                    converted.flip()
                    inFrame = 1
                    outFrame = 1
                }
                else -> return output.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            }
            sourceStart = buffer.position()
        }
        val done = output.write(converted, encodedAccessUnitCount, presentationTimeUs)
        // Mirror what the track took, in the sink's frames
        buffer.position(sourceStart + converted.position() / outFrame * inFrame)
        return done
    }

    override fun flush() {
        converted.clear().limit(0)
        super.flush()
    }

    override fun canReuseAudioOutput(
        currentConfig: AudioOutputProvider.OutputConfig,
        newFormat: AudioOutputProvider.FormatConfig,
        newConfig: AudioOutputProvider.OutputConfig,
    ): Boolean = super.canReuseAudioOutput(currentConfig, newFormat, newConfig) && controller.pending?.key == choice?.key
}

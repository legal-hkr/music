package sh.hnet.music.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** One way a device's mixer can be set up, as the system reports it. [bits] 0: an encoding we don't name. */
data class OutputFormat(val sampleRate: Int, val bits: Int, val float: Boolean, val channels: Int)

/** The formats sharing a sample size and channel count, with every rate they come in, ascending. */
data class OutputFormatGroup(val bits: Int, val float: Boolean, val channels: Int, val sampleRates: List<Int>)

/**
 * A connected USB audio output and what the system offers for it (bit-perfect stage B0): the formats
 * it can be sent unchanged ([bitPerfect]) and the ones the system mixes to ([mixed]).
 */
data class UsbOutput(val name: String, val bitPerfect: List<OutputFormat>, val mixed: List<OutputFormat>)

object OutputDevices {
    /** [formats] by sample size and channels (widest first), each with its rates in ascending order. */
    fun grouped(formats: List<OutputFormat>): List<OutputFormatGroup> =
        formats.groupBy { Triple(it.bits, it.float, it.channels) }
            .map { (key, list) -> OutputFormatGroup(key.first, key.second, key.third, list.map { it.sampleRate }.distinct().sorted()) }
            .sortedWith(compareBy<OutputFormatGroup> { it.channels }.thenBy { it.bits }.thenBy { it.float })

    /**
     * The first connected USB output, again whenever outputs come and go. Asking what a device
     * supports needs no permission and changes nothing.
     */
    fun usbOutput(context: Context): Flow<UsbOutput?> = callbackFlow {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun read(): UsbOutput? {
            val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in USB_TYPES } ?: return null
            val attributes = audio.getSupportedMixerAttributes(device)
            fun formats(behavior: Int) = attributes.filter { it.mixerBehavior == behavior }.map { format(it.format) }.distinct()
            return UsbOutput(
                name = device.productName?.toString().orEmpty(),
                bitPerfect = formats(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT),
                mixed = formats(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT),
            )
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { trySend(read()) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { trySend(read()) }
        }
        // Registering reports the outputs already there
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        trySend(read())
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun format(format: AudioFormat): OutputFormat {
        val (bits, float) = when (format.encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8 to false
            AudioFormat.ENCODING_PCM_16BIT -> 16 to false
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24 to false
            AudioFormat.ENCODING_PCM_32BIT -> 32 to false
            AudioFormat.ENCODING_PCM_FLOAT -> 32 to true
            else -> 0 to false
        }
        return OutputFormat(format.sampleRate, bits, float, format.channelCount)
    }

    private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
}

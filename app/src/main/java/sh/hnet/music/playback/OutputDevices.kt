package sh.hnet.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

/** One way a device's mixer can be set up, as the system reports it. [bits] 0: an encoding we don't name. */
data class OutputFormat(val sampleRate: Int, val bits: Int, val float: Boolean, val channels: Int)

/** The formats sharing a sample size and channel count, with every rate they come in, ascending. */
data class OutputFormatGroup(val bits: Int, val float: Boolean, val channels: Int, val sampleRates: List<Int>)

object OutputDevices {
    /** [formats] by sample size and channels (widest first), each with its rates in ascending order. */
    fun grouped(formats: List<OutputFormat>): List<OutputFormatGroup> =
        formats.groupBy { Triple(it.bits, it.float, it.channels) }
            .map { (key, list) -> OutputFormatGroup(key.first, key.second, key.third, list.map { it.sampleRate }.distinct().sorted()) }
            .sortedWith(compareBy<OutputFormatGroup> { it.channels }.thenBy { it.bits }.thenBy { it.float })

    /** The first connected USB audio output. */
    fun usbDevice(audio: AudioManager): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in USB_TYPES }

    /** [format] as we compare and show it. */
    fun format(format: AudioFormat): OutputFormat {
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

package sh.hnet.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What an output is, as far as equalizer profiles tell outputs apart. */
enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, OTHER }

/**
 * The output music plays through (device profiles, stage P0). [name] is the device's own name where
 * it has one (a USB DAC, Bluetooth headphones); empty for the phone's speaker and the headphone
 * jack, which the system names after the phone.
 */
data class AudioOutput(val kind: OutputKind, val name: String) {
    /**
     * What a profile is tied to. A Bluetooth device's address would need the nearby-devices
     * permission, so devices are told apart by name: two of the same model share a profile.
     */
    val key: String
        get() = when (kind) {
            OutputKind.SPEAKER -> "speaker"
            OutputKind.WIRED -> "wired"
            OutputKind.USB -> "usb:$name"
            OutputKind.BLUETOOTH -> "bluetooth:$name"
            OutputKind.OTHER -> "other:$name"
        }
}

object CurrentOutput {
    /** The kind of an AudioDeviceInfo type. */
    fun kind(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.BLUETOOTH
        else -> OutputKind.OTHER
    }

    /** The output for a device of [type] the system calls [productName]. */
    fun output(type: Int, productName: String?): AudioOutput {
        val kind = kind(type)
        val named = kind != OutputKind.SPEAKER && kind != OutputKind.WIRED
        return AudioOutput(kind, if (named) productName?.trim().orEmpty() else "")
    }

    /**
     * The output the system would play music through now, again whenever outputs come and go or the
     * system's route changes. Needs no permission and changes nothing.
     */
    fun flow(context: Context): Flow<AudioOutput?> = callbackFlow {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        fun read(): AudioOutput? = audio.getAudioDevicesForAttributes(media).firstOrNull()?.let { output(it.type, it.productName?.toString()) }
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { trySend(read()) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { trySend(read()) }
        }
        val handler = Handler(Looper.getMainLooper())
        audio.registerAudioDeviceCallback(devices, handler)
        // The output picked in the system's output switcher, among devices that stay connected
        val router = MediaRouter2.getInstance(context)
        val routes = object : MediaRouter2.RouteCallback() {}
        val controller = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) { trySend(read()) }
        }
        router.registerRouteCallback(handler::post, routes, RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build())
        router.registerControllerCallback(handler::post, controller)
        trySend(read())
        awaitClose {
            audio.unregisterAudioDeviceCallback(devices)
            router.unregisterControllerCallback(controller)
            router.unregisterRouteCallback(routes)
        }
    }.distinctUntilChanged()
}

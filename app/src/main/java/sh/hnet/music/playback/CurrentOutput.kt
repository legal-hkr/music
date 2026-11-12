package sh.hnet.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

/** What an output is (SPEC-output §2.1). */
@Serializable
enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, OTHER }

/**
 * An output music can play through. [name] is the device's own name where it has one (a USB DAC,
 * Bluetooth headphones); empty for the phone's speaker and the headphone jack, which the system
 * names after the phone.
 */
data class AudioOutput(val kind: OutputKind, val name: String) {
    /**
     * What a device is recognized by. A Bluetooth device's address would need the nearby-devices
     * permission, so devices are told apart by name: two of the same model are one device here.
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

/** The outputs as the system has them now: where music would play, what is connected, and what each USB output takes bit-perfect. */
data class OutputSnapshot(
    val current: AudioOutput? = null,
    val connected: List<AudioOutput> = emptyList(),
    val bitPerfectFormats: Map<String, List<OutputFormat>> = emptyMap(),
)

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

    /** The output for a device of [type] the system calls [productName]; a USB device without Android's "USB-Audio - " before its name. */
    fun output(type: Int, productName: String?): AudioOutput {
        val kind = kind(type)
        val name = productName?.trim().orEmpty()
        return when (kind) {
            OutputKind.SPEAKER, OutputKind.WIRED -> AudioOutput(kind, "")
            OutputKind.USB -> AudioOutput(kind, name.removePrefix(USB_PREFIX).trim())
            else -> AudioOutput(kind, name)
        }
    }

    fun output(device: AudioDeviceInfo): AudioOutput = output(device.type, device.productName?.toString())

    /**
     * Opens the system's output switcher, where the output is chosen for the whole phone; false when
     * the system has none to show.
     */
    fun showSwitcher(context: Context): Boolean = MediaRouter2.getInstance(context).showSystemOutputSwitcher()

    /**
     * The outputs, again whenever one comes or goes or the system's route changes. Needs no
     * permission and changes nothing. To be collected on the main thread.
     */
    fun flow(context: Context): Flow<OutputSnapshot> = callbackFlow {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        fun read(): OutputSnapshot {
            val current = audio.getAudioDevicesForAttributes(media).firstOrNull()?.let(::output)
            val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Outputs of no kind of ours (telephony, the system's own loops) are no devices to the user, unless music plays there
            val connected = (listOfNotNull(current) + devices.map(::output).filter { it.kind != OutputKind.OTHER }).distinctBy { it.key }
            val formats = devices.filter { kind(it.type) == OutputKind.USB }.associate { device ->
                output(device).key to audio.getSupportedMixerAttributes(device)
                    .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                    .map { OutputDevices.format(it.format) }.distinct()
            }
            return OutputSnapshot(current, connected, formats)
        }
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

    private const val USB_PREFIX = "USB-Audio - "
}

package sh.hnet.music.playback

/**
 * The path of the sound from the file to the device, as the output details show it
 * (SPEC-output-and-grid §A5, with the owner's decisions of 22.11.2026). Pure Kotlin, JVM-tested:
 * only facts the app knows, from the same decisions the audio path makes.
 */
object SoundPath {
    /** What the details are made from. */
    data class Input(
        /** The file's 1-bit rate when it is DSD, else null. */
        val dsdRate: Int?,
        /** The file's sample rate (PCM). */
        val sampleRate: Int,
        /** The device is USB and its bit-perfect switch is on. */
        val usbSwitchOn: Boolean,
        /** What the bit-perfect controller last decided for that device. */
        val stream: BitPerfectController.Stream?,
        val gainMode: ReplayGainMode,
        /** The gain the service applies now; null when none. */
        val gain: AppliedGain?,
        /** Whether the file has any ReplayGain tag. */
        val hasGainTags: Boolean,
        val equalizerOn: Boolean,
        /** The equalizer's values do something: pre-amp or an enabled filter's gain not 0 dB. */
        val equalizerChanges: Boolean,
        /** Settings › Playback › DSD: the preferred PCM rate and +6 dB. */
        val dsdPcmRate: Int,
        val dsdBoost: Boolean,
    )

    sealed interface Dsd {
        data object AsDop : Dsd
        data class ToPcm(val rate: Int, val boost: Boolean) : Dsd
    }

    sealed interface Gain {
        data object Off : Gain
        data object Skipped : Gain
        /** ReplayGain on, the file without tags, the pre-amp for such files 0 dB: nothing applied. */
        data object NoTags : Gain
        /** The pre-amp for files without tags. */
        data class PreAmp(val db: Float) : Gain
        data class Tagged(val db: Float, val album: Boolean) : Gain
    }

    enum class Equalizer { OFF, SKIPPED, ON }

    enum class Volume { SYSTEM, DEVICE }

    sealed interface Route {
        data class BitPerfect(val bits: Int, val rate: Int) : Route
        data class Dop(val dsdRate: Int, val rate: Int) : Route
        /** Through the system mixer: what Music hands over, 32-bit float at [rate]. */
        data class Mixer(val rate: Int) : Route
    }

    /** Why a USB device with its switch on gets the mixer. */
    sealed interface Why {
        data class NoDop(val rate: Int) : Why
        data class Format(val bits: Int, val rate: Int) : Why
        data object Depth : Why
        data object Channels : Why
    }

    data class Path(
        val dsd: Dsd?,
        val gain: Gain,
        val equalizer: Equalizer,
        val volume: Volume,
        val route: Route,
        val why: Why?,
        /** The equalizer is heard and its values do something. */
        val equalizerChanges: Boolean,
    ) {
        /** Whether a stage of "In Music" changes the sound, for the step's colour (§A5.2). */
        val gainChanges: Boolean get() = when (gain) {
            is Gain.PreAmp -> gain.db != 0f
            is Gain.Tagged -> gain.db != 0f
            else -> false
        }

        val dsdChanges: Boolean get() = dsd is Dsd.ToPcm
        val volumeChanges: Boolean get() = volume == Volume.SYSTEM

        /** Whether In Music changes the sound at all: its node's colour. */
        val changes: Boolean get() = dsdChanges || gainChanges || equalizerChanges || volumeChanges
    }

    fun of(input: Input): Path {
        val dsd = input.dsdRate
        // What leaves Music: the file's rate, or the rate DSD is converted to, or DoP's carrier
        val pcmRate = if (dsd != null) DsdToPcm.pcmRateFor(dsd, input.dsdPcmRate) else input.sampleRate
        val dopRate = if (dsd != null) DsdToDop.pcmRate(dsd) else 0
        val stream = input.stream.takeIf { input.usbSwitchOn }
        // The controller's last word holds only when it is about this stream (a moment after a change of track it isn't)
        val exact = stream?.takeIf { it.bitPerfect && it.sampleRate == (if (dsd != null) dopRate else input.sampleRate) }
        val route = when {
            exact != null && dsd != null -> Route.Dop(dsd, exact.sampleRate)
            exact != null -> Route.BitPerfect(exact.bits, exact.sampleRate)
            else -> Route.Mixer(pcmRate)
        }
        val bitPerfect = route !is Route.Mixer
        val why = when {
            bitPerfect || !input.usbSwitchOn -> null
            dsd != null -> if (dopRate > 0) Why.NoDop(dopRate) else null
            stream == null || stream.bitPerfect || stream.sampleRate != input.sampleRate -> null
            else -> when (stream.refusal) {
                Refusal.DEPTH -> Why.Depth
                Refusal.CHANNELS -> Why.Channels
                Refusal.FORMAT -> Why.Format(stream.bits, stream.sampleRate)
                null -> null
            }
        }
        val gain = when {
            input.gainMode == ReplayGainMode.OFF -> Gain.Off
            bitPerfect -> Gain.Skipped
            input.gain == null -> Gain.NoTags
            !input.hasGainTags -> Gain.PreAmp(input.gain.db)
            else -> Gain.Tagged(input.gain.db, input.gain.album)
        }
        val equalizer = when {
            !input.equalizerOn -> Equalizer.OFF
            bitPerfect -> Equalizer.SKIPPED
            else -> Equalizer.ON
        }
        return Path(
            dsd = when {
                dsd == null -> null
                route is Route.Dop -> Dsd.AsDop
                else -> Dsd.ToPcm(pcmRate, input.dsdBoost)
            },
            gain = gain,
            equalizer = equalizer,
            volume = if (bitPerfect) Volume.DEVICE else Volume.SYSTEM,
            route = route,
            why = why,
            equalizerChanges = equalizer == Equalizer.ON && input.equalizerChanges,
        )
    }

    /** Whether the equalizer's values change the sound: a pre-amp or an enabled filter's gain other than 0 dB (Flat doesn't). */
    fun changes(settings: EqSettings): Boolean = settings.preampDb != 0f || settings.filters.any { it.enabled && it.gainDb != 0f }
}

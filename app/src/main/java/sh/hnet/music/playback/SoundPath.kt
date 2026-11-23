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
        /** The file's bit depth when lossless PCM; null for lossy files (decoded to 16 bits) and DSD. */
        val bits: Int?,
        val channels: Int,
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
        /** [bits]: the device's sample DoP is carried in, 24 or wider. */
        data class Dop(val dsdRate: Int, val rate: Int, val bits: Int) : Route
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
        // What Music can carry exactly at all (BitPerfect.choose's checks): never claimed for anything else, whatever the controller last said
        val carried = input.channels in 1..2 && (dsd != null || input.bits == null || input.bits in EXACT_BITS)
        // The controller's last word holds only when it is about this stream (a moment after a change of track it isn't)
        val exact = stream?.takeIf { carried && it.bitPerfect && it.sampleRate == (if (dsd != null) dopRate else input.sampleRate) }
        val route = when {
            exact != null && dsd != null -> Route.Dop(dsd, exact.sampleRate, exact.bits)
            exact != null -> Route.BitPerfect(exact.bits, exact.sampleRate)
            else -> Route.Mixer(pcmRate)
        }
        val bitPerfect = route !is Route.Mixer
        val why = when {
            bitPerfect || !input.usbSwitchOn -> null
            // More than two channels can't go as DoP whatever the device offers
            dsd != null -> if (input.channels > 2) Why.Channels else if (dopRate > 0) Why.NoDop(dopRate) else null
            // Music's own limits follow from the file itself
            input.channels > 2 -> Why.Channels
            input.bits != null && input.bits !in EXACT_BITS -> Why.Depth
            // Otherwise only from a refusal about this stream: the controller may already be deciding the next one
            stream == null || stream.bitPerfect || stream.sampleRate != input.sampleRate -> null
            // A decoder's float output, where the file gives no depth
            stream.refusal == Refusal.DEPTH && input.bits == null -> Why.Depth
            stream.refusal == Refusal.FORMAT && (input.bits == null || stream.bits == input.bits) -> Why.Format(stream.bits, stream.sampleRate)
            else -> null
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

    /** The source depths Music carries bit for bit (as BitPerfect's). */
    private val EXACT_BITS = setOf(16, 24)

    /** Whether the equalizer's values change the sound: a pre-amp or an enabled filter's gain other than 0 dB (Flat doesn't). */
    fun changes(settings: EqSettings): Boolean = settings.preampDb != 0f || settings.filters.any { it.enabled && it.gainDb != 0f }
}

package sh.hnet.music.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import sh.hnet.music.R
import sh.hnet.music.data.Track
import sh.hnet.music.tags.AudioCodec
import java.text.NumberFormat

fun codecName(codec: AudioCodec): String = when (codec) {
    AudioCodec.DSD -> "DSD"
    AudioCodec.FLAC -> "FLAC"
    AudioCodec.ALAC -> "ALAC"
    AudioCodec.AAC -> "AAC"
    AudioCodec.MP3 -> "MP3"
    AudioCodec.MP2 -> "MP2"
    AudioCodec.MP1 -> "MP1"
    AudioCodec.OPUS -> "Opus"
    AudioCodec.VORBIS -> "Vorbis"
    AudioCodec.PROTECTED, AudioCodec.UNKNOWN -> "?"
}

/** "96", "44.1" ("44,1" in Polish): kHz with decimals only when needed. */
@Composable
fun sampleRateText(rate: Int): String {
    val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }
    return format.format(rate / 1000.0)
}

/** "DSD64", "DSD128": the bit rate as a multiple of a CD's sample rate (streams based on 48 kHz alike). */
fun dsdName(sampleRate: Int): String = "DSD" + sampleRate / (if (sampleRate % 44_100 == 0) 44_100 else 48_000)

/** "FLAC 24 bit / 96 kHz", "AAC 256 kb/s", "DSD64 2.8 MHz" (SPEC §9.3). */
@Composable
fun techLabel(track: Track): String {
    val codec = codecName(track.codec)
    val bits = track.bitsPerSample
    return when {
        // 1-bit audio: its rate in MHz says more than "1 bit"
        track.codec == AudioCodec.DSD && track.sampleRate > 0 -> {
            val megahertz = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }.format(track.sampleRate / 1_000_000.0)
            stringResource(R.string.label_tech_dsd, dsdName(track.sampleRate), megahertz)
        }
        bits != null && track.sampleRate > 0 -> stringResource(R.string.label_tech_lossless, codec, bits.toString(), sampleRateText(track.sampleRate))
        track.bitrateKbps != null -> stringResource(R.string.label_tech_lossy, codec, NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(track.bitrateKbps))
        else -> codec
    }
}

/** "mono", "stereo", "6 channels". */
@Composable
fun channelsText(channels: Int): String = when (channels) {
    1 -> stringResource(R.string.label_mono)
    2 -> stringResource(R.string.label_stereo)
    else -> pluralStringResource(R.plurals.label_channels, channels, channels)
}

/** "−6.5", "+1.2", "0.0": one decimal, a real minus sign, the locale's decimal separator. */
@Composable
fun decibels(db: Float): String {
    val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    val sign = when {
        db <= -0.05f -> "\u2212"
        db >= 0.05f -> "+"
        else -> ""
    }
    return sign + format.format(kotlin.math.abs(db))
}

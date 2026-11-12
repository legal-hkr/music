package sh.hnet.music.equalizer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType

/**
 * Presets as Equalizer APO text (SPEC-equalizer §10), the format AutoEq publishes: `Preamp: -6.3 dB`
 * and `Filter 1: ON PK Fc 1492 Hz Gain -7.7 dB Q 2.39` lines. Pure Kotlin, unit-tested. Our own
 * files start with a `# Preset: {name}` comment, so a preset comes back under its name.
 */
object EqualizerText {
    /** The largest file read: a preset is a few hundred bytes. */
    const val MAX_BYTES = 256 * 1024

    /**
     * @param name from the file's `# Preset:` comment, when it has one
     * @param unsupported filters left out because their type (or a value) isn't one we have
     * @param overLimit filters left out because there were more than [Equalizer.MAX_FILTERS]
     */
    class Parsed(val name: String?, val settings: EqSettings, val unsupported: Int, val overLimit: Int)

    /** The preset in [text]; null when it has neither a Preamp nor a Filter line. */
    fun parse(text: String): Parsed? {
        var name: String? = null
        var preamp: Float? = null
        var filterLines = 0
        var unsupported = 0
        val filters = mutableListOf<EqFilter>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().trimStart(BYTE_ORDER_MARK)
            NAME.matchEntire(line)?.let { if (name == null) name = it.groupValues[1].trim().ifEmpty { null } }
            PREAMP.find(line)?.let { match -> number(match.groupValues[1])?.let { preamp = it } }
            val filter = FILTER.find(line) ?: return@forEach
            val type = filter.groupValues[2].uppercase(Locale.ROOT)
            // An empty slot, as the Equalizer APO editor writes it
            if (type == "NONE") return@forEach
            filterLines++
            val values = filter.groupValues[3]
            val kind = TYPES[type]
            val frequency = FREQUENCY.find(values)?.let { number(it.groupValues[1]) }
            val gain = GAIN.find(values)?.let { number(it.groupValues[1]) }
            // Shelves written without Q have the usual slope
            val q = Q.find(values)?.let { number(it.groupValues[1]) } ?: SHELF_Q.takeIf { kind != null && kind != FilterType.PEAK }
            if (kind == null || frequency == null || gain == null || q == null) {
                unsupported++
            } else {
                filters += EqFilter(
                    enabled = filter.groupValues[1].equals("ON", ignoreCase = true),
                    type = kind,
                    frequencyHz = Math.round(frequency).coerceIn(Equalizer.MIN_FREQUENCY, Equalizer.MAX_FREQUENCY),
                    gainDb = Equalizer.roundTo(gain.coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN), 1),
                    q = Equalizer.roundTo(q.coerceIn(Equalizer.MIN_Q, Equalizer.MAX_Q), 2),
                )
            }
        }
        if (preamp == null && filterLines == 0) return null
        val settings = EqSettings(
            preampDb = Equalizer.roundTo((preamp ?: 0f).coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN), 1),
            filters = filters.take(Equalizer.MAX_FILTERS),
        )
        return Parsed(name, settings, unsupported, (filters.size - Equalizer.MAX_FILTERS).coerceAtLeast(0))
    }

    /** [settings] as a file; switched-off filters stay in it as `OFF`. */
    fun write(name: String, settings: EqSettings): String = buildString {
        append("# Preset: ").append(name.replace('\n', ' ').replace('\r', ' ')).append('\n')
        append("Preamp: ").append(String.format(Locale.US, "%.1f", settings.preampDb)).append(" dB\n")
        settings.filters.forEachIndexed { i, filter ->
            val type = when (filter.type) {
                FilterType.PEAK -> "PK"
                FilterType.LOW_SHELF -> "LSC"
                FilterType.HIGH_SHELF -> "HSC"
            }
            append(String.format(Locale.US, "Filter %d: %s %s Fc %d Hz Gain %.1f dB Q %.2f\n", i + 1, if (filter.enabled) "ON" else "OFF", type, filter.frequencyHz, filter.gainDb, filter.q))
        }
    }

    /** `music_eq_{name}_yyyyMMdd_HHmmss.txt`: the name lower-cased, anything but letters and digits as "_". */
    fun fileName(name: String, now: Date = Date()): String {
        val plain = name.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_').ifEmpty { "preset" }
        return "music_eq_${plain}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.txt"
    }

    /** What an imported preset is called: the file's own name for it, else the file name without its extension. */
    fun presetName(parsed: Parsed, fileName: String, fallback: String): String =
        (parsed.name ?: fileName.substringAfterLast('/').substringBeforeLast('.', fileName).trim()).take(MAX_NAME).trim().ifEmpty { fallback }


    /** The text of a file's bytes: UTF-8, or UTF-16 when it starts with that byte order mark (Windows editors). */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }

    private fun number(text: String): Float? = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

    private const val MAX_NAME = 80
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val SHELF_Q = 0.7f
    private const val NUMBER = """([-+]?\d+(?:[.,]\d+)?)"""
    private val NAME = Regex("""#\s*Preset:\s*(.*)""", RegexOption.IGNORE_CASE)
    private val PREAMP = Regex("""^Preamp:\s*$NUMBER\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex("""^Filter(?:\s+\d+)?:\s*(ON|OFF)\s+([A-Za-z]+)\b(.*)$""", RegexOption.IGNORE_CASE)
    private val FREQUENCY = Regex("""\bFc\s+$NUMBER\s*Hz""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""\bGain\s+$NUMBER\s*dB""", RegexOption.IGNORE_CASE)
    private val Q = Regex("""\bQ\s+$NUMBER""", RegexOption.IGNORE_CASE)
    private val TYPES = mapOf(
        "PK" to FilterType.PEAK, "PEQ" to FilterType.PEAK,
        "LSC" to FilterType.LOW_SHELF, "LS" to FilterType.LOW_SHELF,
        "HSC" to FilterType.HIGH_SHELF, "HS" to FilterType.HIGH_SHELF,
    )
}

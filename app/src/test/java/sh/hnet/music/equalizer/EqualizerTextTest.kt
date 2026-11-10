package sh.hnet.music.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.FilterType
import java.util.GregorianCalendar

class EqualizerTextTest {
    @Test
    fun `an AutoEq file comes in with all its filters`() {
        val parsed = EqualizerText.parse(
            """
            Preamp: -6.3 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.3 dB Q 0.70
            Filter 2: ON PK Fc 1492 Hz Gain -7.7 dB Q 2.39
            Filter 3: ON HSC Fc 10000 Hz Gain -3.6 dB Q 0.70
            """.trimIndent(),
        )!!
        assertEquals(-6.3f, parsed.settings.preampDb)
        assertEquals(
            listOf(
                EqFilter(type = FilterType.LOW_SHELF, frequencyHz = 105, gainDb = 6.3f, q = 0.7f),
                EqFilter(type = FilterType.PEAK, frequencyHz = 1492, gainDb = -7.7f, q = 2.39f),
                EqFilter(type = FilterType.HIGH_SHELF, frequencyHz = 10000, gainDb = -3.6f, q = 0.7f),
            ),
            parsed.settings.filters,
        )
        assertEquals(0, parsed.unsupported)
        assertEquals(0, parsed.overLimit)
        assertNull(parsed.name)
    }

    @Test
    fun `what is written reads back the same, name and switched-off filters included`() {
        BuiltInPresets.all.forEach { preset ->
            val settings = preset.settings.copy(filters = preset.settings.filters.mapIndexed { i, f -> f.copy(enabled = i % 2 == 0) })
            val parsed = EqualizerText.parse(EqualizerText.write("Audeze iSine 10", settings))!!
            assertEquals(settings, parsed.settings)
            assertEquals("Audeze iSine 10", parsed.name)
        }
    }

    @Test
    fun `shelves without Q get 0,70 and unknown types are left out and counted`() {
        val parsed = EqualizerText.parse(
            "Preamp: 0 dB\r\nFilter 1: ON LS Fc 80 Hz Gain 3 dB\r\nFilter 2: ON HP Fc 30 Hz\r\nFilter 3: OFF HS Fc 8000,5 Hz Gain -2,5 dB\r\nFilter 4: ON None\r\nFilter 5: ON PK Fc 1000 Hz Gain 2 dB",
        )!!
        assertEquals(2, parsed.settings.filters.size)
        assertEquals(0.7f, parsed.settings.filters[0].q)
        assertFalse(parsed.settings.filters[1].enabled)
        assertEquals(8001, parsed.settings.filters[1].frequencyHz)
        assertEquals(-2.5f, parsed.settings.filters[1].gainDb)
        // The high-pass and the peak without Q; the empty slot isn't a filter
        assertEquals(2, parsed.unsupported)
    }

    @Test
    fun `more than 20 filters keep the first 20 and values stay within the limits`() {
        val text = (1..23).joinToString("\n") { "Filter $it: ON PK Fc ${it * 100} Hz Gain 30 dB Q 50" }
        val parsed = EqualizerText.parse(text)!!
        assertEquals(20, parsed.settings.filters.size)
        assertEquals(3, parsed.overLimit)
        assertEquals(2000, parsed.settings.filters.last().frequencyHz)
        assertEquals(20f, parsed.settings.filters[0].gainDb)
        assertEquals(10f, parsed.settings.filters[0].q)
        assertEquals(0f, parsed.settings.preampDb)
    }

    @Test
    fun `a file without Preamp or Filter lines isn't a preset`() {
        assertNull(EqualizerText.parse("#EXTM3U\nmusic/a.flac\n"))
        assertNull(EqualizerText.parse(""))
        assertEquals(EqSettings(preampDb = -3f), EqualizerText.parse("# comment\nPreamp: -3 dB")!!.settings)
    }

    @Test
    fun `file and preset names`() {
        val date = GregorianCalendar(2026, 10, 10, 14, 30, 5).time
        assertEquals("music_eq_audeze_isine_10_20261110_143005.txt", EqualizerText.fileName("Audeze iSine 10", date))
        assertEquals("music_eq_słuchawki_2_20261110_143005.txt", EqualizerText.fileName("  Słuchawki / (2) ", date))
        assertEquals("music_eq_preset_20261110_143005.txt", EqualizerText.fileName("???", date))
        val plain = EqualizerText.parse("Preamp: 0 dB")!!
        assertEquals("Sennheiser HD 650 ParametricEQ", EqualizerText.presetName(plain, "Sennheiser HD 650 ParametricEQ.txt", "New preset"))
        assertEquals("New preset", EqualizerText.presetName(plain, ".txt", "New preset"))
        assertEquals("Mine", EqualizerText.presetName(EqualizerText.parse("# Preset: Mine\nPreamp: 0 dB")!!, "music_eq_mine_20261110_143005.txt", "New preset"))
        assertEquals("A (3)", EqualizerText.uniqueName("A") { it in setOf("A", "A (2)") })
        assertTrue(EqualizerText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'P'.code.toByte(), 0)) == "P")
    }
}

package sh.hnet.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDevicesTest {
    @Test
    fun `formats are grouped by sample size and channels, rates ascending and once each`() {
        val formats = listOf(
            OutputFormat(96_000, 24, false, 2), OutputFormat(44_100, 24, false, 2), OutputFormat(44_100, 16, false, 2),
            OutputFormat(96_000, 24, false, 2), OutputFormat(48_000, 32, true, 2), OutputFormat(48_000, 32, false, 2), OutputFormat(48_000, 16, false, 1),
        )
        assertEquals(
            listOf(
                OutputFormatGroup(16, false, 1, listOf(48_000)),
                OutputFormatGroup(16, false, 2, listOf(44_100)),
                OutputFormatGroup(24, false, 2, listOf(44_100, 96_000)),
                OutputFormatGroup(32, false, 2, listOf(48_000)),
                OutputFormatGroup(32, true, 2, listOf(48_000)),
            ),
            OutputDevices.grouped(formats),
        )
        assertEquals(emptyList<OutputFormatGroup>(), OutputDevices.grouped(emptyList()))
    }
}

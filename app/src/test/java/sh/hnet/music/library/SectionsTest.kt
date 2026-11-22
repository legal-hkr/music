package sh.hnet.music.library

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val en = Locale.US

    @Test
    fun `letters lose their marks unless the language has them as its own`() {
        assertEquals("E", Sections.letter("Édith Piaf", pl))
        assertEquals("Ł", Sections.letter("Łona", pl))
        assertEquals("Ś", Sections.letter("ścieżka", pl))
        assertEquals("L", Sections.letter("Łona", en))
        assertEquals("A", Sections.letter("Ägypten", pl))
        // Stored decomposed: E followed by a combining acute
        assertEquals("E", Sections.letter("Édith", en))
        assertEquals(Sections.OTHER, Sections.letter("2Pac", en))
        assertEquals(Sections.OTHER, Sections.letter("", en))
    }

    private fun millis(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000

    @Test
    fun `this year's months by their full names, earlier years by the year`() {
        val today = LocalDate.of(2026, 11, 22)
        assertEquals("Listopad", Sections.added(millis(LocalDate.of(2026, 11, 3)), pl, today))
        assertEquals("Styczeń", Sections.added(millis(LocalDate.of(2026, 1, 15)), pl, today))
        assertEquals("November", Sections.added(millis(LocalDate.of(2026, 11, 3)), en, today))
        assertEquals("2025", Sections.added(millis(LocalDate.of(2025, 12, 31)), pl, today))
        assertEquals(Sections.OTHER, Sections.added(0, pl, today))
    }

    @Test
    fun `a header before every group, a letter that comes back stays in the group before it`() {
        val rows = Sections.rows(listOf("a1", "a2", "b1", "a3", "c1"), listOf("A", "A", "B", "A", "C"), headers = true)
        assertEquals(
            listOf("H:A", "a1", "a2", "H:B", "b1", "a3", "H:C", "c1"),
            rows.map { if (it is SectionRow.Header) "H:${it.label}" else (it as SectionRow.Item).item },
        )
        // The item that came back carries its group's label, for the scroller's bubble
        assertEquals("B", rows.filterIsInstance<SectionRow.Item<String>>().first { it.item == "a3" }.label)
        assertEquals(3, rows.filterIsInstance<SectionRow.Item<String>>().first { it.item == "a3" }.index)
    }

    @Test
    fun `other names may open a group at both ends`() {
        val rows = Sections.rows(listOf("1", "a", "?"), listOf("#", "A", "#"), headers = true)
        assertEquals(listOf("#", "A", "#"), rows.filterIsInstance<SectionRow.Header>().map { it.label })
        // Keys stay apart
        assertEquals(listOf(0, 1, 2), rows.filterIsInstance<SectionRow.Header>().map { it.start })
    }

    @Test
    fun `an album artist tag that only says various artists groups as Various artists`() {
        assertTrue(Albums.namesVarious("Various Artists"))
        assertTrue(Albums.namesVarious(" various "))
        assertTrue(Albums.namesVarious("V.A."))
        assertTrue(Albums.namesVarious("Różni wykonawcy"))
        assertFalse(Albums.namesVarious("Various Artists Orchestra"))
        assertFalse(Albums.namesVarious("Vangelis"))
    }

    @Test
    fun `without headers the rows are the items`() {
        val rows = Sections.rows(listOf("x", "y"), listOf("#", "#"), headers = false)
        assertFalse(rows.any { it is SectionRow.Header })
        assertEquals(listOf(0, 1), rows.map { (it as SectionRow.Item).index })
    }
}

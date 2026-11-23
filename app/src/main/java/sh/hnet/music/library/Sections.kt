package sh.hnet.music.library

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Section labels of the Library's lists: the headers between groups and the fast scroller's bubble (SPEC §5.5). */
object Sections {
    /** Digits, symbols and anything without a sort key. */
    const val OTHER = "#"

    /** Albums by "Various artists", last by album artist; the UI shows the app's name for them. */
    const val VARIOUS = "\u0001various"

    /** The standalone month name's format per language, made once. */
    private val monthNames = ConcurrentHashMap<Locale, DateTimeFormatter>()

    /** Letters a language files apart from the base letter (Ł after L, not with it). */
    private val OWN_LETTERS = mapOf("pl" to "ĄĆĘŁŃÓŚŹŻ")

    /**
     * First letter of a sort key, upper case, as Contacts files names: marks dropped unless the
     * language has the letter as its own (Polish Ł, Ś, …), so "Édith" stays under E where the
     * collator puts it; [OTHER] when the key doesn't start with a letter.
     */
    fun letter(key: String, locale: Locale): String {
        // Composed first: a key stored decomposed starts with the base letter and a separate mark
        val first = Normalizer.normalize(key.trimStart().take(4), Normalizer.Form.NFC).firstOrNull() ?: return OTHER
        if (!first.isLetter()) return OTHER
        val upper = first.toString().uppercase(locale)
        if (upper in OWN_LETTERS[locale.language].orEmpty()) return upper
        // Ł, Ø and Đ have no decomposition: to Unicode they are letters of their own, not L, O and D with a mark
        return when (upper) {
            "Ł" -> "L"
            "Ø" -> "O"
            "Đ" -> "D"
            else -> Normalizer.normalize(upper, Normalizer.Form.NFD).filter { it.isLetter() }.take(1).ifEmpty { upper }
        }
    }

    /**
     * For lists sorted by when things were added (owner's decision): the month's full name in this
     * year ("November", "Listopad"), the year before it ("2025"); [OTHER] when the time is unknown.
     */
    fun added(epochMillis: Long, locale: Locale, today: LocalDate = LocalDate.now()): String {
        if (epochMillis <= 0) return OTHER
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        if (date.year != today.year) return date.year.toString()
        // The standalone form: "listopad", not the "listopada" of a date
        val month = monthNames.getOrPut(locale) { DateTimeFormatter.ofPattern("LLLL", locale) }.format(date)
        return month.replaceFirstChar { it.titlecase(locale) }
    }

    /**
     * The rows of a sorted list: with [headers], a header before each group of equal [sections].
     * A letter that comes back after another group (the collator and the letters disagree) stays
     * under the header before it, so every letter has one header, as in Contacts; [OTHER] may come
     * twice (digits first, tracks without an artist or album last). Every row carries its group's
     * label for the fast scroller.
     */
    fun <T> rows(items: List<T>, sections: List<String>, headers: Boolean): List<SectionRow<T>> {
        if (!headers) return items.mapIndexed { index, item -> SectionRow.Item(item, index, sections.getOrElse(index) { OTHER }) }
        val rows = ArrayList<SectionRow<T>>(items.size + 32)
        val seen = HashMap<String, Int>()
        var current: String? = null
        items.forEachIndexed { index, item ->
            val label = sections.getOrElse(index) { OTHER }
            if (label != current && (label == OTHER || label !in seen || current == null)) {
                current = label
                val occurrence = seen[label] ?: 0
                seen[label] = occurrence + 1
                rows += SectionRow.Header(label, index, occurrence)
            }
            rows += SectionRow.Item(item, index, current!!)
        }
        return rows
    }
}

/** A row of a Library list: a section's header or an item ([index] in the sorted items). */
sealed interface SectionRow<out T> {
    val label: String

    /**
     * [start] is the index of the section's first item; [occurrence] counts the label's earlier headers ("#" may
     * open a group at both ends), so label and occurrence make a key that stays when items come or go before it.
     */
    data class Header(override val label: String, val start: Int, val occurrence: Int = 0) : SectionRow<Nothing>

    data class Item<T>(val item: T, val index: Int, override val label: String) : SectionRow<T>
}

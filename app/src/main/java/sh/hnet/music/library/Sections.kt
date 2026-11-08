package sh.hnet.music.library

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fast-scroller section labels (SPEC §5.5). */
object Sections {
    /** Digits, symbols and anything without a sort key. */
    const val OTHER = "#"

    /** First letter of a sort key, upper case; a Polish letter keeps its own section (Ł after L). */
    fun letter(key: String, locale: Locale): String {
        val first = key.trimStart().firstOrNull() ?: return OTHER
        return if (first.isLetter()) first.toString().uppercase(locale) else OTHER
    }

    /** "Oct 2026", for lists sorted by when things were added. */
    fun month(epochMillis: Long, locale: Locale): String {
        if (epochMillis <= 0) return OTHER
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        return DateTimeFormatter.ofPattern("LLL yyyy", locale).format(date)
    }
}

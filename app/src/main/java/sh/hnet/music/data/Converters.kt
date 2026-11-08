package sh.hnet.music.data

import androidx.room3.ColumnTypeConverter

/** Multi-valued tags as one column, joined with the unit separator (never part of a tag value). */
class Converters {
    @ColumnTypeConverter
    fun fromList(values: List<String>): String = values.joinToString(SEPARATOR)

    @ColumnTypeConverter
    fun toList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(SEPARATOR)

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}

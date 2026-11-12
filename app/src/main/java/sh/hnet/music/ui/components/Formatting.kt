package sh.hnet.music.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.core.text.htmlEncode
import java.text.NumberFormat
import sh.hnet.music.R

/** A count in the locale's format ("8,214", "8 214"). */
@Composable
fun formatCount(value: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return NumberFormat.getIntegerInstance(locale).format(value)
}

/** A string whose arguments (names the user gave) are escaped and set in bold by its own `<b>` tags. */
@Composable
fun boldNamesResource(@StringRes id: Int, vararg names: String): AnnotatedString =
    AnnotatedString.fromHtml(stringResource(id, *names.map { it.htmlEncode() }.toTypedArray()))

/** "1 h 14 min", "52 min" (at least 1 min); an album's or a queue's length. */
@Composable
fun durationText(ms: Long): String = durationText(LocalResources.current, ms)

/** The same outside composition (the widgets). */
fun durationText(resources: Resources, ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(1)
    return if (minutes >= 60) resources.getString(R.string.label_duration_hm, minutes / 60, minutes % 60) else resources.getString(R.string.label_duration_m, minutes)
}

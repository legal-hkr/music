package sh.hnet.music.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.core.text.htmlEncode
import java.text.NumberFormat

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

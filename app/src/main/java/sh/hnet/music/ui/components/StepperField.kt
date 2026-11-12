package sh.hnet.music.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.pow
import sh.hnet.music.R

/**
 * A number typed exactly or stepped with − and +, as ComfyChair's numeric stepper field: an outlined
 * text field with the steppers inside it. What is typed applies on Done or on leaving the field,
 * rounded to [decimals]; outside [range] or not a number, the field shows [rangeError] and the last
 * valid value stays. Both "." and "," are taken as the decimal separator.
 *
 * @param stepDown the value one step below (the steps needn't be even: frequency goes by a 24th of an octave)
 */
@Composable
fun StepperField(
    label: String,
    value: Float,
    decimals: Int,
    range: ClosedFloatingPointRange<Float>,
    rangeError: String,
    onChange: (Float) -> Unit,
    stepDown: (Float) -> Float,
    stepUp: (Float) -> Float,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(format(value, decimals, locale)) }
    var invalid by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // The value changed elsewhere (a stepper, the slider, another preset): the field follows
    LaunchedEffect(value, locale) {
        if (parse(text, decimals) != value) {
            text = format(value, decimals, locale)
            invalid = false
        }
    }

    fun apply() {
        val typed = parse(text, decimals)
        if (typed == null || typed !in range) {
            invalid = true
        } else {
            invalid = false
            text = format(typed, decimals, locale)
            if (typed != value) onChange(typed)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; invalid = false },
        label = { Text(label) },
        suffix = unit?.let { { Text(it) } },
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(rangeError) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { apply(); focus.clearFocus() }),
        shape = RoundedCornerShape(28.dp),
        leadingIcon = {
            IconButton(onClick = { onChange(stepDown(value).coerceIn(range)) }, enabled = value > range.start) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.content_description_decrease, label))
            }
        },
        trailingIcon = {
            IconButton(onClick = { onChange(stepUp(value).coerceIn(range)) }, enabled = value < range.endInclusive) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.content_description_increase, label))
            }
        },
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) apply()
            focused = it.isFocused
        },
    )
}

private fun format(value: Float, decimals: Int, locale: Locale): String = String.format(locale, "%.${decimals}f", value)

/** The number typed, rounded to [decimals]; null when it isn't one. A typographic minus and spaces are accepted. */
private fun parse(text: String, decimals: Int): Float? {
    val plain = text.trim().replace(',', '.').replace('−', '-').filterNot { it == ' ' || it == ' ' || it == ' ' }
    val number = plain.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val scale = 10.0.pow(decimals)
    return (Math.round(number * scale) / scale).toFloat()
}

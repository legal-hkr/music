package sh.hnet.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import sh.hnet.music.R
import sh.hnet.music.library.PlaylistRepository

/**
 * A form dialog asking for a playlist's name (SPEC-playlists §7.4, §13.1): the field focused with the
 * keyboard open, the current name all selected, validation shown in the field on confirm.
 *
 * @param validate the error string for a name, or null when it can be used
 */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String = "",
    note: (@Composable () -> Unit)? = null,
    validate: (String) -> Int?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val confirm = {
        val problem = validate(value.text)
        if (problem == null) onConfirm(value.text.trim()) else error = problem
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val length = value.text.length
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        if (it.text.length <= PlaylistRepository.MAX_NAME) value = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = when {
                        error != null -> ({ Text(stringResource(error!!)) })
                        length >= 80 -> ({ Text("$length / ${PlaylistRepository.MAX_NAME}") })
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                note?.invoke()
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = confirm) { Text(confirmLabel) } },
    )
}

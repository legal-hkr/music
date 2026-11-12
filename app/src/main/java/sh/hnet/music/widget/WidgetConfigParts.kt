@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.music.R
import sh.hnet.music.ui.components.SettingsGroupHeader

/** What the widgets' settings pages share (SPEC-widgets §5.4). */

/** The Look group: its header with a connected toggle of [options], one of them [selected]. */
@Composable
fun <T> WidgetLookChoice(options: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    // The header with its control, as a group has its header
    Column {
        SettingsGroupHeader(stringResource(R.string.title_widget_look))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { index, option ->
                ToggleButton(
                    checked = selected == option,
                    onCheckedChange = { if (it) onSelect(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.colors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(stringResource(label(option)), maxLines = 1)
                }
            }
        }
    }
}

/** Cancel and the confirming button ([confirm]: Add widget, or Save for a placed one), at the end of the page. */
@Composable
fun WidgetConfigActions(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.button_cancel)) }
        Button(onClick = onConfirm, enabled = enabled) { Text(confirm) }
    }
}

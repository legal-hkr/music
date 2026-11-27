package sh.hnet.music.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.music.R

/** Cancel and the confirming button ([confirm]: Add widget, or Save for a placed one), at the end of a widget's settings page (SPEC-widgets §5.4). */
@Composable
fun WidgetConfigActions(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        // Outlined and filled, as a dialog's pair (the owner, 27.11.2026, as the family's M3 decision 6)
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.button_cancel)) }
        Button(onClick = onConfirm, enabled = enabled) { Text(confirm) }
    }
}

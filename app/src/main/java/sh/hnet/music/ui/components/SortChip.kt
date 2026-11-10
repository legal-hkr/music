@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.components

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.music.R

/**
 * A filter chip for a list's header row, beside or instead of the sort chip: on, it shows a check
 * and the selected colors (FilterChip's own).
 */
@Composable
fun FilterToggleChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

/** The sort chip of a list's header row (SPEC §4.2): the current order, a single-choice menu. */
@Composable
fun <T> SortChip(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = label(selected)
    val description = stringResource(R.string.content_description_sort, current)
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current, maxLines = 1) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_sort), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            trailingIcon = {
                Icon(
                    painterResource(if (open) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                options.forEachIndexed { index, option ->
                    val isSelected = option == selected
                    DropdownMenuItem(
                        text = { Text(label(option), maxLines = 1) },
                        trailingContent = {
                            if (isSelected) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        },
                        shape = MenuDefaults.itemShape(index, options.size).shape,
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

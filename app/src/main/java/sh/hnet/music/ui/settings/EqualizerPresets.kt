@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package sh.hnet.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.music.R
import sh.hnet.music.equalizer.BuiltInPreset
import sh.hnet.music.equalizer.BuiltInPresets
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.ui.components.MenuItem
import sh.hnet.music.ui.components.NameDialog
import sh.hnet.music.ui.components.SingleChoiceDialog
import sh.hnet.music.ui.components.TooltipIconButton

/** What a name dialog is for (§9.4); kept across rotation. */
enum class PresetNaming { NEW_FROM_CURRENT, NEW_EMPTY, SAVE_AS_NEW }

/**
 * Under the Preset group while the values differ from the preset's, or belong to none (§9.3): Save
 * (the user's own preset only), Save as new, Discard changes.
 */
@Composable
fun PresetActions(state: EqualizerState, onSave: () -> Unit, onSaveAsNew: () -> Unit, onDiscard: () -> Unit) {
    if (!state.modified && state.saved != null) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.modified && state.ownInUse != null) {
            FilledTonalButton(onClick = onSave) { Text(stringResource(R.string.button_save)) }
        }
        FilledTonalButton(onClick = onSaveAsNew) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.button_save_as_new), modifier = Modifier.padding(start = 8.dp))
        }
        if (state.modified) {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.button_discard_changes)) }
        }
    }
}

/**
 * Choosing a preset (§9.2): New preset, then the user's own (most recently changed first, each with
 * Rename and Delete in its menu) and the built-in ones. One tap selects and closes. Import and the
 * Export menu items come with their stage.
 */
@Composable
fun PresetSheet(
    state: EqualizerState,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onSelectBuiltIn: (BuiltInPreset) -> Unit,
    onSelectOwn: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    val colors = ListItemDefaults.colors(containerColor = container)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden), containerColor = container) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.title_presets),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).semantics { heading() },
            )
            ListItem(
                onClick = onNew,
                colors = colors,
                leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            ) {
                Text(stringResource(R.string.label_new_preset))
            }
            Column(Modifier.selectableGroup()) {
                val own = state.presetsByChange
                if (own.isNotEmpty()) {
                    GroupHeader(stringResource(R.string.title_your_presets))
                    own.forEach { preset ->
                        PresetRow(preset.name, preset.settings, selected = state.preset?.own == preset.name, colors, onClick = { onSelectOwn(preset.name) }) {
                            OwnPresetMenu(preset.name, onRename = { onRename(preset.name) }, onDelete = { onDelete(preset.name) })
                        }
                    }
                }
                GroupHeader(stringResource(R.string.title_builtin_presets))
                BuiltInPresets.all.forEach { preset ->
                    PresetRow(preset.name ?: stringResource(R.string.label_flat), preset.settings, selected = state.preset?.builtIn == preset.id, colors, onClick = { onSelectBuiltIn(preset) })
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun PresetRow(
    name: String,
    settings: EqSettings,
    selected: Boolean,
    colors: androidx.compose.material3.ListItemColors,
    onClick: () -> Unit,
    menu: (@Composable () -> Unit)? = null,
) {
    val count = settings.filters.size
    ListItem(
        selected = selected,
        onClick = onClick,
        colors = colors,
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = menu,
        supportingContent = {
            Text(if (count == 0) stringResource(R.string.desc_no_filters) else pluralStringResource(R.plurals.desc_filter_count, count, count))
        },
    ) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OwnPresetMenu(name: String, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.content_description_more_for, name), { open = true })
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                MenuItem(stringResource(R.string.menu_rename), R.drawable.ic_edit, 0, 1) { open = false; onRename() }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                MenuItem(stringResource(R.string.menu_delete), R.drawable.ic_delete, 0, 1, error = true) { open = false; onDelete() }
            }
        }
    }
}

/** New preset's first question (§9.4): what it starts from. */
@Composable
fun StartFromDialog(onDismiss: () -> Unit, onChosen: (PresetNaming) -> Unit) {
    SingleChoiceDialog(
        title = AnnotatedString(stringResource(R.string.title_start_from)),
        options = listOf(PresetNaming.NEW_FROM_CURRENT, PresetNaming.NEW_EMPTY),
        initial = PresetNaming.NEW_FROM_CURRENT,
        label = { stringResource(if (it == PresetNaming.NEW_FROM_CURRENT) R.string.label_current_values else R.string.label_empty_flat) },
        confirmLabel = stringResource(R.string.button_next),
        onConfirm = onChosen,
        onDismiss = onDismiss,
    )
}

/** The name of a new preset, with a line saying what it will hold. */
@Composable
fun NewPresetDialog(naming: PresetNaming, state: EqualizerState, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val count = state.current.filters.size
    val note = if (naming == PresetNaming.NEW_EMPTY) stringResource(R.string.msg_saves_empty) else pluralStringResource(R.plurals.msg_saves_current, count, count)
    val builtInNames = builtInNames()
    NameDialog(
        title = stringResource(if (naming == PresetNaming.SAVE_AS_NEW) R.string.title_save_as_new_preset else R.string.title_new_preset),
        confirmLabel = stringResource(R.string.button_save),
        note = { Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) },
        validate = { presetNameError(state, it, builtInNames) },
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
fun RenamePresetDialog(name: String, state: EqualizerState, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val builtInNames = builtInNames()
    NameDialog(
        title = stringResource(R.string.title_rename_preset),
        confirmLabel = stringResource(R.string.button_rename),
        initial = name,
        validate = { presetNameError(state, it, builtInNames, except = name) },
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** The built-in presets' names as shown: a new name mustn't read like one of them. */
@Composable
private fun builtInNames(): List<String> {
    val flat = stringResource(R.string.label_flat)
    return BuiltInPresets.all.map { it.name ?: flat }
}

/** Names are compared as playlist names are (SPEC-playlists §7.4). */
private fun presetNameError(state: EqualizerState, name: String, builtInNames: List<String>, except: String? = null): Int? = when {
    name.isBlank() -> R.string.error_name_empty
    state.nameTaken(name, builtInNames, PlaylistRepository::nameKey, except) -> R.string.error_preset_name_taken
    else -> null
}

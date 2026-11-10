@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.settings

import sh.hnet.music.playback.EqSettings
import sh.hnet.music.equalizer.PresetRef
import androidx.core.text.htmlEncode
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.material3.SliderState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.equalizer.BuiltInPreset
import sh.hnet.music.equalizer.BuiltInPresets
import sh.hnet.music.equalizer.EqualizerFiles
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.equalizer.EqualizerText
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.ui.library.ErrorDetails
import sh.hnet.music.ui.library.ErrorDetailsDialog
import sh.hnet.music.ui.library.reportFailure
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.Equalizer
import sh.hnet.music.playback.FilterType
import sh.hnet.music.ui.components.DestructiveButton
import sh.hnet.music.ui.components.LocalBottomOverlay
import sh.hnet.music.ui.components.RowIcon
import sh.hnet.music.ui.components.SettingsDescription
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsNote
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.StepperField
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.player.decibels
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Settings › Sound › Equalizer (SPEC-equalizer §3–§9): the response curve pinned under the top bar,
 * then the main switch, the preset, the pre-amp and the filters. Every change is heard at once.
 * A filter is edited in a standard (non-modal) bottom sheet, so the curve stays in sight and live.
 *
 * @param topInsets false over now playing, which has taken the status bar's inset already
 */
@Composable
fun EqualizerScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope, topInsets: Boolean = true) {
    val app = LocalContext.current.applicationContext as MusicApplication
    val store = app.equalizer
    val state by store.state.collectAsStateWithLifecycle()
    val bypassed by app.bitPerfect.active.collectAsStateWithLifecycle()
    val filters = state.current.filters
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val undo = stringResource(R.string.button_undo)

    // The filter in the sheet, by its place in the list; gone with its filter
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val edited = editing?.let(filters::getOrNull)
    if (editing != null && edited == null) editing = null
    var choosingPreset by rememberSaveable { mutableStateOf(false) }
    var startingNew by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf<PresetNaming?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    val deletedText = stringResource(R.string.msg_playlist_deleted)
    val show = stringResource(R.string.button_show)
    val flatName = stringResource(R.string.label_flat)
    val newPresetName = stringResource(R.string.label_new_preset)
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    // The preset being exported while the system picker is open: "b:{id}" built-in, "o:{name}" the user's own
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }

    fun plain(template: Int, vararg names: String) = AnnotatedString.fromHtml(resources.getString(template).format(*names.map { it.htmlEncode() }.toTypedArray())).text

    // Import (§10): read once; the file becomes one of the user's presets, in use at once. Undo takes it back
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        messageScope.launch {
            val fileName = app.transfer.displayName(uri) ?: uri.lastPathSegment.orEmpty()
            when (val read = EqualizerFiles.read(app.contentResolver, uri)) {
                is EqualizerFiles.Read.Unreadable -> reportFailure(snackbars, plain(R.string.error_import_unreadable, fileName), read.details, show) { errorShown = it }
                is EqualizerFiles.Read.NotAPreset -> reportFailure(snackbars, plain(R.string.error_import_not_preset, fileName), read.details, show) { errorShown = it }
                is EqualizerFiles.Read.Done -> {
                    val parsed = read.parsed
                    val before = store.state.value
                    val builtInNames = BuiltInPresets.all.map { it.name ?: flatName }
                    var imported = ""
                    editing = null
                    store.update { now ->
                        imported = EqualizerText.uniqueName(EqualizerText.presetName(parsed, fileName, newPresetName)) { now.nameTaken(it, builtInNames, PlaylistRepository::nameKey) }
                        now.savedAsNew(imported, System.currentTimeMillis(), parsed.settings)
                    }
                    val count = parsed.settings.filters.size
                    var what = resources.getQuantityString(R.plurals.desc_filter_count, count, count)
                    if (parsed.unsupported > 0) what = resources.getString(R.string.msg_import_left_out_type, what, parsed.unsupported)
                    if (parsed.overLimit > 0) what = resources.getString(R.string.msg_import_left_out_limit, what, parsed.overLimit)
                    snackbars.currentSnackbarData?.dismiss()
                    val text = AnnotatedString.fromHtml(resources.getString(R.string.msg_preset_imported).format(imported.htmlEncode(), what.htmlEncode())).text
                    if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        store.update { now ->
                            // What was heard before comes back, unless another preset was chosen meanwhile
                            val inUse = now.preset?.own == imported
                            now.copy(
                                presets = now.presets.filterNot { it.name == imported },
                                preset = if (inUse) before.preset else now.preset,
                                current = if (inUse) before.current else now.current,
                            )
                        }
                    }
                }
            }
        }
    }
    // Export (§10): the preset's saved values, as text
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val key = exporting
        exporting = null
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        val now = store.state.value
        val (name, settings) = when {
            key.startsWith("b:") -> BuiltInPresets.byId(key.drop(2))?.let { (it.name ?: flatName) to it.settings }
            else -> now.presets.firstOrNull { it.name == key.drop(2) }?.let { it.name to it.settings }
        } ?: return@rememberLauncherForActivityResult
        messageScope.launch {
            val failed = EqualizerFiles.write(app.contentResolver, uri, EqualizerText.write(name, settings))
            val fileName = app.transfer.displayName(uri) ?: uri.lastPathSegment.orEmpty()
            if (failed == null) {
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(plain(R.string.msg_exported, fileName), duration = SnackbarDuration.Short)
            } else {
                reportFailure(snackbars, plain(R.string.error_export_write, fileName), failed, show) { errorShown = it }
            }
        }
    }
    fun export(key: String, name: String) {
        choosingPreset = false
        exporting = key
        exportPicker.launch(EqualizerText.fileName(name))
    }

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(editing != null) {
        if (editing != null) sheetState.expand() else sheetState.hide()
    }
    // Swiped down: nothing is being edited any more
    LaunchedEffect(sheetState) {
        var shown = false
        snapshotFlow { sheetState.currentValue }.collect { value ->
            if (shown && value != SheetValue.Expanded) editing = null
            shown = value == SheetValue.Expanded
        }
    }
    BackHandler(enabled = editing != null) { editing = null }

    /** A snackbar with Undo, which puts [before] back. */
    fun offerUndo(message: String, before: EqualizerState) = messageScope.launch {
        snackbars.currentSnackbarData?.dismiss()
        if (snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            // Only the values: whatever else changed meanwhile (the switch) stays
            store.update { it.copy(preset = before.preset, current = before.current) }
        }
    }

    BoxWithConstraints {
        // The sheet's top sits just under the curve: the bar, the curve and the sheet's own handle above its content
        val statusBar = if (topInsets) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 0.dp
        val sheetHeight = maxHeight - statusBar - TOP_BAR - CURVE_AREA - SHEET_HANDLE
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 0.dp,
            sheetContent = {
                val index = editing
                if (index != null && edited != null) {
                    FilterSheet(
                        number = index + 1,
                        filter = edited,
                        height = sheetHeight,
                        onChange = { changed -> store.update { it.withFilter(index, changed) } },
                        onRemove = {
                            val before = state
                            editing = null
                            store.update { it.withoutFilter(index) }
                            offerUndo(
                                resources.getString(R.string.msg_removed_filter, index + 1, resources.getString(edited.type.label()), frequencyText(edited.frequencyHz, locale)),
                                before,
                            )
                        },
                    )
                }
            },
        ) {
            SettingsPage(
                title = stringResource(R.string.title_equalizer),
                onBack = onBack,
                topInsets = topInsets,
                pinned = {
                    ResponseCurve(
                        settings = state.current,
                        applied = state.enabled && !bypassed,
                        edited = edited,
                        tag = when {
                            !state.enabled -> stringResource(R.string.label_curve_off)
                            bypassed -> stringResource(R.string.label_curve_not_applied)
                            else -> null
                        },
                        // A tap on the curve closes the filter sheet
                        // Space above too: scrolled, the top bar takes the curve's own color
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = editing != null) { editing = null },
                    )
                },
            ) {
                // Everything stays editable and saved; it just isn't heard for now (§11.2)
                if (bypassed) SettingsDescription(stringResource(R.string.msg_eq_bitperfect))
                SettingsGroup {
                    row { shapes ->
                        SegmentedListItem(
                            checked = state.enabled,
                            onCheckedChange = { on -> store.update { it.copy(enabled = on) } },
                            shapes = shapes,
                            colors = settingsRowColors(),
                            trailingContent = { Switch(checked = state.enabled, onCheckedChange = null) },
                        ) {
                            Text(stringResource(R.string.label_use_equalizer))
                        }
                    }
                }
                SettingsGroup(stringResource(R.string.title_preset_group)) {
                    row { shapes ->
                        SegmentedListItem(
                            onClick = { choosingPreset = true },
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { RowIcon(R.drawable.ic_tune) },
                            trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
                            supportingContent = {
                                if (state.modified) Text(stringResource(R.string.desc_modified), color = MaterialTheme.colorScheme.primary)
                                else presetKind(state)?.let { Text(it) }
                            },
                        ) {
                            Text(presetName(state))
                        }
                    }
                    // The place device profiles take later (§12): for now it only says so
                    row { shapes ->
                        SegmentedListItem(onClick = {}, shapes = shapes, colors = settingsRowColors(), leadingContent = { RowIcon(R.drawable.ic_devices) }) {
                            Text(stringResource(R.string.label_all_outputs))
                        }
                    }
                }
                PresetActions(
                    state,
                    onSave = { store.update { it.saved(System.currentTimeMillis()) } },
                    onSaveAsNew = { naming = PresetNaming.SAVE_AS_NEW },
                    onDiscard = {
                        val before = state
                        store.update { it.discarded() }
                        offerUndo(resources.getString(R.string.msg_changes_discarded), before)
                    },
                )
                Preamp(state.current.preampDb) { db -> store.update { it.withPreamp(db) } }
                val peak = Equalizer.peakDb(state.current)
                if (peak > 0f) {
                    Column {
                        SettingsNote(stringResource(R.string.msg_clipping, decibels(peak)))
                        val safe = Equalizer.safePreamp(state.current)
                        TextButton(onClick = { store.update { it.withPreamp(safe) } }, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(stringResource(R.string.button_set_preamp, decibels(safe)))
                        }
                    }
                }
                SettingsGroup(stringResource(R.string.title_filters, filters.size)) {
                    filters.forEachIndexed { index, filter ->
                        row { shapes ->
                            SegmentedListItem(
                                onClick = { editing = index },
                                shapes = shapes,
                                colors = settingsRowColors(),
                                leadingContent = { FilterNumber(index + 1) },
                                trailingContent = {
                                    Switch(checked = filter.enabled, onCheckedChange = { on -> store.update { it.withFilter(index, filter.copy(enabled = on)) } })
                                },
                                supportingContent = { Text(stringResource(R.string.desc_filter_row, decibels(filter.gainDb), qText(filter.q, locale))) },
                            ) {
                                Text(
                                    stringResource(R.string.label_filter_row, stringResource(filter.type.label()), frequencyText(filter.frequencyHz, locale)),
                                    // A switched-off filter reads muted
                                    color = if (filter.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            // Opens the new filter's sheet at once: it changes nothing until it is set
                            store.update { it.withNewFilter() }
                            editing = filters.size
                        },
                        enabled = filters.size < Equalizer.MAX_FILTERS,
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.button_add_filter), modifier = Modifier.padding(start = 8.dp))
                    }
                    if (filters.size > 1) {
                        TextButton(onClick = {
                            val before = state
                            store.update { it.sortedByFrequency() }
                            offerUndo(resources.getString(R.string.msg_sorted_by_frequency), before)
                        }) {
                            Icon(painterResource(R.drawable.ic_sort), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.button_sort_by_frequency), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (filters.size >= Equalizer.MAX_FILTERS) SettingsNote(stringResource(R.string.desc_filter_limit))
            }
        }
    }

    if (choosingPreset) {
        /** Another preset in use; unsaved changes go with the one they were made to, unless undone (§9.3). */
        fun select(change: (EqualizerState) -> EqualizerState) {
            choosingPreset = false
            val before = state
            editing = null
            store.update(change)
            if (before.modified) offerUndo(resources.getString(R.string.msg_changes_discarded), before)
        }
        PresetSheet(
            state = state,
            onDismiss = { choosingPreset = false },
            onNew = { choosingPreset = false; startingNew = true },
            onImport = { choosingPreset = false; importPicker.launch(arrayOf("text/plain", "application/octet-stream")) },
            onExportBuiltIn = { export("b:" + it.id, it.name ?: flatName) },
            onExportOwn = { export("o:$it", it) },
            onSelectBuiltIn = { preset -> select { it.withBuiltIn(preset) } },
            onSelectOwn = { name -> select { it.withOwn(name) } },
            onRename = { choosingPreset = false; renaming = it },
            // At once, with Undo (§9.5); the sheet closes so the snackbar can be seen
            onDelete = { name ->
                choosingPreset = false
                val deleted = state.presets.firstOrNull { it.name == name } ?: return@PresetSheet
                val wasInUse = state.preset?.own == name
                store.update { it.withoutPreset(name) }
                messageScope.launch {
                    snackbars.currentSnackbarData?.dismiss()
                    val text = AnnotatedString.fromHtml(deletedText.format(name.htmlEncode())).text
                    if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        store.update { now ->
                            now.copy(
                                presets = now.presets.filterNot { it.name == name } + deleted,
                                // In use again, unless another preset was chosen meanwhile
                                preset = if (wasInUse && now.preset == null) PresetRef(own = name) else now.preset,
                            )
                        }
                    }
                }
            },
        )
    }
    if (startingNew) {
        StartFromDialog(onDismiss = { startingNew = false }) { startingNew = false; naming = it }
    }
    naming?.let { kind ->
        NewPresetDialog(kind, state, onDismiss = { naming = null }) { name ->
            naming = null
            val before = state
            editing = null
            store.update { if (kind == PresetNaming.NEW_EMPTY) it.savedAsNew(name, System.currentTimeMillis(), EqSettings()) else it.savedAsNew(name, System.currentTimeMillis()) }
            // An empty preset replaces what was heard: unsaved changes can come back
            if (kind == PresetNaming.NEW_EMPTY && before.modified) offerUndo(resources.getString(R.string.msg_changes_discarded), before)
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
    renaming?.let { name ->
        if (state.presets.none { it.name == name }) {
            renaming = null
        } else {
            RenamePresetDialog(name, state, onDismiss = { renaming = null }) { to ->
                renaming = null
                if (to != name) store.update { it.renamed(name, to, System.currentTimeMillis()) }
            }
        }
    }
}

/** The pre-amp (§5.2): the exact value in a stepper field, and a slider to move it by ear. */
@Composable
private fun Preamp(db: Float, onChange: (Float) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            GainControl(stringResource(R.string.label_eq_preamp), db, onChange)
        }
    }
}

/** A gain in dB, −20 to +20 in steps of 0.1: field and slider. */
@Composable
private fun GainControl(label: String, db: Float, onChange: (Float) -> Unit) {
    StepperField(
        label = label,
        value = db,
        decimals = 1,
        range = -Equalizer.MAX_GAIN..Equalizer.MAX_GAIN,
        rangeError = stringResource(R.string.error_gain_range),
        onChange = onChange,
        stepDown = { Equalizer.roundTo(it - 0.1f, 1) },
        stepUp = { Equalizer.roundTo(it + 0.1f, 1) },
        unit = stringResource(R.string.unit_db),
        modifier = Modifier.fillMaxWidth(),
    )
    ValueSlider(db, -Equalizer.MAX_GAIN..Equalizer.MAX_GAIN) { onChange(Equalizer.roundTo(it, 1)) }
}

/**
 * A slider that follows [position] (a value typed or stepped elsewhere) except while the finger is
 * on it, when it leads and reports every move, so changes are heard as it is dragged.
 */
@Composable
private fun ValueSlider(position: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, onMove: (Float) -> Unit) {
    val state = remember { SliderState(value = position, trackRange = range) }
    var dragging by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(position)
    LaunchedEffect(position) { if (!dragging) state.value = position }
    Slider(
        state = state,
        onValueChange = {
            dragging = true
            state.value = it
            onMove(it)
        },
        // Let go: the thumb settles on the value as it was rounded
        onValueChangeFinished = {
            dragging = false
            state.value = current
        },
    )
}

/** A filter's number in the tonal box rows keep their icons in. */
@Composable
private fun FilterNumber(number: Int) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * The filter sheet's content (§8): on/off, the type, then frequency, gain and Q, each typed exactly
 * or moved by its slider (frequency and Q on logarithmic sliders), and Remove filter.
 */
@Composable
private fun FilterSheet(number: Int, filter: EqFilter, height: Dp, onChange: (EqFilter) -> Unit, onRemove: () -> Unit) {
    val typeLabel = stringResource(R.string.content_description_filter_type)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // Clear of the mini player, which floats over the page's bottom
            .padding(bottom = 16.dp + LocalBottomOverlay.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.title_filter, number), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            Switch(checked = filter.enabled, onCheckedChange = { onChange(filter.copy(enabled = it)) })
        }
        Row(
            Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = typeLabel },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            FilterType.entries.forEachIndexed { index, type ->
                ToggleButton(
                    checked = filter.type == type,
                    onCheckedChange = { if (it) onChange(filter.copy(type = type)) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        FilterType.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.colors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(stringResource(type.label()), maxLines = 1)
                }
            }
        }
        Column {
            StepperField(
                label = stringResource(R.string.label_frequency),
                value = filter.frequencyHz.toFloat(),
                decimals = 0,
                range = Equalizer.MIN_FREQUENCY.toFloat()..Equalizer.MAX_FREQUENCY.toFloat(),
                rangeError = stringResource(R.string.error_frequency_range),
                onChange = { onChange(filter.copy(frequencyHz = Math.round(it))) },
                stepDown = { Equalizer.stepFrequency(Math.round(it), up = false).toFloat() },
                stepUp = { Equalizer.stepFrequency(Math.round(it), up = true).toFloat() },
                unit = stringResource(R.string.unit_hz),
                modifier = Modifier.fillMaxWidth(),
            )
            // Logarithmic: each decade the same length
            ValueSlider(toLog(filter.frequencyHz.toFloat(), Equalizer.MIN_FREQUENCY.toFloat(), Equalizer.MAX_FREQUENCY.toFloat())) {
                onChange(filter.copy(frequencyHz = Math.round(fromLog(it, Equalizer.MIN_FREQUENCY.toFloat(), Equalizer.MAX_FREQUENCY.toFloat()))))
            }
        }
        Column { GainControl(stringResource(R.string.label_gain), filter.gainDb) { onChange(filter.copy(gainDb = it)) } }
        Column {
            StepperField(
                label = stringResource(R.string.label_q),
                value = filter.q,
                decimals = 2,
                range = Equalizer.MIN_Q..Equalizer.MAX_Q,
                rangeError = stringResource(R.string.error_q_range),
                onChange = { onChange(filter.copy(q = it)) },
                stepDown = { Equalizer.roundTo(it - 0.01f, 2) },
                stepUp = { Equalizer.roundTo(it + 0.01f, 2) },
                modifier = Modifier.fillMaxWidth(),
            )
            ValueSlider(toLog(filter.q, Equalizer.MIN_Q, Equalizer.MAX_Q)) {
                onChange(filter.copy(q = Equalizer.roundTo(fromLog(it, Equalizer.MIN_Q, Equalizer.MAX_Q), 2)))
            }
        }
        DestructiveButton(stringResource(R.string.button_remove_filter), R.drawable.ic_delete, onRemove)
    }
}

fun FilterType.label(): Int = when (this) {
    FilterType.PEAK -> R.string.label_filter_type_peak
    FilterType.LOW_SHELF -> R.string.label_filter_type_low_shelf
    FilterType.HIGH_SHELF -> R.string.label_filter_type_high_shelf
}

/** The preset in use by name: "Flat" from the resources, "Unsaved settings" when its preset is gone. */
@Composable
fun presetName(state: EqualizerState): String {
    val ref = state.preset
    val builtIn = BuiltInPresets.byId(ref?.builtIn)
    return when {
        builtIn != null -> builtIn.name ?: stringResource(R.string.label_flat)
        ref?.own != null && state.saved != null -> ref.own
        else -> stringResource(R.string.label_eq_unsaved)
    }
}

@Composable
private fun presetKind(state: EqualizerState): String? = when {
    state.saved == null -> null
    state.preset?.builtIn != null -> stringResource(R.string.desc_builtin)
    else -> stringResource(R.string.desc_own_preset)
}

/** The Settings row's line (§2.1): "Off", the preset, or the preset "(modified)". */
@Composable
fun equalizerSummary(state: EqualizerState, bypassed: Boolean = false): String = when {
    !state.enabled -> stringResource(R.string.label_eq_off)
    // What plays goes out bit-perfect: set, kept, not heard (§11.2)
    bypassed -> stringResource(R.string.label_eq_bypassed, presetName(state))
    state.modified -> stringResource(R.string.label_eq_modified, presetName(state))
    else -> presetName(state)
}

/** "1492", "10,000" ("10 000" in Polish). */
private fun frequencyText(hz: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(hz)

/** "2.39" ("2,39" in Polish). */
private fun qText(q: Float, locale: Locale): String = String.format(locale, "%.2f", q)

/** Where [value] stands between [min] and [max] on a logarithmic scale, 0 to 1. */
private fun toLog(value: Float, min: Float, max: Float): Float = (ln(value / min) / ln(max / min)).coerceIn(0f, 1f)

private fun fromLog(position: Float, min: Float, max: Float): Float = min * (max / min).pow(position)

/** The small top bar, and the curve with the space above and under it. */
private val TOP_BAR = 64.dp
private val CURVE_AREA = 156.dp + 16.dp

/** The sheet's drag handle with its padding, above the sheet's content. */
private val SHEET_HANDLE = 48.dp

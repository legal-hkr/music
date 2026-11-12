@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.PlaylistSummary
import sh.hnet.music.ui.components.NameDialog
import sh.hnet.music.ui.components.boldNamesResource
import sh.hnet.music.ui.components.formatCount

/**
 * What Add to playlist adds (SPEC-playlists §7.1): one track, an album or an artist's tracks, with
 * the name the sheet shows for it.
 */
sealed class AddRequest(val tracks: List<LibraryTrack>) {
    class Track(val track: LibraryTrack) : AddRequest(listOf(track))
    class Group(val name: String, tracks: List<LibraryTrack>) : AddRequest(tracks)
}

/** Opens the Add to playlist sheet from anywhere (track menus, now playing, album and artist pages). */
val LocalAddToPlaylist = staticCompositionLocalOf<(AddRequest) -> Unit> { {} }

/** A message with names in bold (escaped), as plain text for a snackbar. */
private fun message(template: String, vararg args: Any): String =
    AnnotatedString.fromHtml(template.format(*args.map { if (it is String) it.htmlEncode() else it }.toTypedArray())).text

/**
 * The sheet and dialogs of Add to playlist and Save as playlist, shown over the whole app. Their
 * snackbars run in [scope], which outlives the sheet.
 */
@Composable
fun PlaylistActionsHost(
    viewModel: BrowseViewModel,
    snackbars: SnackbarHostState,
    scope: CoroutineScope,
    addRequest: AddRequest?,
    onAddDone: () -> Unit,
    queueToSave: List<LibraryTrack>?,
    onSaveDone: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val resources = LocalResources.current
    val undo = stringResource(R.string.button_undo)
    var newFor by remember { mutableStateOf<AddRequest?>(null) }

    fun show(text: String, action: String?, onAction: () -> Unit) = scope.launch {
        snackbars.currentSnackbarData?.dismiss()
        if (snackbars.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onAction()
    }

    if (addRequest != null && newFor == null) {
        AddToPlaylistSheet(
            viewModel,
            addRequest,
            onDismiss = onAddDone,
            onNew = { newFor = addRequest },
        ) { summary ->
            onAddDone()
            scope.launch {
                val result = viewModel.addToPlaylist(summary.playlist.id, addRequest.tracks)
                val name = summary.playlist.name
                val n = addRequest.tracks.size
                val text = when {
                    n == 1 && result.alreadyThere > 0 -> message(resources.getString(R.string.msg_added_already_there_one), name)
                    n == 1 -> message(resources.getString(R.string.msg_added_to_playlist), name)
                    result.alreadyThere > 0 -> message(resources.getQuantityString(R.plurals.msg_added_already_there_n, result.alreadyThere), n, name, result.alreadyThere)
                    else -> message(resources.getQuantityString(R.plurals.msg_added_n_to_playlist, n), n, name)
                }
                show(text, undo) { viewModel.undoAdd(summary.playlist.id, result.entryIds) }
            }
        }
    }
    newFor?.let { request ->
        NameDialog(
            title = stringResource(R.string.title_new_playlist),
            confirmLabel = stringResource(R.string.button_create),
            note = {
                val text = when (request) {
                    is AddRequest.Track -> boldNamesResource(R.string.msg_track_added_to_new, request.track.displayTitle)
                    is AddRequest.Group -> AnnotatedString(pluralStringResource(R.plurals.msg_tracks_added_to_new, request.tracks.size, request.tracks.size))
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            },
            validate = { viewModel.playlistNameError(it) },
            onDismiss = { newFor = null; onAddDone() },
        ) { name ->
            newFor = null
            onAddDone()
            scope.launch {
                val id = viewModel.createPlaylistWith(name, request.tracks)
                val n = request.tracks.size
                val text = message(resources.getQuantityString(R.plurals.msg_playlist_created_with, n), name, n)
                show(text, undo) { scope.launch { viewModel.deletePlaylist(id) } }
            }
        }
    }
    if (queueToSave != null) {
        SaveQueueDialog(viewModel, queueToSave, onDismiss = onSaveDone) { target, name ->
            onSaveDone()
            scope.launch {
                if (target == null) {
                    val id = viewModel.createPlaylistWith(name, queueToSave)
                    show(message(resources.getString(R.string.msg_queue_saved), name), resources.getString(R.string.button_view)) { onOpenPlaylist(id) }
                } else {
                    val old = viewModel.replacePlaylist(target.playlist.id, queueToSave)
                    show(message(resources.getString(R.string.msg_playlist_replaced), target.playlist.name), undo) {
                        viewModel.restoreReplaced(target.playlist.id, old)
                    }
                }
            }
        }
    }
}

/** The sheet (§7.2): what is added, New playlist first, then playlists; one tap adds and closes. */
@Composable
private fun AddToPlaylistSheet(
    viewModel: BrowseViewModel,
    request: AddRequest,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onPick: (PlaylistSummary) -> Unit,
) {
    val playlists by viewModel.playlistsByChange.collectAsStateWithLifecycle()
    val single = (request as? AddRequest.Track)?.track
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Text(stringResource(R.string.title_add_to_playlist), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val what = when (request) {
                is AddRequest.Track -> listOfNotNull(request.track.displayTitle, request.track.artists.joinToString(", ").ifEmpty { null }).joinToString(" – ")
                is AddRequest.Group -> pluralStringResource(R.plurals.label_adding_tracks, request.tracks.size, request.name, request.tracks.size)
            }
            Text(what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "new") {
                ListItem(
                    onClick = onNew,
                    colors = colors,
                    leadingContent = {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                ) { Text(stringResource(R.string.button_new_playlist)) }
            }
            items(playlists, key = { it.playlist.id }) { summary ->
                val hasIt = single != null && summary.tracks.any { it.documentUri == single.documentUri }
                ListItem(
                    onClick = { onPick(summary) },
                    colors = colors,
                    leadingContent = { PlaylistPicture(summary, 40.dp) },
                    supportingContent = {
                        if (hasIt) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.label_has_this_track))
                            }
                        } else {
                            Text(pluralStringResource(R.plurals.label_track_count, summary.tracks.size, formatCount(summary.tracks.size)))
                        }
                    },
                ) { Text(summary.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * Save as playlist (§8): the track playing and what follows, to a new playlist (a name) or over an
 * existing one (Replace, in the error color: it removes entries).
 */
@Composable
private fun SaveQueueDialog(
    viewModel: BrowseViewModel,
    tracks: List<LibraryTrack>,
    onDismiss: () -> Unit,
    onSave: (target: PlaylistSummary?, name: String) -> Unit,
) {
    val playlists by viewModel.playlistsByChange.collectAsStateWithLifecycle()
    var targetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val target = playlists.firstOrNull { it.playlist.id == targetId }
    val following = tracks.size - 1
    // The name field takes the keyboard at once, as New playlist does (P5b)
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(target == null) { if (target == null) nameFocus.requestFocus() }
    val confirm = {
        if (target != null) {
            onSave(target, target.playlist.name)
        } else {
            val problem = viewModel.playlistNameError(name)
            if (problem == null) onSave(null, name.trim()) else error = problem
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_save_as_playlist)) },
        text = {
            Column {
                Text(
                    if (following > 0) pluralStringResource(R.plurals.msg_save_queue_what, following, following) else stringResource(R.string.msg_save_queue_current),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(16.dp))
                val newLabel = stringResource(R.string.button_new_playlist)
                ChoiceField(
                    label = stringResource(R.string.label_save_to),
                    options = listOf<Pair<Long?, String>>(null to newLabel) + playlists.map { it.playlist.id to it.playlist.name },
                    selected = targetId,
                    onSelect = { targetId = it; error = null },
                )
                if (target == null) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= PlaylistRepository.MAX_NAME) name = it; error = null },
                        label = { Text(stringResource(R.string.label_name)) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { problem -> { Text(stringResource(problem)) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    )
                } else {
                    Spacer(Modifier.size(16.dp))
                    val replaced = target.tracks.size + target.missing
                    Text(
                        boldNamesResource(R.string.msg_replace_playlist, target.playlist.name, replaced.toString(), tracks.size.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = {
            if (target == null) {
                TextButton(onClick = confirm) { Text(stringResource(R.string.button_save)) }
            } else {
                TextButton(onClick = confirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.button_replace))
                }
            }
        },
    )
}

/** A choice inside a form: an outlined field opening the app's menu, as wide as the field. */
@Composable
private fun <T> ChoiceField(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    var fieldWidth by remember { mutableStateOf<Dp?>(null) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.onSizeChanged { fieldWidth = with(density) { it.width.toDp() } }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        val sized = fieldWidth?.let { Modifier.width(it) } ?: Modifier
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                options.forEachIndexed { index, (value, text) ->
                    val isSelected = value == selected
                    DropdownMenuItem(
                        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = MenuDefaults.itemShape(index, options.size).shape,
                        leadingIcon = {
                            if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null) else Spacer(Modifier.size(24.dp))
                        },
                        onClick = { expanded = false; onSelect(value) },
                        modifier = sized.semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                    )
                }
            }
        }
    }
}


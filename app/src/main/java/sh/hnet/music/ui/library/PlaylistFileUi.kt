@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.library

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import sh.hnet.music.ui.components.SingleChoiceDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.music.R
import sh.hnet.music.library.PlaylistFiles
import sh.hnet.music.library.PlaylistFormat
import sh.hnet.music.library.PlaylistTransfer

/** The picker's types for Import (§9.1): the playlist types plus the generic ones files often arrive with. */
val IMPORT_MIME_TYPES = arrayOf(
    "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl",
    "audio/x-scpls", "application/xspf+xml", "application/octet-stream", "text/plain",
)

/** The system "create document" picker with the type and suggested name given at launch. */
private class CreateDocumentOfType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(input.first)
        .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

/** An error's sentence and its details, for the Show dialog. */
class ErrorDetails(val message: String, val details: String)

/** The sentence of a failed import or export, names in bold (escaped), as plain text. */
fun failureMessage(resources: android.content.res.Resources, failed: PlaylistTransfer.Result.Failed): String {
    val template = resources.getString(
        when (failed.failure) {
            PlaylistTransfer.Failure.NOT_A_PLAYLIST -> R.string.error_import_not_playlist
            PlaylistTransfer.Failure.UNREADABLE -> R.string.error_import_unreadable
            PlaylistTransfer.Failure.EMPTY -> R.string.error_import_empty
            PlaylistTransfer.Failure.UNWRITABLE -> R.string.error_export_write
        },
    )
    return AnnotatedString.fromHtml(template.format(failed.fileName.htmlEncode())).text
}

/** Shows an error snackbar with Show, which opens its details. */
fun CoroutineScope.reportFailure(snackbars: SnackbarHostState, message: String, details: String, show: String, onShow: (ErrorDetails) -> Unit) = launch {
    snackbars.currentSnackbarData?.dismiss()
    if (snackbars.showSnackbar(message, actionLabel = show, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
        onShow(ErrorDetails(message, details))
    }
}

/** The error-details dialog: the sentence, the details (selectable), Copy and Close. */
@Composable
fun ErrorDetailsDialog(error: ErrorDetails, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val copied = stringResource(R.string.msg_copied)
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(error.message) },
        text = {
            SelectionContainer(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                Text(error.details, style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, error.message + "\n" + error.details)))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.button_copy)) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_close)) } },
    )
}

/** Export for one screen: the format dialog, the picker, the write and its message (§10). */
class PlaylistExport internal constructor(val start: (id: Long, name: String) -> Unit)

@Composable
fun rememberPlaylistExport(viewModel: BrowseViewModel, snackbars: SnackbarHostState, scope: CoroutineScope): PlaylistExport {
    val resources = LocalResources.current
    val saved by viewModel.exportFormat.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }
    var pending by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }
    var error by remember { mutableStateOf<ErrorDetails?>(null) }
    val show = stringResource(R.string.button_show)
    val picker = rememberLauncherForActivityResult(CreateDocumentOfType()) { uri ->
        val (id, format) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = viewModel.exportPlaylist(id, PlaylistFormat.valueOf(format), uri)) {
                is PlaylistTransfer.Result.Exported -> {
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(AnnotatedString.fromHtml(resources.getString(R.string.msg_exported).format(result.fileName.htmlEncode())).text, duration = SnackbarDuration.Short)
                }
                is PlaylistTransfer.Result.Failed -> scope.reportFailure(snackbars, failureMessage(resources, result), result.details, show) { error = it }
                else -> Unit
            }
        }
    }
    asking?.let { (id, name) ->
        ExportDialog(name, saved, onDismiss = { asking = null }) { format ->
            asking = null
            pending = id to format.name
            picker.launch(format.mimeType to PlaylistFiles.fileName(name, format))
        }
    }
    error?.let { ErrorDetailsDialog(it) { error = null } }
    return remember { PlaylistExport { id, name -> asking = id to name } }
}

/** The format choice (§10): a single-choice list of the format names (owner's decision: no explanations). */
@Composable
private fun ExportDialog(name: String, initial: PlaylistFormat, onDismiss: () -> Unit, onExport: (PlaylistFormat) -> Unit) =
    SingleChoiceDialog(
        title = boldTitle(name),
        options = PlaylistFormat.entries,
        initial = initial,
        label = { it.name },
        confirmLabel = stringResource(R.string.button_export),
        onConfirm = onExport,
        onDismiss = onDismiss,
    )

@Composable
private fun boldTitle(name: String) = sh.hnet.music.ui.components.boldNamesResource(R.string.title_export, name)

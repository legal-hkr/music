package sh.hnet.music.ui.settings

import android.content.ClipData
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import sh.hnet.music.R
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsInfoRow
import sh.hnet.music.ui.components.SettingsNote
import sh.hnet.music.ui.components.SettingsPage

/** Settings › About: the version (long press copies it) and what the app does and doesn't do. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val (name, code) = remember(context) { appVersion(context) }
    val version = "$name ($code)"
    val copied = stringResource(R.string.msg_copied)
    SettingsPage(title = stringResource(R.string.title_settings_about), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsInfoRow(shapes, stringResource(R.string.label_version), version, R.drawable.ic_info) {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, version)))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        SettingsNote(stringResource(R.string.msg_about_privacy))
    }
}

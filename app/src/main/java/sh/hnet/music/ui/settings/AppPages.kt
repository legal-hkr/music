@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import sh.hnet.music.ui.components.DestructiveButton
import sh.hnet.music.ui.components.ConfirmDestructiveDialog
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import sh.hnet.music.ui.components.SettingsGroupHeader
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.ui.components.SettingsDescription
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.SettingsRadioRow

/** A language the app is translated into; an empty tag follows the system. */
class AppLanguage(val tag: String, @param:StringRes val name: Int)

val appLanguages = listOf(
    AppLanguage("", R.string.language_system_default),
    AppLanguage("en", R.string.language_name_en),
    AppLanguage("pl", R.string.language_name_pl),
)

/** The app's language tag; empty when it follows the system. */
fun currentLanguageTag(context: Context): String {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) "" else locales.get(0)?.language.orEmpty()
}

private fun setLanguage(context: Context, tag: String) {
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

/** Language (as ComfyChair): one choice among the translations; the system recreates the activity in it. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val current = remember { currentLanguageTag(context) }
    SettingsPage(title = stringResource(R.string.title_settings_language), onBack = onBack) {
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                appLanguages.forEach { language ->
                    row { shapes ->
                        SettingsRadioRow(shapes, stringResource(language.name), selected = language.tag == current, onSelect = {
                            if (language.tag != current) setLanguage(context, language.tag)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Reset: the play counts back to zero (at once, with Undo), then Restore defaults, confirmed (as
 * ComfyChair). Settings go back to their defaults and the language follows the system; folders, the
 * library and playlists stay.
 */
@Composable
fun ResetScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val app = context.applicationContext as MusicApplication
    val preferences = app.preferences
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val done = stringResource(R.string.msg_defaults_restored)
    val counts by app.library.playCounts.collectAsStateWithLifecycle()
    val countsReset = stringResource(R.string.msg_play_counts_reset)
    val undo = stringResource(R.string.button_undo)
    SettingsPage(title = stringResource(R.string.title_settings_reset), onBack = onBack) {
        SettingsGroupHeader(stringResource(R.string.title_play_counts))
        SettingsDescription(stringResource(R.string.desc_reset_play_counts))
        DestructiveButton(
            stringResource(R.string.button_reset_play_counts),
            R.drawable.ic_restart_alt,
            onClick = {
                // In the app's scope: the snackbar and its Undo outlive this page
                messageScope.launch {
                    val before = app.library.resetPlayCounts()
                    snackbars.currentSnackbarData?.dismiss()
                    if (snackbars.showSnackbar(countsReset, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        app.library.restorePlayCounts(before)
                    }
                }
            },
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            enabled = counts.isNotEmpty(),
        )
        SettingsGroupHeader(stringResource(R.string.title_settings))
        SettingsDescription(stringResource(R.string.desc_settings_reset))
        // A single destructive action: an outlined button in the error color, as Remove folder and
        // Clear queue (owner's decision)
        DestructiveButton(stringResource(R.string.button_restore_defaults), R.drawable.ic_history, { confirming = true }, Modifier.padding(top = 8.dp))
    }
    if (confirming) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_history,
            title = stringResource(R.string.title_confirm_restore_defaults),
            text = AnnotatedString(stringResource(R.string.msg_confirm_restore_defaults)),
            confirmLabel = stringResource(R.string.button_restore),
            onConfirm = {
                confirming = false
                scope.launch {
                    preferences.restoreDefaults()
                    // The equalizer: off, Flat on every device; the user's presets are data and stay (SPEC-equalizer §13.2, SPEC-output §11)
                    (context.applicationContext as MusicApplication).equalizer.update { it.restoredDefaults() }
                    // The devices stay remembered; bit-perfect is off on every one
                    (context.applicationContext as MusicApplication).outputs.update { it.withoutBitPerfect() }
                    Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                    // Last: a language change recreates the activity
                    if (currentLanguageTag(context).isNotEmpty()) setLanguage(context, "")
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

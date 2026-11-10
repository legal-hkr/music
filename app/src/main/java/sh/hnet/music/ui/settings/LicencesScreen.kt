@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui.settings

import sh.hnet.music.ui.components.SettingsLinkRow
import sh.hnet.music.equalizer.BuiltInPresets
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.music.R
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.settingsRowColors

/**
 * What ships in the app, all under the Apache License 2.0 (checked against the release runtime
 * classpath; update when a dependency with another licence is added).
 */
private val COMPONENTS = listOf(
    "AndroidX (Jetpack Compose, Material 3, Media3, Room, Navigation 3, DataStore, Lifecycle, Activity, Core)",
    "Kotlin",
    "kotlinx.coroutines",
    "kotlinx.serialization",
    "Guava",
    "Okio",
    "JSpecify",
    "JetBrains Java Annotations",
    "Material Symbols",
)

/** Where the built-in Audeze iSine 10 preset's values were published; handed to the browser (the app has no network). */
private const val ISINE_10_SOURCE = "atriumaudio.net/eq/audeze-isine-10"

/**
 * Settings › About › Licences (SPEC §11.6): each component and its licence, the text on tap; then
 * where the built-in equalizer presets come from, with a link to the source (owner's decision).
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val licenceText = remember(resources) { resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() } }
    var showing by rememberSaveable { mutableStateOf(false) }
    val licence = stringResource(R.string.label_apache_licence)
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.msg_no_browser)
    SettingsPage(title = stringResource(R.string.label_licences), onBack = onBack) {
        SettingsGroup {
            COMPONENTS.forEach { name ->
                row { shapes ->
                    SegmentedListItem(
                        onClick = { showing = true },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        supportingContent = { Text(licence) },
                    ) {
                        Text(name)
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.title_preset_sources)) {
            row { shapes ->
                SettingsLinkRow(shapes, R.drawable.ic_equalizer, BuiltInPresets.AUDEZE_ISINE_10.name.orEmpty(), stringResource(R.string.desc_preset_source_isine_10)) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://$ISINE_10_SOURCE".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(licence) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(licenceText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.button_close)) } },
        )
    }
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.PlaylistSort
import sh.hnet.music.library.PlaylistSummary
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsGroupHeader
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.formatCount
import sh.hnet.music.ui.components.settingsRowColors
import sh.hnet.music.ui.library.PlaylistPicture
import sh.hnet.music.ui.theme.MusicTheme

/**
 * Playlist widget (SPEC-widgets §5.4): which playlist a widget shows and how it looks. Opened by
 * the launcher when the widget is placed – Back or Cancel then leave no widget – and again from
 * the widget's settings or from a widget whose playlist was deleted.
 */
class PlaylistWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Until Add widget: the launcher places nothing
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as MusicApplication
        val current = app.playlistWidgets.get(id)
        setContent {
            MusicTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    PlaylistWidgetConfig(
                        app = app,
                        current = current,
                        onCancel = ::finish,
                        onDone = { choice ->
                            app.playlistWidgets.put(id, choice)
                            app.widgets.start(redraw = true)
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistWidgetConfig(app: MusicApplication, current: PlaylistWidgetStore.Choice?, onCancel: () -> Unit, onDone: (PlaylistWidgetStore.Choice) -> Unit) {
    val all by app.playlists.playlists.collectAsStateWithLifecycle()
    val sort by app.preferences.playlistSort.collectAsStateWithLifecycle(PlaylistSort.RECENTLY_CHANGED)
    val locale = LocalConfiguration.current.locales[0]
    // Starred first and always (it is there even while empty), then the others in the Library's order
    val list = all.orEmpty().let { summaries -> summaries.filter { it.starred } + PlaylistRepository.sort(summaries.filter { !it.starred }, sort, locale) }
    var chosen by rememberSaveable { mutableStateOf(current?.playlistId) }
    var look by rememberSaveable { mutableStateOf(current?.look ?: PlaylistLook.COVERS) }
    val selected = list.firstOrNull { it.playlist.id == chosen } ?: list.firstOrNull()
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        SettingsPage(title = stringResource(R.string.title_playlist_widget), onBack = onCancel) {
            // Read here: the group's rows are built outside composition's reach of these helpers
            val rows = list.map { it to pluralStringResource(R.plurals.label_track_count, it.tracks.size, formatCount(it.tracks.size)) }
            Box(Modifier.selectableGroup()) {
                SettingsGroup(stringResource(R.string.label_widget_playlist)) {
                    rows.forEach { (summary, count) ->
                        row { shapes -> PlaylistChoice(summary, count, summary == selected, shapes) { chosen = summary.playlist.id } }
                    }
                }
            }
            // The header with its control, as a group has its header
            Column {
                SettingsGroupHeader(stringResource(R.string.title_widget_look))
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    PlaylistLook.entries.forEachIndexed { index, option ->
                        ToggleButton(
                            checked = look == option,
                            onCheckedChange = { if (it) look = option },
                            shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            colors = ToggleButtonDefaults.colors(
                                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) {
                            Text(stringResource(if (option == PlaylistLook.COVERS) R.string.label_look_covers else R.string.label_look_system), maxLines = 1)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.button_cancel)) }
                Button(onClick = { selected?.let { onDone(PlaylistWidgetStore.Choice(it.playlist.id, look)) } }, enabled = selected != null) {
                    // Changing a placed widget: Save
                    Text(stringResource(if (current == null) R.string.button_add_widget else R.string.button_save))
                }
            }
        }
    }
}

@Composable
private fun PlaylistChoice(summary: PlaylistSummary, count: String, selected: Boolean, shapes: androidx.compose.material3.ListItemShapes, onSelect: () -> Unit) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { PlaylistPicture(summary, 56.dp) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = { Text(count) },
    ) {
        Text(summary.playlist.name)
    }
}

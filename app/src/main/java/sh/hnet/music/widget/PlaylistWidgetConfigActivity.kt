@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.PlaylistSort
import sh.hnet.music.library.PlaylistSummary
import sh.hnet.music.ui.components.SettingsGroup
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
    var look by rememberSaveable { mutableStateOf(current?.look ?: PlaylistLook.FLOATING) }
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
            WidgetLookChoice(
                options = PlaylistLook.entries,
                selected = look,
                label = { if (it == PlaylistLook.COVERS) R.string.label_look_covers else R.string.label_look_floating },
                onSelect = { look = it },
            )
            // Changing a placed widget: Save
            WidgetConfigActions(stringResource(if (current == null) R.string.button_add_widget else R.string.button_save), enabled = selected != null, onCancel = onCancel) {
                selected?.let { onDone(PlaylistWidgetStore.Choice(it.playlist.id, look)) }
            }
        }
    }
}

@Composable
private fun PlaylistChoice(summary: PlaylistSummary, count: String, selected: Boolean, shapes: ListItemShapes, onSelect: () -> Unit) {
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

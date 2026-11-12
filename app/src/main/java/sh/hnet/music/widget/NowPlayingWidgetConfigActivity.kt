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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.theme.MusicTheme

/**
 * Now playing widget: how a placed widget looks – floating, or with a background (owner's request, as
 * the Playlist widget has two looks). Opened from the widget's settings only: placing it asks nothing
 * (`configuration_optional`) and gives the floating look.
 */
class NowPlayingWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as MusicApplication
        val current = app.nowPlayingWidgets.get(id)
        setContent {
            MusicTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    var look by rememberSaveable { mutableStateOf(current) }
                    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                        SettingsPage(title = stringResource(R.string.title_now_playing_widget), onBack = ::finish) {
                            WidgetLookChoice(
                                // The look with a background first, as on the Playlist widget's page; floating is the default
                                options = listOf(NowPlayingLook.CARD, NowPlayingLook.FLOATING),
                                selected = look,
                                label = { if (it == NowPlayingLook.FLOATING) R.string.label_look_floating else R.string.label_look_card },
                                onSelect = { look = it },
                            )
                            WidgetConfigActions(stringResource(R.string.button_save), enabled = true, onCancel = ::finish) {
                                app.nowPlayingWidgets.put(id, look)
                                app.widgets.start(redraw = true)
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }
}

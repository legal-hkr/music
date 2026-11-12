package sh.hnet.music

import android.content.Intent
import android.os.Bundle
import sh.hnet.music.about.WhatsNew
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.music.ui.MusicApp
import sh.hnet.music.ui.settings.LibraryViewModel
import sh.hnet.music.ui.welcome.FirstRun
import sh.hnet.music.ui.welcome.WelcomeWizard
import sh.hnet.music.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    /** Counts requests to show now playing (the notification was tapped); the UI reacts to changes. */
    private val openNowPlaying = mutableIntStateOf(0)

    /** A playlist's page asked for by a Playlist widget: the request's number and the playlist. */
    private val openPlaylist = mutableStateOf(0 to -1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Picks up files added or changed since the last start; not again on rotation.
        if (savedInstanceState == null) {
            (application as MusicApplication).scanner.requestScan()
            WhatsNew.recordStart(this)
            handle(intent)
        }
        setContent {
            MusicTheme {
                val app = application as MusicApplication
                // The first-run wizard while there is nothing at all; decided once, kept on rotation
                var firstRun by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    if (firstRun == null) firstRun = withContext(Dispatchers.IO) { FirstRun.isNeeded(app) }
                }
                when (firstRun) {
                    null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    true -> {
                        val library: LibraryViewModel = viewModel { LibraryViewModel(app) }
                        WelcomeWizard(onFolderPicked = { uri ->
                            library.onFolderPicked(uri)
                            firstRun = false
                        })
                    }
                    false -> MusicApp(openNowPlaying = openNowPlaying.intValue, openPlaylist = openPlaylist.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) openNowPlaying.intValue++
        val playlist = intent?.getLongExtra(EXTRA_OPEN_PLAYLIST, -1) ?: -1
        if (playlist >= 0) openPlaylist.value = openPlaylist.value.first + 1 to playlist
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "sh.hnet.music.OPEN_NOW_PLAYING"
        const val EXTRA_OPEN_PLAYLIST = "sh.hnet.music.OPEN_PLAYLIST"
    }
}

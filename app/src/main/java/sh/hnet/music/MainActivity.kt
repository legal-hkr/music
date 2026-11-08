package sh.hnet.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import sh.hnet.music.ui.MusicApp
import sh.hnet.music.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    /** Counts requests to show now playing (the notification was tapped); the UI reacts to changes. */
    private val openNowPlaying = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Picks up files added or changed since the last start; not again on rotation.
        if (savedInstanceState == null) {
            (application as MusicApplication).scanner.requestScan()
            handle(intent)
        }
        setContent {
            MusicTheme {
                MusicApp(openNowPlaying = openNowPlaying.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) openNowPlaying.intValue++
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "sh.hnet.music.OPEN_NOW_PLAYING"
    }
}

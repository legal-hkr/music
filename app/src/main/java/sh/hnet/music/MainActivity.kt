package sh.hnet.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.music.ui.FoldersScreen
import sh.hnet.music.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Picks up files added or changed since the last start; not again on rotation.
        if (savedInstanceState == null) (application as MusicApplication).scanner.requestScan()
        setContent {
            MusicTheme {
                FoldersScreen()
            }
        }
    }
}

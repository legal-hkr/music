package sh.hnet.music

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.music.backup.BackupManager
import sh.hnet.music.data.MusicDatabase
import sh.hnet.music.equalizer.EqualizerStore
import sh.hnet.music.library.CoverCache
import sh.hnet.music.library.LibraryRepository
import sh.hnet.music.library.LibraryScanner
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.PlaylistTransfer
import sh.hnet.music.output.Outputs
import sh.hnet.music.playback.BitPerfectController
import sh.hnet.music.playback.PlayerConnection
import sh.hnet.music.widget.NowPlayingWidgetStore
import sh.hnet.music.widget.PlaylistWidgetStore
import sh.hnet.music.widget.Widgets

class MusicApplication : Application() {
    /** Work that outlives a screen (scans); lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val database: MusicDatabase by lazy { MusicDatabase.get(this) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(contentResolver, database.libraryFolderDao(), database.trackDao(), appScope, getSharedPreferences("scanner", MODE_PRIVATE))
    }

    val library: LibraryRepository by lazy { LibraryRepository(database.trackDao(), scanner, preferences.skipShortSeconds, appScope) }

    val covers: CoverCache by lazy { CoverCache(this) }
    val transfer: PlaylistTransfer by lazy { PlaylistTransfer(this, playlists, database.playlistDao(), database.libraryFolderDao(), library) }
    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("backup", MODE_PRIVATE)) }
    val equalizer: EqualizerStore by lazy { EqualizerStore(File(filesDir, "equalizer.json"), appScope) }
    val bitPerfect: BitPerfectController by lazy { BitPerfectController(this) }
    val outputs: Outputs by lazy { Outputs(this, File(filesDir, "devices.json"), appScope, preferences) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database.playlistDao(), database.trackDao(), library, scanner, appScope) { getString(R.string.label_starred_playlist) } }

    val player: PlayerConnection by lazy { PlayerConnection(this) }

    val preferences: Preferences by lazy { Preferences(this) }

    val widgets: Widgets by lazy { Widgets(this) }
    val playlistWidgets: PlaylistWidgetStore by lazy { PlaylistWidgetStore(getSharedPreferences("widgets", MODE_PRIVATE)) }
    val nowPlayingWidgets: NowPlayingWidgetStore by lazy { NowPlayingWidgetStore(getSharedPreferences("widgets", MODE_PRIVATE)) }

    override fun onCreate() {
        super.onCreate()
        // Placed widgets follow the player from the start of the process, whatever started it
        widgets.start()
    }
}

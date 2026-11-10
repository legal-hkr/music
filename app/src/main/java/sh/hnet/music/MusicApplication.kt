package sh.hnet.music

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.music.data.MusicDatabase
import sh.hnet.music.library.CoverCache
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.PlaylistTransfer
import sh.hnet.music.library.LibraryRepository
import sh.hnet.music.library.LibraryScanner
import sh.hnet.music.playback.PlayerConnection

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
    val backup: sh.hnet.music.backup.BackupManager by lazy { sh.hnet.music.backup.BackupManager(this, getSharedPreferences("backup", MODE_PRIVATE)) }
    val equalizer: sh.hnet.music.equalizer.EqualizerStore by lazy { sh.hnet.music.equalizer.EqualizerStore(java.io.File(filesDir, "equalizer.json"), appScope) }
    val bitPerfect: sh.hnet.music.playback.BitPerfectController by lazy { sh.hnet.music.playback.BitPerfectController(this) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database.playlistDao(), database.trackDao(), library, scanner, appScope) { getString(R.string.label_starred_playlist) } }

    val player: PlayerConnection by lazy { PlayerConnection(this) }

    val preferences: Preferences by lazy { Preferences(this) }
}

package sh.hnet.music

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.music.data.MusicDatabase
import sh.hnet.music.library.CoverCache
import sh.hnet.music.library.PlaylistRepository
import sh.hnet.music.library.LibraryRepository
import sh.hnet.music.library.LibraryScanner
import sh.hnet.music.playback.PlayerConnection

class MusicApplication : Application() {
    /** Work that outlives a screen (scans); lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val database: MusicDatabase by lazy { MusicDatabase.get(this) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(contentResolver, database.libraryFolderDao(), database.trackDao(), appScope)
    }

    val library: LibraryRepository by lazy { LibraryRepository(database.trackDao(), scanner, appScope) }

    val covers: CoverCache by lazy { CoverCache(this) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database.playlistDao(), database.trackDao(), library, scanner, appScope) }

    val player: PlayerConnection by lazy { PlayerConnection(this) }

    val preferences: Preferences by lazy { Preferences(this) }
}

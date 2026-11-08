package sh.hnet.music

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.music.data.MusicDatabase
import sh.hnet.music.library.LibraryScanner

class MusicApplication : Application() {
    /** Work that outlives a screen (scans); lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val database: MusicDatabase by lazy { MusicDatabase.get(this) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(contentResolver, database.libraryFolderDao(), database.trackDao(), appScope)
    }
}

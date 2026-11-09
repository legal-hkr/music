package sh.hnet.music.ui.library

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import sh.hnet.music.data.LibraryTrack

/** Which tracks are starred and how to change it, for every track menu and now playing (§3). */
class Starring(private val starred: State<Set<String>>, val set: (LibraryTrack, Boolean) -> Unit) {
    fun isStarred(track: LibraryTrack): Boolean = track.documentUri in starred.value
}

val LocalStarring = staticCompositionLocalOf<Starring?> { null }

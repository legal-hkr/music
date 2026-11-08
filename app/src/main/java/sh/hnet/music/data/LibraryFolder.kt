package sh.hnet.music.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** A folder the user picked; [treeUri] holds a persisted read grant (SAF). */
@Entity(tableName = "library_folder")
data class LibraryFolder(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long,
)

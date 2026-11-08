package sh.hnet.music.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryFolderDao {
    /** In the order the folders were added. */
    @Query("SELECT * FROM library_folder ORDER BY addedAt")
    fun observeAll(): Flow<List<LibraryFolder>>

    @Query("SELECT * FROM library_folder ORDER BY addedAt")
    suspend fun all(): List<LibraryFolder>

    @Query("SELECT * FROM library_folder WHERE treeUri = :treeUri")
    suspend fun get(treeUri: String): LibraryFolder?

    @Upsert
    suspend fun upsert(folder: LibraryFolder)

    @Query("DELETE FROM library_folder WHERE treeUri = :treeUri")
    suspend fun delete(treeUri: String)
}

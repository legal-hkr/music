package sh.hnet.music.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist")
    fun observePlaylists(): Flow<List<Playlist>>

    @Query("SELECT playlistId, documentUri FROM playlist_entry ORDER BY playlistId, position")
    fun observeEntryRefs(): Flow<List<EntryRef>>

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistEntry>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: Long): Playlist?

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntry>)

    @Query("UPDATE playlist SET name = :name, changedAt = :changedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, changedAt: Long)

    @Query("DELETE FROM playlist_entry WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deleteEntries(id)
        deletePlaylist(id)
    }

    /** Puts a deleted playlist back with its own ids (Undo). */
    @Transaction
    suspend fun restore(playlist: Playlist, entries: List<PlaylistEntry>) {
        insert(playlist)
        insertEntries(entries)
    }
}

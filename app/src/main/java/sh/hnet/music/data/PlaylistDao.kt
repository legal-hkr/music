package sh.hnet.music.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist")
    fun observePlaylists(): Flow<List<Playlist>>

    @Query("SELECT id, playlistId, documentUri FROM playlist_entry ORDER BY playlistId, position")
    fun observeEntryRefs(): Flow<List<EntryRef>>

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistEntry>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: Long): Playlist?

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observePlaylist(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId ORDER BY position")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntry>>

    @Query("SELECT * FROM playlist_entry WHERE id = :id")
    suspend fun entry(id: Long): PlaylistEntry?

    @Insert
    suspend fun insertEntry(entry: PlaylistEntry)

    @Query("UPDATE playlist_entry SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("UPDATE playlist_entry SET position = position + 1 WHERE playlistId = :playlistId AND position >= :from")
    suspend fun shiftFrom(playlistId: Long, from: Int)

    @Query("DELETE FROM playlist_entry WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("UPDATE playlist SET changedAt = :changedAt WHERE id = :id")
    suspend fun touch(id: Long, changedAt: Long)

    @Query("SELECT * FROM playlist_entry ORDER BY playlistId, position")
    suspend fun allEntries(): List<PlaylistEntry>

    /** A re-resolved entry or a refreshed snapshot: not a change the user made, so no changedAt. */
    @Update
    suspend fun updateEntries(entries: List<PlaylistEntry>)

    @Query("SELECT id FROM playlist WHERE builtin = :builtin")
    suspend fun builtinId(builtin: String): Long?

    /** The tracks in Starred (SPEC-now-playing-v2 §3). */
    @Query("SELECT e.documentUri FROM playlist_entry e JOIN playlist p ON p.id = e.playlistId WHERE p.builtin = 'starred' AND e.documentUri IS NOT NULL")
    fun observeStarred(): Flow<List<String>>

    @Query("SELECT COALESCE(MIN(position), 0) FROM playlist_entry WHERE playlistId = :playlistId")
    suspend fun firstPosition(playlistId: Long): Int

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId AND documentUri = :documentUri")
    suspend fun entriesOf(playlistId: Long, documentUri: String): List<PlaylistEntry>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entry WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int

    @Query("SELECT documentUri FROM playlist_entry WHERE playlistId = :playlistId AND documentUri IS NOT NULL")
    suspend fun documentUris(playlistId: Long): List<String>

    @Insert
    suspend fun insertEntriesReturningIds(entries: List<PlaylistEntry>): List<Long>

    @Query("DELETE FROM playlist_entry WHERE id IN (:ids)")
    suspend fun deleteEntriesById(ids: List<Long>)

    /** Appends entries (their positions already after the last one); returns their ids. */
    @Transaction
    suspend fun append(playlistId: Long, entries: List<PlaylistEntry>, changedAt: Long): List<Long> {
        val ids = insertEntriesReturningIds(entries)
        touch(playlistId, changedAt)
        return ids
    }

    @Transaction
    suspend fun removeEntries(playlistId: Long, ids: List<Long>, changedAt: Long) {
        deleteEntriesById(ids)
        touch(playlistId, changedAt)
    }

    /** All entries replaced at once ("Save as playlist" over an existing one, and its Undo). */
    @Transaction
    suspend fun replaceEntries(playlistId: Long, entries: List<PlaylistEntry>, changedAt: Long) {
        deleteEntries(playlistId)
        insertEntries(entries)
        touch(playlistId, changedAt)
    }

    /** The new order, written once on drop (one change). */
    @Transaction
    suspend fun reorder(playlistId: Long, entryIds: List<Long>, changedAt: Long) {
        entryIds.forEachIndexed { index, id -> setPosition(id, index) }
        touch(playlistId, changedAt)
    }

    @Transaction
    suspend fun removeEntry(entry: PlaylistEntry, changedAt: Long) {
        deleteEntry(entry.id)
        touch(entry.playlistId, changedAt)
    }

    /** Puts a removed entry back at its position, the same entry (Undo). */
    @Transaction
    suspend fun restoreEntry(entry: PlaylistEntry, changedAt: Long) {
        shiftFrom(entry.playlistId, entry.position)
        insertEntry(entry)
        touch(entry.playlistId, changedAt)
    }

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

    @Query("SELECT * FROM playlist ORDER BY id")
    suspend fun allPlaylists(): List<Playlist>

    @Query("DELETE FROM playlist_entry")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM playlist")
    suspend fun deleteAllPlaylists()

    /** Restore from backup: every playlist, Starred included, replaced at once (no merging). */
    @Transaction
    suspend fun replaceAll(playlists: List<Pair<Playlist, List<PlaylistEntry>>>) {
        deleteAllEntries()
        deleteAllPlaylists()
        playlists.forEach { (playlist, entries) ->
            val id = insert(playlist)
            insertEntries(entries.mapIndexed { position, entry -> entry.copy(playlistId = id, position = position) })
        }
    }
}

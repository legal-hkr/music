package sh.hnet.music.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT documentUri, size, lastModified FROM track WHERE folderUri = :folderUri")
    suspend fun trackStates(folderUri: String): List<FileState>

    @Query("SELECT documentUri, size, lastModified FROM skipped_file WHERE folderUri = :folderUri")
    suspend fun skippedStates(folderUri: String): List<FileState>

    @Upsert
    suspend fun upsertTracks(tracks: List<Track>)

    @Upsert
    suspend fun upsertSkipped(files: List<SkippedFile>)

    @Query("DELETE FROM track WHERE documentUri IN (:uris)")
    suspend fun deleteTracks(uris: List<String>)

    @Query("DELETE FROM skipped_file WHERE documentUri IN (:uris)")
    suspend fun deleteSkipped(uris: List<String>)

    @Query("DELETE FROM track WHERE folderUri = :folderUri")
    suspend fun deleteTracksOfFolder(folderUri: String)

    @Query("DELETE FROM skipped_file WHERE folderUri = :folderUri")
    suspend fun deleteSkippedOfFolder(folderUri: String)

    /** One batch of scan results: a file is in exactly one of the two tables. */
    @Transaction
    suspend fun applyResults(tracks: List<Track>, skipped: List<SkippedFile>) {
        if (tracks.isNotEmpty()) {
            upsertTracks(tracks)
            deleteSkipped(tracks.map { it.documentUri })
        }
        if (skipped.isNotEmpty()) {
            upsertSkipped(skipped)
            deleteTracks(skipped.map { it.documentUri })
        }
    }

    /** Files gone from the folder; [uris] in chunks well below SQLite's variable limit. */
    @Transaction
    suspend fun removeFiles(uris: List<String>) {
        uris.chunked(500).forEach {
            deleteTracks(it)
            deleteSkipped(it)
        }
    }

    @Transaction
    suspend fun removeFolder(folderUri: String) {
        deleteTracksOfFolder(folderUri)
        deleteSkippedOfFolder(folderUri)
    }

    @Query(
        "SELECT documentUri, folderUri, relativePath, title, artists, album, albumArtists, trackNumber, " +
            "discNumber, year, durationMs, compilation, titleSort, artistSort, albumSort, albumArtistSort, " +
            "lastModified, size, hasEmbeddedCover FROM track",
    )
    fun observeLibrary(): Flow<List<LibraryTrack>>

    @Query("SELECT * FROM skipped_file WHERE folderUri = :folderUri ORDER BY relativePath COLLATE NOCASE")
    fun observeSkipped(folderUri: String): Flow<List<SkippedFile>>

    @Query("SELECT folderUri, COUNT(*) AS count FROM track GROUP BY folderUri")
    fun observeTrackCounts(): Flow<List<FolderCount>>

    @Query("SELECT folderUri, COUNT(*) AS count FROM skipped_file GROUP BY folderUri")
    fun observeSkippedCounts(): Flow<List<FolderCount>>
}

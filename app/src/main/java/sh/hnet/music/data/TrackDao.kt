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
            "lastModified, size, hasEmbeddedCover, trackGainDb, trackPeak, albumGainDb, albumPeak FROM track",
    )
    fun observeLibrary(): Flow<List<LibraryTrack>>

    @Query(
        "SELECT documentUri, folderUri, relativePath, title, artists, album, albumArtists, trackNumber, " +
            "discNumber, year, durationMs, compilation, titleSort, artistSort, albumSort, albumArtistSort, " +
            "lastModified, size, hasEmbeddedCover, trackGainDb, trackPeak, albumGainDb, albumPeak FROM track WHERE documentUri IN (:uris)",
    )
    suspend fun libraryTracks(uris: List<String>): List<LibraryTrack>

    /** Which of [uris] are in the index (visible or behind a folder that lost access). */
    @Query("SELECT documentUri FROM track WHERE documentUri IN (:uris)")
    suspend fun existing(uris: List<String>): List<String>

    @Query("SELECT * FROM track WHERE documentUri = :documentUri")
    fun observeTrack(documentUri: String): Flow<Track?>


    @Query("SELECT * FROM skipped_file WHERE folderUri = :folderUri ORDER BY relativePath COLLATE NOCASE")
    fun observeSkipped(folderUri: String): Flow<List<SkippedFile>>

    /** Tracks per folder, without those shorter than [minMs] (0: none are). Unknown lengths (0) always count. */
    @Query("SELECT folderUri, COUNT(*) AS count FROM track WHERE durationMs <= 0 OR durationMs >= :minMs GROUP BY folderUri")
    fun observeTrackCounts(minMs: Long): Flow<List<FolderCount>>

    /** Tracks left out for being shorter than [minMs], per folder. */
    @Query("SELECT folderUri, COUNT(*) AS count FROM track WHERE durationMs > 0 AND durationMs < :minMs GROUP BY folderUri")
    fun observeShortCounts(minMs: Long): Flow<List<FolderCount>>

    /** One folder's tracks left out for being shorter than [minMs]. */
    @Query("SELECT documentUri, relativePath, durationMs FROM track WHERE folderUri = :folderUri AND durationMs > 0 AND durationMs < :minMs ORDER BY relativePath COLLATE NOCASE")
    fun observeShort(folderUri: String, minMs: Long): Flow<List<ShortFile>>

    @Query("SELECT folderUri, COUNT(*) AS count FROM skipped_file GROUP BY folderUri")
    fun observeSkippedCounts(): Flow<List<FolderCount>>
}

/** A track left out of the library for being short (Settings › Music folders › Skip short files). */
data class ShortFile(val documentUri: String, val relativePath: String, val durationMs: Long)

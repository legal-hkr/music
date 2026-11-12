package sh.hnet.music.data

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities = [LibraryFolder::class, Track::class, SkippedFile::class, Playlist::class, PlaylistEntry::class, PlayCount::class],
    version = 5,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
@ColumnTypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile private var instance: MusicDatabase? = null

        fun get(context: Context): MusicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder<MusicDatabase>(context.applicationContext, "music.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
                .also { instance = it }
        }
    }
}

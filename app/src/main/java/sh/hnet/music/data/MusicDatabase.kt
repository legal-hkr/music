package sh.hnet.music.data

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities = [LibraryFolder::class, Track::class, SkippedFile::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ColumnTypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun trackDao(): TrackDao

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

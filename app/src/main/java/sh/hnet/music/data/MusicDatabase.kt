package sh.hnet.music.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(entities = [LibraryFolder::class], version = 1)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun libraryFolderDao(): LibraryFolderDao

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

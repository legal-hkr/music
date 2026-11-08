package sh.hnet.music

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.hnet.music.library.AlbumSort
import sh.hnet.music.library.TrackSort

private val Context.settings by preferencesDataStore("settings")

/** Choices the user made in the UI that outlive the process (app-private, not backed up). */
class Preferences(private val context: Context) {
    private val albumSortKey = stringPreferencesKey("album_sort")

    val albumSort: Flow<AlbumSort> = context.settings.data.map { prefs ->
        prefs[albumSortKey]?.let { name -> AlbumSort.entries.firstOrNull { it.name == name } } ?: AlbumSort.ALBUM_ARTIST
    }

    suspend fun setAlbumSort(sort: AlbumSort) {
        context.settings.edit { it[albumSortKey] = sort.name }
    }

    private val trackSortKey = stringPreferencesKey("track_sort")

    val trackSort: Flow<TrackSort> = context.settings.data.map { prefs ->
        prefs[trackSortKey]?.let { name -> TrackSort.entries.firstOrNull { it.name == name } } ?: TrackSort.TITLE
    }

    suspend fun setTrackSort(sort: TrackSort) {
        context.settings.edit { it[trackSortKey] = sort.name }
    }
}

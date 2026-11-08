package sh.hnet.music

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.hnet.music.library.AlbumSort
import sh.hnet.music.library.TrackSort
import sh.hnet.music.playback.GainSettings
import sh.hnet.music.playback.ReplayGainMode

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

    private val gainModeKey = stringPreferencesKey("replaygain_mode")
    private val preampKey = floatPreferencesKey("replaygain_preamp_db")
    private val preventClippingKey = booleanPreferencesKey("replaygain_prevent_clipping")

    val gain: Flow<GainSettings> = context.settings.data.map { prefs ->
        GainSettings(
            mode = prefs[gainModeKey]?.let { name -> ReplayGainMode.entries.firstOrNull { it.name == name } } ?: ReplayGainMode.OFF,
            preampDb = prefs[preampKey] ?: -6f,
            preventClipping = prefs[preventClippingKey] ?: true,
        )
    }

    suspend fun setGainMode(mode: ReplayGainMode) = context.settings.edit { it[gainModeKey] = mode.name }

    suspend fun setPreamp(db: Float) = context.settings.edit { it[preampKey] = db }

    suspend fun setPreventClipping(on: Boolean) = context.settings.edit { it[preventClippingKey] = on }
}

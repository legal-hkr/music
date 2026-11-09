package sh.hnet.music

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.hnet.music.library.AlbumSort
import sh.hnet.music.library.PlaylistSort
import sh.hnet.music.library.PlaylistFormat
import sh.hnet.music.library.TrackSort
import sh.hnet.music.playback.GainSettings
import sh.hnet.music.playback.ReplayGainMode
import sh.hnet.music.ui.library.LibraryView

private val Context.settings by preferencesDataStore("settings")

private const val LAST_USED = "LAST_USED"

/** Skip short files: Off, 10, 30 or 60 s. */
val SKIP_SHORT_CHOICES = listOf(0, 10, 30, 60)

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

    private val playlistSortKey = stringPreferencesKey("playlist_sort")

    val playlistSort: Flow<PlaylistSort> = context.settings.data.map { prefs ->
        prefs[playlistSortKey]?.let { name -> PlaylistSort.entries.firstOrNull { it.name == name } } ?: PlaylistSort.RECENTLY_CHANGED
    }

    suspend fun setPlaylistSort(sort: PlaylistSort) = context.settings.edit { it[playlistSortKey] = sort.name }

    private val exportFormatKey = stringPreferencesKey("playlist_export_format")

    /** The last format chosen in the export dialog (§10). */
    val exportFormat: Flow<PlaylistFormat> = context.settings.data.map { prefs ->
        prefs[exportFormatKey]?.let { name -> PlaylistFormat.entries.firstOrNull { it.name == name } } ?: PlaylistFormat.M3U8
    }

    suspend fun setExportFormat(format: PlaylistFormat) = context.settings.edit { it[exportFormatKey] = format.name }

    /**
     * Restore defaults (Settings › Reset): every setting of this store back to its default. Nothing
     * else lives here (What's new keeps its own preferences, playlists the database) but the last
     * used Library view, which is a memory, not a setting, and stays.
     */
    suspend fun restoreDefaults() = context.settings.edit { prefs ->
        val lastView = prefs[lastViewKey]
        prefs.clear()
        lastView?.let { prefs[lastViewKey] = it }
    }

    private val skipShortKey = intPreferencesKey("skip_short_seconds")

    /** Files shorter than this many seconds stay out of the library (SPEC-settings-v2 §5.2); 0 = off, the default. */
    val skipShortSeconds: Flow<Int> = context.settings.data.map { prefs -> prefs[skipShortKey]?.takeIf { it in SKIP_SHORT_CHOICES } ?: 0 }

    suspend fun setSkipShortSeconds(seconds: Int) = context.settings.edit { it[skipShortKey] = seconds }

    private val viewOrderKey = stringPreferencesKey("library_view_order")
    private val startViewKey = stringPreferencesKey("library_start_view")
    private val lastViewKey = stringPreferencesKey("library_last_view")

    /** The order of the Library's views (SPEC-settings-v2 §4.1). */
    val viewOrder: Flow<List<LibraryView>> = context.settings.data.map { LibraryView.order(it[viewOrderKey]) }

    suspend fun setViewOrder(order: List<LibraryView>) = context.settings.edit { it[viewOrderKey] = LibraryView.save(order) }

    /** The view the Library opens on: one of the four, or null for Last used (§4.2); Albums by default. */
    val startView: Flow<LibraryView?> = context.settings.data.map { prefs ->
        when (val name = prefs[startViewKey]) {
            null -> LibraryView.ALBUMS
            LAST_USED -> null
            else -> LibraryView.entries.firstOrNull { it.name == name } ?: LibraryView.ALBUMS
        }
    }

    suspend fun setStartView(view: LibraryView?) = context.settings.edit { it[startViewKey] = view?.name ?: LAST_USED }

    /** The view the Library showed last, for Last used. */
    val lastView: Flow<LibraryView?> = context.settings.data.map { prefs -> prefs[lastViewKey]?.let { name -> LibraryView.entries.firstOrNull { it.name == name } } }

    suspend fun setLastView(view: LibraryView) = context.settings.edit { it[lastViewKey] = view.name }

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

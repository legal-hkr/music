package sh.hnet.music

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import sh.hnet.music.backup.BackupSettings
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

/** DSD is played as PCM at 88.2 or 176.4 kHz (streams based on 48 kHz get 96 or 192 kHz). */
val DSD_PCM_RATES = listOf(88_200, 176_400)

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

    /** The settings a backup carries (SPEC-settings-v2 §7.2): everything in this store but the last used view. */
    suspend fun backupSettings(): BackupSettings {
        val prefs = context.settings.data.first()
        return BackupSettings(
            sorts = BackupSettings.Sorts(albums = prefs[albumSortKey], tracks = prefs[trackSortKey], playlists = prefs[playlistSortKey]),
            libraryViews = BackupSettings.LibraryViews(
                order = prefs[viewOrderKey]?.let { LibraryView.order(it).map(LibraryView::name) }.orEmpty(),
                opensOn = prefs[startViewKey],
            ),
            playback = BackupSettings.Playback(
                replayGain = prefs[gainModeKey],
                preampDb = prefs[preampKey],
                preventClipping = prefs[preventClippingKey],
                dsdPcmRate = prefs[dsdPcmRateKey],
            ),
            exportFormat = prefs[exportFormatKey],
            skipShorterThanSeconds = prefs[skipShortKey] ?: 0,
            artistsWithAlbumsOnly = prefs[artistsWithAlbumsKey],
        )
    }

    /**
     * Restore from backup: the backup's settings replace these; what it lacks or doesn't know (a
     * name from a later version) goes back to the default, as the readers above treat it.
     */
    suspend fun restoreSettings(backup: BackupSettings) = context.settings.edit { prefs ->
        val lastView = prefs[lastViewKey]
        prefs.clear()
        lastView?.let { prefs[lastViewKey] = it }
        backup.sorts.albums?.let { prefs[albumSortKey] = it }
        backup.sorts.tracks?.let { prefs[trackSortKey] = it }
        backup.sorts.playlists?.let { prefs[playlistSortKey] = it }
        if (backup.libraryViews.order.isNotEmpty()) prefs[viewOrderKey] = backup.libraryViews.order.joinToString(",")
        backup.libraryViews.opensOn?.let { prefs[startViewKey] = it }
        backup.playback.replayGain?.let { prefs[gainModeKey] = it }
        backup.playback.preampDb?.let { prefs[preampKey] = it }
        backup.playback.preventClipping?.let { prefs[preventClippingKey] = it }
        backup.playback.dsdPcmRate?.takeIf { it in DSD_PCM_RATES }?.let { prefs[dsdPcmRateKey] = it }
        backup.exportFormat?.let { prefs[exportFormatKey] = it }
        backup.artistsWithAlbumsOnly?.let { prefs[artistsWithAlbumsKey] = it }
        backup.skipShorterThanSeconds.takeIf { it in SKIP_SHORT_CHOICES && it != 0 }?.let { prefs[skipShortKey] = it }
    }

    /** Whether any setting was ever stored (the first run has none). */
    suspend fun hasAnySetting(): Boolean = context.settings.data.first().asMap().keys.any { it != lastViewKey }

    private val artistsWithAlbumsKey = booleanPreferencesKey("artists_with_albums_only")

    /** The Artists view lists only artists with an album of their own (owner's decision); on by default. */
    val artistsWithAlbumsOnly: Flow<Boolean> = context.settings.data.map { it[artistsWithAlbumsKey] ?: true }

    suspend fun setArtistsWithAlbumsOnly(on: Boolean) = context.settings.edit { it[artistsWithAlbumsKey] = on }

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

    private val dsdPcmRateKey = intPreferencesKey("dsd_pcm_rate")

    /** The PCM rate DSD is converted to for playback: one of [DSD_PCM_RATES], the first by default. */
    val dsdPcmRate: Flow<Int> = context.settings.data.map { prefs -> prefs[dsdPcmRateKey]?.takeIf { it in DSD_PCM_RATES } ?: DSD_PCM_RATES.first() }

    suspend fun setDsdPcmRate(rate: Int) = context.settings.edit { it[dsdPcmRateKey] = rate }
}

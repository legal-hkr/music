package sh.hnet.music.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.MostPlayed

/**
 * Keeps the placed widgets up to date (SPEC-widgets §2.4): on events only – the track, play or
 * pause, the queue going empty – never on a timer. Runs for the life of the process once a widget
 * exists or one of them is used.
 */
class Widgets(private val app: MusicApplication) {
    private var nowPlaying = false
    private var playlists = false
    private var albums = false

    /** What each Playlist widget was last drawn from; in memory, so a new process draws them once more. */
    private val drawn = HashMap<Int, Any?>()
    private val redraws = MutableStateFlow(0)

    /**
     * Starts following what the placed widgets show; a second call does nothing more, unless [redraw]
     * asks for the Playlist widgets to be drawn again (one was placed, or its settings changed). Main thread only.
     */
    fun start(redraw: Boolean = false) {
        if (!nowPlaying && NowPlayingWidgetProvider.ids(app).isNotEmpty()) {
            nowPlaying = true
            followPlayer()
        }
        if (!playlists && PlaylistWidgetProvider.ids(app).isNotEmpty()) {
            playlists = true
            followPlaylists()
        }
        if (!albums && AlbumsWidgetProvider.ids(app).isNotEmpty()) {
            albums = true
            followAlbums()
        }
        if (redraw) {
            drawn.clear()
            redraws.value++
        }
    }

    private fun followPlayer() {
        app.player.connect()
        app.appScope.launch(Dispatchers.Main) {
            combine(app.player.connected, app.player.currentId, app.player.isPlaying, app.library.playableByUri) { connected, id, playing, tracks ->
                Shown(connected, id, id?.let(tracks::get), playing)
            }.distinctUntilChanged().collectLatest { shown ->
                // Not connected yet: what the widget shows from before stays
                if (!shown.connected) return@collectLatest
                if (shown.track == null) {
                    // Nothing queued, or a track the library hasn't listed yet. Either may be a moment's state –
                    // the service restoring its queue, the library loading – so the empty widget waits
                    delay(SETTLE_MS)
                    if (shown.id == null) NowPlayingWidgetProvider.update(app, NowPlayingWidgetProvider.Content(null, null, null, false))
                    return@collectLatest
                }
                val track = shown.track
                val size = NowPlayingWidgetProvider.coverSize(app)
                val cover = app.covers.load(track, app.covers.sizeFor(size))?.let { WidgetBitmaps.cookie(it, size) }
                NowPlayingWidgetProvider.update(app, NowPlayingWidgetProvider.Content(track.displayTitle, track.artists.joinToString(", "), cover, shown.playing))
            }
        }
    }

    /** Renamed, changed, deleted: each Playlist widget is drawn again when what it shows is different (SPEC-widgets §5.5). */
    private fun followPlaylists() {
        app.appScope.launch(Dispatchers.Main) {
            combine(app.playlists.playlists.filterNotNull(), app.library.tracks.filterNotNull(), redraws) { list, _, _ -> list }.collectLatest { list ->
                // Edits come in bursts, and the library's first reading after the playlists'
                delay(PLAYLIST_SETTLE_MS)
                for (id in PlaylistWidgetProvider.ids(app)) {
                    val choice = app.playlistWidgets.get(id) ?: continue
                    val summary = list.firstOrNull { it.playlist.id == choice.playlistId }
                    val key = PlaylistWidgetProvider.key(choice.look, summary)
                    if (drawn.containsKey(id) && drawn[id] == key) continue
                    PlaylistWidgetProvider.update(app, id, choice.look, summary) { track, size -> app.covers.load(track, app.covers.sizeFor(size)) }
                    drawn[id] = key
                }
            }
        }
    }

    /**
     * The fan is drawn again only when the albums in it change – another album among the most played, or
     * their order – not with each listen (SPEC-widgets §4.3).
     */
    private fun followAlbums() {
        app.appScope.launch(Dispatchers.Main) {
            combine(app.library.albums.filterNotNull(), app.library.playCounts, redraws) { albums, counts, redraw ->
                MostPlayed.albums(albums, counts, AlbumsWidgetProvider.ALBUMS) to redraw
            }.distinctUntilChanged { old, new -> old.second == new.second && old.first.map { it.key to it.coverTrack?.documentUri } == new.first.map { it.key to it.coverTrack?.documentUri } }
                .collectLatest { (most, _) ->
                    // The library's first reading comes before the counts'
                    delay(PLAYLIST_SETTLE_MS)
                    val size = AlbumsWidgetProvider.coverSize(app)
                    val covers = most.map { album -> album.coverTrack?.let { app.covers.load(it, app.covers.sizeFor(size)) } }
                    AlbumsWidgetProvider.update(app, most, covers)
                }
        }
    }

    private data class Shown(val connected: Boolean, val id: String?, val track: LibraryTrack?, val playing: Boolean)

    private companion object {
        const val SETTLE_MS = 3_000L
        const val PLAYLIST_SETTLE_MS = 400L
    }
}

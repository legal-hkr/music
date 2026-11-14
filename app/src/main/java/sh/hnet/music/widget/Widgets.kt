package sh.hnet.music.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.MostPlayed
import sh.hnet.music.playback.QueueStore

/**
 * Keeps the placed widgets up to date (SPEC-widgets §2.4): on events only – the track, play or
 * pause, the queue going empty – never on a timer. Runs for the life of the process once a widget
 * exists or one of them is used.
 */
class Widgets(private val app: MusicApplication) {
    private var nowPlaying = false
    private var playlists = false
    private var albums = false

    /** The playback service is up in this process. */
    private var playerUp = false

    /** What each Playlist widget was last drawn from; in memory, so a new process draws them once more. */
    private val drawn = HashMap<Int, Any?>()
    private val redraws = MutableStateFlow(0)

    /** What the Now playing widgets show, to draw them again when asked to. */
    private var nowPlayingContent: NowPlayingWidgetProvider.Content? = null

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
            // One was placed, or the theme, the wallpaper's colors or the language changed
            nowPlayingContent?.let { NowPlayingWidgetProvider.update(app, it) }
            drawn.clear()
            redraws.value++
        }
    }

    /**
     * The playback service started – the app was opened, a widget's button used, a headset's key pressed.
     * From here the Now playing widgets show the player itself. Main thread only.
     */
    fun playerStarted() {
        playerUp = true
        if (nowPlaying) app.player.connect()
    }

    /**
     * A process started only to draw the widgets doesn't start the player to ask what it holds: until
     * the player runs for a reason of its own, the widgets show the track it stopped on, read from the
     * saved queue, paused. Their buttons start it, as does opening the app.
     */
    private fun followPlayer() {
        if (playerUp) app.player.connect()
        app.appScope.launch(Dispatchers.Main) {
            val saved = withContext(Dispatchers.IO) { QueueStore(app).load() }?.currentUri
            combine(app.player.connected, app.player.currentId, app.player.isPlaying, app.library.playableByUri) { connected, id, playing, tracks ->
                val shown = if (connected) id else saved
                Shown(shown, shown?.let(tracks::get), connected && playing)
            }.distinctUntilChanged().collectLatest { shown ->
                if (shown.track == null) {
                    // Nothing queued, or a track the library hasn't listed yet. Either may be a moment's state –
                    // the service restoring its queue, the library loading – so the empty widget waits
                    delay(SETTLE_MS)
                    if (shown.id == null) show(NowPlayingWidgetProvider.Content(null, null, null, false))
                    return@collectLatest
                }
                val track = shown.track
                val size = NowPlayingWidgetProvider.coverSize(app)
                // A circle here (owner's choice); the Playlist widgets have their own shape
                // Drawn off the main thread, as every widget picture is
                val cover = app.covers.load(track, app.covers.sizeFor(size))?.let { withContext(Dispatchers.Default) { WidgetBitmaps.circle(it, size) } }
                show(NowPlayingWidgetProvider.Content(track.displayTitle, track.artists.joinToString(", "), cover, shown.playing))
            }
        }
    }

    private fun show(content: NowPlayingWidgetProvider.Content) {
        nowPlayingContent = content
        NowPlayingWidgetProvider.update(app, content)
    }

    /** Renamed, changed, deleted: each Playlist widget is drawn again when what it shows is different (SPEC-widgets §5.5). */
    private fun followPlaylists() {
        app.appScope.launch(Dispatchers.Main) {
            combine(app.playlists.playlists.filterNotNull(), app.library.tracks.filterNotNull(), redraws) { list, _, _ -> list }.collectLatest { list ->
                // Edits come in bursts, and the library's first reading after the playlists'
                delay(PLAYLIST_SETTLE_MS)
                for (id in PlaylistWidgetProvider.ids(app)) {
                    val playlistId = app.playlistWidgets.get(id) ?: continue
                    val summary = list.firstOrNull { it.playlist.id == playlistId }
                    val key = PlaylistWidgetProvider.key(summary)
                    if (drawn.containsKey(id) && drawn[id] == key) continue
                    withContext(Dispatchers.Default) {
                        PlaylistWidgetProvider.update(app, id, summary) { track, size -> app.covers.load(track, app.covers.sizeFor(size)) }
                    }
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
                    withContext(Dispatchers.Default) { AlbumsWidgetProvider.update(app, most, covers) }
                }
        }
    }

    private data class Shown(val id: String?, val track: LibraryTrack?, val playing: Boolean)

    private companion object {
        const val SETTLE_MS = 3_000L
        const val PLAYLIST_SETTLE_MS = 400L
    }
}

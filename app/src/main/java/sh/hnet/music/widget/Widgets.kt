package sh.hnet.music.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import sh.hnet.music.MusicApplication
import sh.hnet.music.data.LibraryTrack

/**
 * Keeps the placed widgets up to date (SPEC-widgets §2.4): on events only – the track, play or
 * pause, the queue going empty – never on a timer. Runs for the life of the process once a widget
 * exists or one of them is used.
 */
class Widgets(private val app: MusicApplication) {
    private var started = false

    /** Starts following the player, if a widget is placed; a second call does nothing. Main thread only. */
    fun start() {
        if (started || NowPlayingWidgetProvider.ids(app).isEmpty()) return
        started = true
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

    private data class Shown(val connected: Boolean, val id: String?, val track: LibraryTrack?, val playing: Boolean)

    private companion object {
        const val SETTLE_MS = 3_000L
    }
}

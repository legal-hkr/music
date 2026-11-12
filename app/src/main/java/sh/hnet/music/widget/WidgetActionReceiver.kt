package sh.hnet.music.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.music.MusicApplication

/**
 * A widget's buttons (SPEC-widgets §3.4, §5.5): play, pause, previous and next, and playing a playlist,
 * go to the player through the app's own controller, without opening the app. Not exported: only our pending intents reach it.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MusicApplication
        // The process may have been started for this tap: it stays until the player has the command
        val result = goAsync()
        var finished = false
        val finish = { if (!finished) { finished = true; result.finish() } }
        app.widgets.start()
        when (intent.action) {
            // Play and Pause apart, not a toggle: after the process was killed a widget may still show Pause,
            // and a tap on it must not start anything. With nothing queued yet (the service is just
            // restoring its kept queue), Play stands and starts it
            ACTION_PLAY -> app.player.play()
            ACTION_PAUSE -> app.player.pause()
            ACTION_PREVIOUS -> app.player.previous()
            ACTION_NEXT -> app.player.next()
            ACTION_PLAYLIST -> {
                val id = intent.getLongExtra(EXTRA_PLAYLIST, -1)
                val shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                app.appScope.launch(Dispatchers.Main) {
                    // In a process started for this tap the playlists and the library are still being read
                    val summary = withTimeoutOrNull(LOAD_MS) {
                        app.playlists.playlists.mapNotNull { list -> list?.firstOrNull { it.playlist.id == id }?.takeIf { it.tracks.isNotEmpty() } }.first()
                    }
                    // From the first track with shuffle off, or shuffled (§5.5)
                    if (summary != null) app.player.play(summary.tracks, 0, shuffle, summary.entryIds)
                    app.player.whenConnected(finish)
                }
                Handler(Looper.getMainLooper()).postDelayed(finish, TIMEOUT_MS)
                return
            }
        }
        app.player.whenConnected(finish)
        // Should the player not answer, the broadcast still ends
        Handler(Looper.getMainLooper()).postDelayed(finish, TIMEOUT_MS)
    }

    companion object {
        const val ACTION_PLAY = "sh.hnet.music.widget.PLAY"
        const val ACTION_PAUSE = "sh.hnet.music.widget.PAUSE"
        const val ACTION_PREVIOUS = "sh.hnet.music.widget.PREVIOUS"
        const val ACTION_NEXT = "sh.hnet.music.widget.NEXT"
        const val ACTION_PLAYLIST = "sh.hnet.music.widget.PLAYLIST"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_SHUFFLE = "shuffle"

        /** Plays the playlist [id], shuffled or from its first track. */
        fun playlist(context: Context, id: Long, shuffle: Boolean): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java)
                .setAction(ACTION_PLAYLIST)
                // Told apart by its address: extras alone don't make two pending intents differ
                .setData("music-widget://play/$id/$shuffle".toUri())
                .putExtra(EXTRA_PLAYLIST, id)
                .putExtra(EXTRA_SHUFFLE, shuffle)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun intent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0, Intent(context, WidgetActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private const val TIMEOUT_MS = 8_000L
        private const val LOAD_MS = 6_000L
    }
}

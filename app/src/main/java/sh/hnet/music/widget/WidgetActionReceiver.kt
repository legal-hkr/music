package sh.hnet.music.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import sh.hnet.music.MusicApplication

/**
 * A widget's buttons (SPEC-widgets §3.4): play/pause, previous and next go to the player through the
 * app's own controller, without opening the app. Not exported: only our pending intents reach it.
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
            // With nothing queued yet (the service is just restoring its kept queue), Play stands and starts it
            ACTION_PLAY_PAUSE -> app.player.togglePlayPause()
            ACTION_PREVIOUS -> app.player.previous()
            ACTION_NEXT -> app.player.next()
        }
        app.player.whenConnected(finish)
        // Should the player not answer, the broadcast still ends
        Handler(Looper.getMainLooper()).postDelayed(finish, TIMEOUT_MS)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "sh.hnet.music.widget.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "sh.hnet.music.widget.PREVIOUS"
        const val ACTION_NEXT = "sh.hnet.music.widget.NEXT"

        fun intent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0, Intent(context, WidgetActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private const val TIMEOUT_MS = 8_000L
    }
}

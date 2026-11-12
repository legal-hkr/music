package sh.hnet.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import sh.hnet.music.MainActivity
import sh.hnet.music.MusicApplication
import sh.hnet.music.R

/**
 * The Now playing widget (SPEC-widgets §3): the track Music would play now, as a bar (4×1), a
 * square (2×2) or wide (4×2), whichever the launcher's size takes. Plain RemoteViews (owner's
 * decision: no Glance, which brings WorkManager along).
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // After a restart of the phone or the process there is nothing to show yet: the app's widget
        // updater connects to the player, whose kept queue gives the last track, paused
        (context.applicationContext as MusicApplication).widgets.start()
    }

    /** What the widget shows; [title] null when nothing plays and nothing is queued. */
    class Content(val title: String?, val artist: String?, val cover: Bitmap?, val playing: Boolean)

    companion object {
        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))

        /** The largest cover a layout shows, in pixels: rendered once, at most at its displayed size (§2.4). */
        fun coverSize(context: Context): Int = (COVER_DP * context.resources.displayMetrics.density).toInt()

        /** Every placed widget shows [content], in the layout its size takes. */
        fun update(context: Context, content: Content) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            // Of the layouts that fit the widget's size the launcher shows the nearest one (the least distance
            // between the two sizes), so each is declared close to the sizes it is for. On the Pixel launcher
            // one row is about 110 dp high and two about 236; two, three and four cells are about 181, 280 and 379 dp wide
            val views = RemoteViews(
                mapOf(
                    SizeF(NARROW_DP, BAR_DP) to bar(context, content, cover = false),
                    SizeF(BAR_WIDTH_DP, BAR_DP) to bar(context, content, cover = true),
                    SizeF(SQUARE_DP, TWO_ROWS_DP) to square(context, content),
                    SizeF(WIDE_DP, TWO_ROWS_DP) to wide(context, content),
                ),
            )
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /** Two cells wide there is no room for the cover beside the text and the button. */
        private fun bar(context: Context, content: Content, cover: Boolean) = RemoteViews(context.packageName, R.layout.widget_now_playing_bar).apply {
            common(context, content, playing = R.drawable.widget_button_container_circle, paused = R.drawable.widget_button_container_rounded)
            setViewVisibility(R.id.widget_cover, if (cover) View.VISIBLE else View.GONE)
        }

        private fun square(context: Context, content: Content) = RemoteViews(context.packageName, R.layout.widget_now_playing_square).apply {
            common(context, content, playing = R.drawable.widget_button_primary_circle, paused = R.drawable.widget_button_primary_rounded)
            // With large text the cover gives way, so title and artist still fit (§2.3)
            val cover = if (context.resources.configuration.fontScale >= 1.3f) 104f else 112f
            setViewLayoutWidth(R.id.widget_cover, cover, TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.widget_cover, cover, TypedValue.COMPLEX_UNIT_DIP)
        }

        private fun wide(context: Context, content: Content) = RemoteViews(context.packageName, R.layout.widget_now_playing_wide).apply {
            common(context, content, playing = R.drawable.widget_button_primary_pill, paused = R.drawable.widget_button_primary_rounded_wide)
            setViewVisibility(R.id.widget_buttons, if (content.title == null) View.GONE else View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_previous, WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_PREVIOUS))
            setOnClickPendingIntent(R.id.widget_next, WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_NEXT))
        }

        /** What every layout has: the cover, two lines, Play/Pause (round while playing, a rounded square while paused) and the tap that opens Music. */
        private fun RemoteViews.common(context: Context, content: Content, playing: Int, paused: Int) {
            val empty = content.title == null
            if (content.cover != null) setImageViewBitmap(R.id.widget_cover, content.cover) else setImageViewResource(R.id.widget_cover, R.drawable.widget_cookie_note)
            val title = content.title ?: context.getString(R.string.label_widget_nothing_playing)
            val text = if (empty) context.getString(R.string.desc_widget_tap_to_open) else content.artist.orEmpty()
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_text, text)
            setViewVisibility(R.id.widget_text, if (text.isEmpty()) View.GONE else View.VISIBLE)
            // Nothing to play: no buttons, and the whole widget opens Music (§3.4)
            setViewVisibility(R.id.widget_play, if (empty) View.GONE else View.VISIBLE)
            setImageViewResource(R.id.widget_play, if (content.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            setInt(R.id.widget_play, "setBackgroundResource", if (content.playing) playing else paused)
            setContentDescription(R.id.widget_play, context.getString(if (content.playing) R.string.content_description_pause else R.string.content_description_play))
            setOnClickPendingIntent(R.id.widget_play, WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_PLAY_PAUSE))
            if (!empty) setContentDescription(R.id.widget_root, context.getString(R.string.content_description_widget_now_playing, title, text))
            setOnClickPendingIntent(R.id.widget_root, open(context, nowPlaying = !empty))
        }

        /** Opens Music: on Now playing, or wherever it is (the Library, or the first run) when nothing plays. */
        private fun open(context: Context, nowPlaying: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (nowPlaying) intent.putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
            return PendingIntent.getActivity(context, if (nowPlaying) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private const val COVER_DP = 116

        /** Two cells and up: every layout's least width. */
        private const val NARROW_DP = 110f

        /** The bar with its cover: three cells and up. */
        private const val BAR_WIDTH_DP = 250f

        /** Any height. */
        private const val BAR_DP = 50f

        /** Well over one row, under two. */
        private const val TWO_ROWS_DP = 200f

        /** Two cells and three: nearer to three cells and two rows than the bar is. */
        private const val SQUARE_DP = 150f

        /** Padding, cover, gap, the three buttons with their gaps, padding: four cells, not three (those get the square). */
        private const val WIDE_DP = 14f + 116f + 14f + 156f + 14f
    }
}

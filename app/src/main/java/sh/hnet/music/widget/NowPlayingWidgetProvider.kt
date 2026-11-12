package sh.hnet.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import sh.hnet.music.MainActivity
import sh.hnet.music.MusicApplication
import sh.hnet.music.R

/**
 * The Now playing widget (SPEC-widgets §3): the track Music would play now. Two widgets in the
 * launcher's list, each of a fixed width (owner's decision, as for the Playlist widgets: three
 * cells by two rows look wrong): this one two cells wide – a bar (2×1) or a square (2×2) – and
 * [NowPlayingWideWidgetProvider] four cells wide. Floating (SPEC-widgets rev. 2 §2.1): no widget
 * background – the round cover as tall as the widget straight on the wallpaper, the text in a small
 * label, the buttons on their own, each with a shadow (elevation in the layouts). Plain RemoteViews
 * (owner's decision: no Glance, which brings WorkManager along).
 */
open class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // After a restart of the phone or the process there is nothing to show yet: the app's widget
        // updater connects to the player, whose kept queue gives the last track, paused
        (context.applicationContext as MusicApplication).widgets.start()
    }

    /** What the widget shows; [title] null when nothing plays and nothing is queued. */
    class Content(val title: String?, val artist: String?, val cover: Bitmap?, val playing: Boolean)

    companion object {
        /** The placed widgets of both widths. */
        fun ids(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java)) +
                manager.getAppWidgetIds(ComponentName(context, NowPlayingWideWidgetProvider::class.java))
        }

        /** The largest cover a layout shows, in pixels: the cover is as tall as the widget, two rows at most (§2.5). */
        fun coverSize(context: Context): Int = (COVER_DP * context.resources.displayMetrics.density).toInt()

        /** Every placed widget shows [content], in the layout its size takes. */
        fun update(context: Context, content: Content) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            // Of the layouts that fit the widget's size the launcher shows the nearest one (the least distance
            // between the two sizes), so each is declared close to the sizes it is for. On the Pixel launcher
            // one row is about 110 dp high and two about 236; two and four cells are about 181 and 379 dp wide
            val views = RemoteViews(
                mapOf(
                    SizeF(NARROW_DP, BAR_DP) to bar(context, content, wide = false),
                    SizeF(BAR_WIDTH_DP, BAR_DP) to bar(context, content, wide = true),
                    SizeF(SQUARE_DP, TWO_ROWS_DP) to square(context, content),
                    SizeF(WIDE_DP, TWO_ROWS_DP) to wide(context, content),
                ),
            )
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /**
         * One row (§3.3). Four cells wide: the cover, the label over its end, Play/Pause at the end. Two cells
         * wide there is room for the cover and the button only; nothing playing, for the cover and the label.
         */
        private fun bar(context: Context, content: Content, wide: Boolean) = RemoteViews(context.packageName, R.layout.widget_floating_bar).apply {
            val empty = content.title == null
            common(context, content, artist = wide)
            setViewVisibility(R.id.widget_label, if (wide || empty) View.VISIBLE else View.GONE)
            // The label hugs its text, up to the room between the cover and the button
            val room = ((if (wide) WIDE_BAR_LABEL_DP else NARROW_BAR_LABEL_DP) * context.resources.displayMetrics.density).toInt()
            setInt(R.id.widget_title, "setMaxWidth", room)
            setInt(R.id.widget_text, "setMaxWidth", room)
        }

        /** Two rows, two cells (§3.2): the title alone in the label. */
        private fun square(context: Context, content: Content) = RemoteViews(context.packageName, R.layout.widget_now_playing_square).apply {
            common(context, content, artist = false)
        }

        /** Two rows, four cells (§3.1). */
        private fun wide(context: Context, content: Content) = RemoteViews(context.packageName, R.layout.widget_now_playing_wide).apply {
            common(context, content, artist = true)
            setViewVisibility(R.id.widget_buttons, if (content.title == null) View.GONE else View.VISIBLE)
            // In the app's language: what a layout says by itself is in the launcher's
            setContentDescription(R.id.widget_previous, context.getString(R.string.content_description_previous))
            setContentDescription(R.id.widget_next, context.getString(R.string.content_description_next))
            setOnClickPendingIntent(R.id.widget_previous, WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_PREVIOUS))
            setOnClickPendingIntent(R.id.widget_next, WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_NEXT))
            // Play/Pause is wider than high here: its ends fully rounded while playing
            if (content.playing) setInt(R.id.widget_play, "setBackgroundResource", R.drawable.widget_button_primary_pill)
        }

        /**
         * What every layout has: the cover, the label, Play/Pause (round while playing, a rounded square while
         * paused) and the tap that opens Music. [artist]: whether the label has a second line.
         */
        private fun RemoteViews.common(context: Context, content: Content, artist: Boolean) {
            val empty = content.title == null
            if (content.cover != null) setImageViewBitmap(R.id.widget_cover, content.cover) else setImageViewResource(R.id.widget_cover, R.drawable.widget_circle_note)
            val title = content.title ?: context.getString(R.string.label_widget_nothing_playing)
            val text = if (empty) context.getString(R.string.desc_widget_tap_to_open) else content.artist.orEmpty()
            setTextViewText(R.id.widget_title, title)
            if (artist) {
                setTextViewText(R.id.widget_text, text)
                setViewVisibility(R.id.widget_text, if (text.isEmpty()) View.GONE else View.VISIBLE)
            }
            // Nothing to play: no buttons, and the whole widget opens Music (§3.4)
            setViewVisibility(R.id.widget_play, if (empty) View.GONE else View.VISIBLE)
            setImageViewResource(R.id.widget_play, if (content.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            setInt(R.id.widget_play, "setBackgroundResource", if (content.playing) R.drawable.widget_button_primary_circle else R.drawable.widget_button_primary_rounded)
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

        private const val COVER_DP = 240

        /** Two cells and up: every layout's least width. */
        private const val NARROW_DP = 110f

        /** The bar with its label: four cells. */
        private const val BAR_WIDTH_DP = 250f

        /** Any height. */
        private const val BAR_DP = 50f

        /** Well over one row, under two. */
        private const val TWO_ROWS_DP = 200f
        private const val SQUARE_DP = 150f

        /** Four cells. */
        private const val WIDE_DP = 300f

        /** The room for the bar's label: four cells less the cover and the button; two cells less the cover. */
        private const val WIDE_BAR_LABEL_DP = 170
        private const val NARROW_BAR_LABEL_DP = 62
    }
}

/** The Now playing widget four cells wide: a bar with the cover (4×1) or the wide form with all three buttons (4×2). */
class NowPlayingWideWidgetProvider : NowPlayingWidgetProvider()

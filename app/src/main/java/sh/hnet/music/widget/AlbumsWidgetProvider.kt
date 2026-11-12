package sh.hnet.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import sh.hnet.music.MainActivity
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.library.Album

/**
 * The Most played albums widget (SPEC-widgets §4; "Recent albums" there – the owner's change, as
 * Music keeps play counts and no history): the covers of the albums listened to most, fanned out
 * straight on the wallpaper. Five of them four cells wide, three when narrower; the whole widget
 * opens Music.
 */
class AlbumsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as MusicApplication).widgets.start(redraw = true)
    }

    companion object {
        /** As many albums as the widest fan shows. */
        const val ALBUMS = 5
        private const val NARROW_ALBUMS = 3

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AlbumsWidgetProvider::class.java))

        /** The side of a cover in the wide fan, in pixels: covers are loaded at most that large. */
        fun coverSize(context: Context): Int = (WIDE_CARD_DP * context.resources.displayMetrics.density).toInt()

        /** Every placed widget shows [albums], most played first, with their [covers] (null where an album has none). */
        fun update(context: Context, albums: List<Album>, covers: List<Bitmap?>) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val density = context.resources.displayMetrics.density
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            // The wallpaper can't be read: the system's dark theme decides how strong the shadow is (§4.1)
            fun fan(width: Int, height: Int, card: Int, step: Int) = WidgetBitmaps.Fan(
                width = (width * density).toInt(),
                height = (height * density).toInt(),
                card = card * density,
                step = step * density,
                radius = 14 * density,
                drop = 6 * density,
                shadowBlur = (if (dark) 16 else 12) * density,
                shadowOffset = (if (dark) 6 else 4) * density,
                shadowAlpha = if (dark) 115 else 71,
            )
            val placeholder = context.getColor(R.color.widget_placeholder)
            val note = ContextCompat.getDrawable(context, R.drawable.widget_ic_music_note)
            // Nothing played yet: three plain cards, a note on the middle one (§4.2)
            val cards = if (albums.isEmpty()) listOf<Bitmap?>(null, null, null) else covers
            val description =
                if (albums.isEmpty()) context.getString(R.string.content_description_widget_no_albums)
                else context.getString(R.string.content_description_widget_most_played, albums.take(NARROW_ALBUMS).joinToString(", ") { it.title })
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            fun views(picture: Bitmap) = RemoteViews(context.packageName, R.layout.widget_albums).apply {
                setImageViewBitmap(R.id.widget_cover, picture)
                setContentDescription(android.R.id.background, description)
                setOnClickPendingIntent(android.R.id.background, open)
            }
            // Each picture has room for the tilted corners and the shadows; the launcher fits it into the widget
            val narrow = views(WidgetBitmaps.fan(cards.take(NARROW_ALBUMS), fan(196, 156, NARROW_CARD_DP, 30), placeholder, note.takeIf { albums.isEmpty() }))
            val wide = views(WidgetBitmaps.fan(cards, fan(372, 210, WIDE_CARD_DP, 46), placeholder, note.takeIf { albums.isEmpty() }))
            // Two and three cells get the fan of three, four cells the fan of five
            AppWidgetManager.getInstance(context).updateAppWidget(ids, RemoteViews(mapOf(SizeF(110f, 150f) to narrow, SizeF(300f, 150f) to wide)))
        }

        private const val WIDE_CARD_DP = 120
        private const val NARROW_CARD_DP = 92
    }
}

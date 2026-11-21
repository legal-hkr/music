package sh.hnet.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.net.toUri
import java.text.NumberFormat
import sh.hnet.music.MainActivity
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.data.LibraryTrack
import sh.hnet.music.library.PlaylistSummary
import sh.hnet.music.ui.components.durationText

/**
 * The playlist each placed Playlist widget shows, by widget id. In its own preferences and in no
 * backup: widget ids don't survive a reinstall (§7).
 */
class PlaylistWidgetStore(private val preferences: SharedPreferences) {
    fun get(widgetId: Int): Long? = preferences.getLong("playlist_$widgetId", -1).takeIf { it >= 0 }

    fun put(widgetId: Int, playlistId: Long) = preferences.edit { putLong("playlist_$widgetId", playlistId) }

    /** After playlists got new ids (a backup was restored): each of [widgetIds] goes on with the playlist its old one became, by [moved]. */
    fun follow(widgetIds: IntArray, moved: Map<Long, Long>) = preferences.edit {
        for (widgetId in widgetIds) {
            val old = preferences.getLong("playlist_$widgetId", -1)
            moved[old]?.let { putLong("playlist_$widgetId", it) }
        }
    }

    fun remove(widgetId: Int) = preferences.edit { remove("playlist_$widgetId") }

    /** Drops what earlier versions kept about the looks with a background, which are gone. Once is enough, and costs nothing later. */
    fun forgetLooks() {
        val old = preferences.all.keys.filter { it.startsWith("look_") || it.startsWith("now_playing_look_") }
        if (old.isNotEmpty()) preferences.edit { old.forEach(::remove) }
    }
}

/**
 * The Playlist widget (SPEC-widgets rev. 2 §5): one playlist per widget, floating as the Now playing
 * widget – no widget background, the mosaic in the slanted shape, the text in a label. Two widgets in
 * the launcher's list, each of a fixed width (owner's decision: three cells by two rows look wrong,
 * and a single size can't be taken out of a resizable widget): this one two cells wide – a bar (2×1)
 * that plays on a tap, or a square (2×2) – and [PlaylistWideWidgetProvider] four cells wide.
 */
open class PlaylistWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // The app's widget updater draws them once the playlists are read
        (context.applicationContext as MusicApplication).widgets.start(redraw = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = (context.applicationContext as MusicApplication).playlistWidgets
        appWidgetIds.forEach(store::remove)
    }

    companion object {
        /** The placed widgets of both widths. */
        fun ids(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, PlaylistWidgetProvider::class.java)) +
                manager.getAppWidgetIds(ComponentName(context, PlaylistWideWidgetProvider::class.java))
        }

        /** Widget [id] shows [summary] ([summary] null: its playlist was deleted). [pictures] loads a track's cover. */
        suspend fun update(context: Context, id: Int, summary: PlaylistSummary?, pictures: Pictures) {
            val views = when {
                summary == null -> message(context, id, null)
                summary.tracks.isEmpty() -> message(context, id, summary)
                else -> playlist(context, summary, pictures)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }

        /** What a widget's content depends on: drawn again only when this changes (§2.4). */
        fun key(summary: PlaylistSummary?): Any? =
            summary?.let { listOf(it.playlist.name, it.tracks.size, it.durationMs, it.coverTracks.map { track -> track.documentUri to track.folderCover }) }

        /** Loads covers at the size a widget shows them. */
        fun interface Pictures {
            suspend fun cover(track: LibraryTrack, size: Int): Bitmap?
        }

        private suspend fun playlist(context: Context, summary: PlaylistSummary, pictures: Pictures): RemoteViews {
            val size = (PICTURE_DP * context.resources.displayMetrics.density).toInt()
            val tracks = summary.coverTracks
            val covers = tracks.map { if (it.hasCover) pictures.cover(it, if (tracks.size == 4) size / 2 else size) else null }
            // A tile whose track has no cover: plain, in the system's tone
            val picture = WidgetBitmaps.shaped(WidgetBitmaps.mosaic(covers, size, context.getColor(R.color.widget_secondary_container)), size)
            val name = summary.playlist.name
            val count = context.resources.getQuantityString(R.plurals.label_track_count, summary.tracks.size, NumberFormat.getIntegerInstance().format(summary.tracks.size))
            val length = context.getString(R.string.label_playlist_summary, count, durationText(context.resources, summary.durationMs))
            val playlistId = summary.playlist.id
            val open = open(context, playlistId)
            val play = WidgetActionReceiver.playlist(context, playlistId, shuffle = false)
            val shuffle = WidgetActionReceiver.playlist(context, playlistId, shuffle = true)
            fun bar(wide: Boolean) = floatingBar(context, wide).apply {
                setImageViewBitmap(R.id.widget_cover, picture)
                setTextViewText(R.id.widget_title, name)
                setTextViewText(R.id.widget_text, length)
                setViewVisibility(R.id.widget_text, if (wide) View.VISIBLE else View.GONE)
                setContentDescription(R.id.widget_root, context.getString(R.string.content_description_widget_play_playlist, name))
                // The whole bar plays (§5.2)
                setOnClickPendingIntent(R.id.widget_root, play)
            }
            // The picture, Play (a star for Starred, which plays as well) over it, the name in a label
            val square = RemoteViews(context.packageName, R.layout.widget_playlist_floating_square).apply {
                setImageViewBitmap(R.id.widget_cover, picture)
                setTextViewText(R.id.widget_title, name)
                setImageViewResource(R.id.widget_play, if (summary.starred) R.drawable.ic_star_filled else R.drawable.ic_play_arrow)
                setInt(R.id.widget_play, "setBackgroundResource", R.drawable.widget_button_primary_circle)
                setContentDescription(R.id.widget_play, context.getString(R.string.content_description_widget_play_playlist, name))
                setOnClickPendingIntent(R.id.widget_play, play)
                setContentDescription(R.id.widget_root, context.getString(R.string.content_description_widget_playlist, name, count))
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            val wide = RemoteViews(context.packageName, R.layout.widget_playlist_floating_wide).apply {
                setImageViewBitmap(R.id.widget_cover, picture)
                // In the app's language: what a layout says by itself is in the launcher's
                setTextViewText(R.id.widget_play_label, context.getString(R.string.content_description_play))
                setTextViewText(R.id.widget_title, name)
                setTextViewText(R.id.widget_text, length)
                setContentDescription(R.id.widget_play, context.getString(R.string.content_description_widget_play_playlist, name))
                setOnClickPendingIntent(R.id.widget_play, play)
                setContentDescription(R.id.widget_shuffle, context.getString(R.string.content_description_widget_shuffle_playlist, name))
                setOnClickPendingIntent(R.id.widget_shuffle, shuffle)
                setContentDescription(R.id.widget_root, context.getString(R.string.content_description_widget_playlist, name, length))
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            return sized(bar(false), bar(true), square, wide)
        }

        /**
         * The deleted playlist ([summary] null: "Playlist deleted", a tap opens the widget's settings to
         * choose another) and the empty one (its name, "No tracks yet", a tap opens it). No Play (§5.5).
         */
        private fun message(context: Context, id: Int, summary: PlaylistSummary?): RemoteViews {
            val title = summary?.playlist?.name ?: context.getString(R.string.label_widget_playlist_deleted)
            val text = context.getString(if (summary == null) R.string.desc_widget_choose_another else R.string.label_playlist_no_tracks)
            val picture = if (summary == null) R.drawable.widget_shape_deleted else R.drawable.widget_shape_empty
            val tap = summary?.let { open(context, it.playlist.id) } ?: configure(context, id)
            fun RemoteViews.common(shown: String = title) {
                setImageViewResource(R.id.widget_cover, picture)
                setTextViewText(R.id.widget_title, shown)
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            // The placeholder and a label (rev. 2 §5.5); two cells by one row have room for a word
            fun bar(wide: Boolean) = floatingBar(context, wide).apply {
                common(if (summary == null && !wide) context.getString(R.string.label_widget_deleted_short) else title)
                setTextViewText(R.id.widget_text, text)
                setViewVisibility(R.id.widget_text, if (wide) View.VISIBLE else View.GONE)
            }
            val square = RemoteViews(context.packageName, R.layout.widget_playlist_floating_square).apply {
                common()
                setViewVisibility(R.id.widget_play, View.GONE)
            }
            val wide = RemoteViews(context.packageName, R.layout.widget_playlist_floating_wide).apply {
                common()
                setTextViewText(R.id.widget_text, text)
                setViewVisibility(R.id.widget_buttons, View.GONE)
            }
            return sized(bar(false), bar(true), square, wide)
        }

        /** The floating bar without Play/Pause (that is Now playing's), its label as wide as the room after the picture. */
        private fun floatingBar(context: Context, wide: Boolean) = RemoteViews(context.packageName, R.layout.widget_floating_bar).apply {
            setViewVisibility(R.id.widget_play, View.GONE)
            val room = ((if (wide) 230 else 76) * context.resources.displayMetrics.density).toInt()
            setInt(R.id.widget_title, "setMaxWidth", room)
            setInt(R.id.widget_text, "setMaxWidth", room)
        }

        /** As the Now playing widget: each layout declared near the sizes it is for, as the launcher takes the nearest that fits. */
        private fun sized(bar: RemoteViews, wideBar: RemoteViews, square: RemoteViews, wide: RemoteViews) =
            RemoteViews(mapOf(SizeF(110f, 50f) to bar, SizeF(250f, 50f) to wideBar, SizeF(150f, 200f) to square, SizeF(WIDE_DP, 200f) to wide))

        private fun open(context: Context, playlistId: Long): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Told apart by its address: extras alone don't make two pending intents differ
                .setData("music-widget://playlist/$playlistId".toUri())
                .putExtra(MainActivity.EXTRA_OPEN_PLAYLIST, playlistId)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun configure(context: Context, id: Int): PendingIntent {
            val intent = Intent(context, PlaylistWidgetConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData("music-widget://configure/$id".toUri())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** The picture is drawn once, at the largest size a layout shows it. */
        private const val PICTURE_DP = 236

        /** Padding, picture, gap, Play and Shuffle, padding: four cells; three get the square. */
        private const val WIDE_DP = 300f
    }
}

/** The Playlist widget four cells wide: a bar (4×1) or the wide form (4×2). Everything else is [PlaylistWidgetProvider]'s. */
class PlaylistWideWidgetProvider : PlaylistWidgetProvider()

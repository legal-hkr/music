package sh.hnet.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.net.toUri
import sh.hnet.music.MainActivity
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.library.PlaylistSummary
import java.text.NumberFormat

/** How a Playlist widget looks (SPEC-widgets §5.1, §5.2). */
enum class PlaylistLook { COVERS, SYSTEM }

/**
 * What each placed Playlist widget shows: a playlist and a look, by widget id. In its own
 * preferences and in no backup: widget ids don't survive a reinstall (§7).
 */
class PlaylistWidgetStore(private val preferences: SharedPreferences) {
    class Choice(val playlistId: Long, val look: PlaylistLook)

    fun get(widgetId: Int): Choice? {
        val playlist = preferences.getLong("playlist_$widgetId", -1).takeIf { it >= 0 } ?: return null
        val look = PlaylistLook.entries.firstOrNull { it.name == preferences.getString("look_$widgetId", null) } ?: PlaylistLook.COVERS
        return Choice(playlist, look)
    }

    fun put(widgetId: Int, choice: Choice) = preferences.edit {
        putLong("playlist_$widgetId", choice.playlistId)
        putString("look_$widgetId", choice.look.name)
    }

    fun remove(widgetId: Int) = preferences.edit {
        remove("playlist_$widgetId")
        remove("look_$widgetId")
    }
}

/**
 * The Playlist widget (SPEC-widgets §5): one playlist per widget, in the Covers look or in system
 * colors. Two widgets in the launcher's list, each of a fixed width (owner's decision: three cells
 * by two rows suit neither look, and a single size can't be taken out of a resizable widget): this
 * one two cells wide – a bar (2×1) that plays on a tap, or a square (2×2) – and
 * [PlaylistWideWidgetProvider] four cells wide. Starred always takes the system form, the square
 * on primaryContainer with a star.
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

        /**
         * Widget [id] shows [summary] ([summary] null: its playlist was deleted) in [look].
         * [pictures] loads a track's cover. Returns what was drawn, to tell whether it has to be drawn again.
         */
        suspend fun update(context: Context, id: Int, look: PlaylistLook, summary: PlaylistSummary?, pictures: Pictures) {
            val views = when {
                summary == null -> message(context, id, null)
                summary.tracks.isEmpty() -> message(context, id, summary)
                else -> playlist(context, id, look, summary, pictures)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }

        /** What a widget's content depends on: drawn again only when this changes (§2.4). */
        fun key(look: PlaylistLook, summary: PlaylistSummary?): Any? =
            summary?.let { listOf(look, it.playlist.name, it.tracks.size, it.durationMs, it.coverTracks.map { track -> track.documentUri }) }

        /** Loads covers at the size a widget shows them. */
        fun interface Pictures {
            suspend fun cover(track: sh.hnet.music.data.LibraryTrack, size: Int): Bitmap?
        }

        private suspend fun playlist(context: Context, id: Int, look: PlaylistLook, summary: PlaylistSummary, pictures: Pictures): RemoteViews {
            val density = context.resources.displayMetrics.density
            val size = (PICTURE_DP * density).toInt()
            val tracks = summary.coverTracks
            val covers = tracks.map { if (it.hasEmbeddedCover) pictures.cover(it, if (tracks.size == 4) size / 2 else size) else null }
            val system = look == PlaylistLook.SYSTEM || summary.starred
            val role = context.getColor(R.color.widget_secondary_container)
            val colors = CoverColors.of(covers.filterNotNull(), role)
            // A tile whose track has no cover: plain, in the system's tone or in the picture's own
            val mosaic = WidgetBitmaps.mosaic(covers, size, if (system) role else colors.chip)
            val name = summary.playlist.name
            val count = context.resources.getQuantityString(R.plurals.label_track_count, summary.tracks.size, NumberFormat.getIntegerInstance().format(summary.tracks.size))
            val length = context.getString(R.string.label_playlist_summary, count, duration(context, summary.durationMs))
            val playlistId = summary.playlist.id
            val open = open(context, playlistId)
            val play = WidgetActionReceiver.playlist(context, playlistId, shuffle = false)
            val shuffle = WidgetActionReceiver.playlist(context, playlistId, shuffle = true)
            val round = WidgetBitmaps.circle(mosaic, (BAR_PICTURE_DP * density).toInt())
            fun bar(wide: Boolean) = RemoteViews(context.packageName, R.layout.widget_playlist_bar).apply {
                setImageViewBitmap(R.id.widget_cover, round)
                setTextViewText(R.id.widget_title, name)
                setTextViewText(R.id.widget_text, length)
                setViewVisibility(R.id.widget_text, if (wide) View.VISIBLE else View.GONE)
                setContentDescription(R.id.widget_root, context.getString(R.string.content_description_widget_play_playlist, name))
                // The whole bar plays (§5.3)
                setOnClickPendingIntent(R.id.widget_root, play)
            }
            val cookie = if (system) WidgetBitmaps.cookie(mosaic, size) else null
            val square = when {
                cookie != null -> RemoteViews(context.packageName, if (summary.starred) R.layout.widget_playlist_starred else R.layout.widget_playlist_square).apply {
                    setImageViewBitmap(R.id.widget_cover, cookie)
                    setTextViewText(R.id.widget_title, name)
                }
                else -> RemoteViews(context.packageName, R.layout.widget_playlist_covers_square).apply {
                    setImageViewBitmap(R.id.widget_cover, mosaic)
                    setImageViewBitmap(R.id.widget_scrim, WidgetBitmaps.scrim(colors.panel))
                    setTextViewText(R.id.widget_title, name)
                    setTextViewText(R.id.widget_text, count)
                    setTextColor(R.id.widget_title, colors.onPanel)
                    setTextColor(R.id.widget_text, colors.onPanel)
                    setColorStateList(R.id.widget_play, "setBackgroundTintList", ColorStateList.valueOf(colors.onPanel))
                    setInt(R.id.widget_play, "setColorFilter", colors.panel)
                }
            }.apply {
                setContentDescription(R.id.widget_play, context.getString(R.string.content_description_widget_play_playlist, name))
                setOnClickPendingIntent(R.id.widget_play, play)
                setContentDescription(R.id.widget_root, context.getString(R.string.content_description_widget_playlist, name, count))
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            val wide = when {
                cookie != null -> RemoteViews(context.packageName, R.layout.widget_playlist_wide).apply {
                    setImageViewBitmap(R.id.widget_cover, cookie)
                    // In the app's language: what a layout says by itself is in the launcher's
                    setTextViewText(R.id.widget_play_label, context.getString(R.string.content_description_play))
                }
                else -> RemoteViews(context.packageName, R.layout.widget_playlist_covers_wide).apply {
                    setImageViewBitmap(R.id.widget_cover, mosaic)
                    setImageViewBitmap(R.id.widget_fade, WidgetBitmaps.fade(colors.panel))
                    setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(colors.panel))
                    setTextViewText(R.id.widget_label, context.getString(R.string.label_widget_playlist))
                    setTextColor(R.id.widget_label, colors.onPanelMuted)
                    setTextColor(R.id.widget_title, colors.onPanel)
                    setTextColor(R.id.widget_text, colors.onPanelMuted)
                    setColorStateList(R.id.widget_play, "setBackgroundTintList", ColorStateList.valueOf(colors.accent))
                    setInt(R.id.widget_play, "setColorFilter", colors.onAccent)
                    setColorStateList(R.id.widget_shuffle, "setBackgroundTintList", ColorStateList.valueOf(colors.chip))
                    setInt(R.id.widget_shuffle, "setColorFilter", colors.onPanel)
                }
            }.apply {
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
            val picture = if (summary == null) R.drawable.widget_cookie_deleted else R.drawable.widget_cookie_empty
            val tap = summary?.let { open(context, it.playlist.id) } ?: configure(context, id)
            fun RemoteViews.common() {
                setImageViewResource(R.id.widget_cover, picture)
                setTextViewText(R.id.widget_title, title)
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            fun bar(wide: Boolean) = RemoteViews(context.packageName, R.layout.widget_playlist_bar).apply {
                common()
                setTextViewText(R.id.widget_text, text)
                setViewVisibility(R.id.widget_text, if (wide) View.VISIBLE else View.GONE)
            }
            val square = RemoteViews(context.packageName, R.layout.widget_playlist_square).apply {
                common()
                setViewVisibility(R.id.widget_play, View.GONE)
                setTextViewText(R.id.widget_text, text)
                setViewVisibility(R.id.widget_text, View.VISIBLE)
            }
            val wide = RemoteViews(context.packageName, R.layout.widget_playlist_wide).apply {
                common()
                setTextViewText(R.id.widget_text, text)
                setViewVisibility(R.id.widget_buttons, View.GONE)
            }
            return sized(bar(false), bar(true), square, wide)
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

        /** "1 h 14 min", "52 min", as the playlist's page has it. */
        private fun duration(context: Context, ms: Long): String {
            val minutes = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(1)
            return if (minutes >= 60) context.getString(R.string.label_duration_hm, minutes / 60, minutes % 60) else context.getString(R.string.label_duration_m, minutes)
        }

        /** The picture is drawn once, at the largest size a layout shows it. */
        private const val PICTURE_DP = 236
        private const val BAR_PICTURE_DP = 52

        /** Padding, picture, gap, Play and Shuffle, padding: four cells; three get the square. */
        private const val WIDE_DP = 300f
    }
}

/** The Playlist widget four cells wide: a bar (4×1) or the wide form (4×2). Everything else is [PlaylistWidgetProvider]'s. */
class PlaylistWideWidgetProvider : PlaylistWidgetProvider()

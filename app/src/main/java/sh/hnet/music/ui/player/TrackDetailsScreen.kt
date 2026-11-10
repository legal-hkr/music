@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.music.ui.player

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.music.R
import sh.hnet.music.data.Track
import sh.hnet.music.ui.components.SettingsGroup
import sh.hnet.music.ui.components.SettingsGroupScope
import sh.hnet.music.ui.components.SettingsPage
import sh.hnet.music.ui.components.formatDuration
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Everything known about the current file (SPEC §9.5); read-only, values selectable. */
@Composable
fun TrackDetailsScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val track by viewModel.details.collectAsStateWithLifecycle()
    // A Surface for the content color as well as the background (see NowPlayingScreen)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    ) {
        // SettingsPage draws its own status-bar inset through its top bar; here the overlay already did
        SettingsPage(title = stringResource(R.string.title_track_details), onBack = onBack, topInsets = false) {
            val shown = track ?: return@SettingsPage
            val folder by remember(shown.folderUri) { viewModel.folderName(shown.folderUri) }.collectAsStateWithLifecycle(null)
            val context = LocalContext.current
            val locale = LocalConfiguration.current.locales[0]
            SettingsGroup(stringResource(R.string.title_details_file)) {
                detail(R.string.label_details_folder, folder)
                detail(R.string.label_details_path, shown.relativePath)
                detail(R.string.label_details_size, Formatter.formatShortFileSize(context, shown.size))
                detail(R.string.label_details_modified, shown.lastModified.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(it)) })
            }
            val codec = if (shown.bitsPerSample != null) "${techLabel(shown)}, ${channelsText(shown.channels)}" else "${codecName(shown.codec)}, ${channelsText(shown.channels)}"
            val bitrate = shown.bitrateKbps?.let { stringResource(R.string.label_tech_kbps, NumberFormat.getIntegerInstance(locale).format(it)) }
            SettingsGroup(stringResource(R.string.title_details_format)) {
                detail(R.string.label_details_codec, codec)
                detail(R.string.label_details_bitrate, bitrate)
                detail(R.string.label_details_duration, shown.durationMs.takeIf { it > 0 }?.let(::formatDuration))
            }
            val of = @Composable { n: Int?, total: Int? -> n?.let { if (total != null) stringResource(R.string.label_of, it, total) else it.toString() } }
            val trackOf = of(shown.trackNumber, shown.trackTotal)
            val discOf = of(shown.discNumber, shown.discTotal)
            val yes = stringResource(R.string.label_yes)
            SettingsGroup(stringResource(R.string.title_details_tags)) {
                detail(R.string.label_details_title, shown.title)
                detail(R.string.label_details_artists, shown.artists.joinToString(", ").ifEmpty { null })
                detail(R.string.label_details_album, shown.album)
                detail(R.string.label_details_album_artists, shown.albumArtists.joinToString(", ").ifEmpty { null })
                detail(R.string.label_details_track, trackOf)
                detail(R.string.label_details_disc, discOf)
                detail(R.string.label_details_date, shown.date ?: shown.year?.toString())
                detail(R.string.label_details_genres, shown.genres.joinToString(", ").ifEmpty { null })
                detail(R.string.label_details_composers, shown.composers.joinToString(", ").ifEmpty { null })
                detail(R.string.label_details_compilation, if (shown.compilation) yes else null)
            }
            val number = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
            val peak = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 3; maximumFractionDigits = 6 }
            fun gain(db: Float?) = db?.let { (if (it < 0) "−" else "") + number.format(kotlin.math.abs(it)) + " dB" }
            val values = listOf(shown.trackGainDb, shown.trackPeak, shown.albumGainDb, shown.albumPeak)
            val none = stringResource(R.string.msg_no_replaygain)
            SettingsGroup(stringResource(R.string.title_details_replaygain)) {
                if (values.all { it == null }) {
                    row { shapes -> DetailRow(shapes, none, null) }
                } else {
                    detail(R.string.label_details_track_gain, gain(shown.trackGainDb))
                    detail(R.string.label_details_track_peak, shown.trackPeak?.let { peak.format(it) })
                    detail(R.string.label_details_album_gain, gain(shown.albumGainDb))
                    detail(R.string.label_details_album_peak, shown.albumPeak?.let { peak.format(it) })
                }
            }
        }
    }
    }
}

/** A row only when there's a value: missing fields are left out (SPEC §9.5). */
private fun SettingsGroupScope.detail(label: Int, value: String?) {
    if (value.isNullOrEmpty()) return
    row { shapes -> DetailRow(shapes, stringResource(label), value) }
}

@Composable
private fun DetailRow(shapes: ListItemShapes, label: String, value: String?) {
    Surface(shape = shapes.shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (value == null) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}


package sh.hnet.music.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import sh.hnet.music.R

/** Navigation bar destinations; each keeps its own back stack. */
enum class Tab(val root: Screen, @param:StringRes val label: Int, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int) {
    LIBRARY(Screen.Library, R.string.label_nav_library, R.drawable.ic_library_music, R.drawable.ic_library_music_filled),
    SEARCH(Screen.Search, R.string.label_nav_search, R.drawable.ic_search, R.drawable.ic_search_filled),
    SETTINGS(Screen.Settings, R.string.label_nav_settings, R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Library : Screen
    @Serializable data class Album(val key: String) : Screen
    @Serializable data class Artist(val name: String) : Screen
    @Serializable data class ArtistTracks(val name: String) : Screen
    @Serializable data object Search : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsLibrary : Screen
    @Serializable data class SettingsFolder(val treeUri: String) : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsPlayback : Screen
    @Serializable data object SettingsLicences : Screen
}

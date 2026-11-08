@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import sh.hnet.music.MusicApplication
import sh.hnet.music.R
import sh.hnet.music.ui.components.formatCount
import sh.hnet.music.ui.library.AlbumScreen
import sh.hnet.music.ui.library.ArtistScreen
import sh.hnet.music.ui.library.BrowseViewModel
import sh.hnet.music.ui.library.LibraryScreen
import sh.hnet.music.ui.search.SearchScreen
import sh.hnet.music.ui.settings.AboutScreen
import sh.hnet.music.ui.settings.FolderScreen
import sh.hnet.music.ui.settings.LibraryMessage
import sh.hnet.music.ui.settings.LibraryMessages
import sh.hnet.music.ui.settings.LibrarySettingsScreen
import sh.hnet.music.ui.settings.LibraryViewModel
import sh.hnet.music.ui.settings.SettingsRootScreen

/** The app's shell: one back stack per navigation bar destination, the bottom block, snackbars. */
@Composable
fun MusicApp() {
    val app = LocalContext.current.applicationContext as MusicApplication
    val library: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val browse: BrowseViewModel = viewModel { BrowseViewModel(app) }
    val summary by library.summary.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    val stacks: Map<Tab, NavBackStack<NavKey>> = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    // Kept here, outside the tab's content, so a tab's pages keep their state while another tab shows
    val decorators = Tab.entries.associateWith { rememberSaveableStateHolderNavEntryDecorator<NavKey>() }
    val stack = stacks.getValue(tab)

    // A destination's root goes back to Library, Library's root leaves the app (§3.1)
    BackHandler(enabled = tab != Tab.LIBRARY && stack.size == 1) { tab = Tab.LIBRARY }

    val snackbars = remember { SnackbarHostState() }
    val alreadyAdded = stringResource(R.string.msg_folder_already_added)
    LibraryMessages(library) { message ->
        when (message) {
            is LibraryMessage.AlreadyAdded -> snackbars.showSnackbar(
                AnnotatedString.fromHtml(alreadyAdded.format(message.name.htmlEncode())).text,
            )
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        },
        bottomBar = {
            Column {
                // The mini player joins here in S4
                ShortNavigationBar {
                    Tab.entries.forEach { item ->
                        val selected = item == tab
                        val label = stringResource(item.label)
                        val scanning = item == Tab.SETTINGS && summary.scanning > 0
                        val scanningDescription = if (scanning) {
                            pluralStringResource(R.plurals.content_description_settings_scanning, summary.scanning, summary.scanning)
                        } else null
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    // Re-selecting a destination returns to its root
                                    while (stack.size > 1) stack.removeAt(stack.lastIndex)
                                } else {
                                    tab = item
                                }
                            },
                            icon = {
                                BadgedBox(badge = { if (scanning) Badge { Text(formatCount(summary.scanning)) } }) {
                                    Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null)
                                }
                            },
                            label = { Text(label, maxLines = 1) },
                            modifier = if (scanningDescription != null) Modifier.semantics { contentDescription = scanningDescription } else Modifier,
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            // Fade through between destinations
            transitionSpec = { (fadeIn(effects) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(fastEffects) },
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            label = "destination",
        ) { current ->
            val currentStack = stacks.getValue(current)
            NavDisplay(
                backStack = currentStack,
                onBack = { if (currentStack.size > 1) currentStack.removeAt(currentStack.lastIndex) },
                entryDecorators = listOf(decorators.getValue(current)),
                // Shared axis X between a list and its pages
                transitionSpec = {
                    (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
                },
                popTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                entryProvider = entryProvider {
                    entry<Screen.Library> { LibraryScreen(browse, onOpenAlbum = { currentStack.add(Screen.Album(it)) }) }
                    entry<Screen.Album> { key ->
                        AlbumScreen(
                            browse,
                            key.key,
                            onBack = { currentStack.removeAt(currentStack.lastIndex) },
                            onOpenArtist = { currentStack.add(Screen.Artist(it)) },
                        )
                    }
                    entry<Screen.Artist> { key ->
                        ArtistScreen(key.name, onBack = { currentStack.removeAt(currentStack.lastIndex) })
                    }
                    entry<Screen.Search> { SearchScreen() }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            library,
                            onOpenLibrary = { currentStack.add(Screen.SettingsLibrary) },
                            onOpenAbout = { currentStack.add(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsLibrary> {
                        LibrarySettingsScreen(
                            library,
                            onBack = { currentStack.removeAt(currentStack.lastIndex) },
                            onOpenFolder = { currentStack.add(Screen.SettingsFolder(it)) },
                        )
                    }
                    entry<Screen.SettingsFolder> { key ->
                        FolderScreen(library, key.treeUri, onBack = { currentStack.removeAt(currentStack.lastIndex) })
                    }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(onBack = { currentStack.removeAt(currentStack.lastIndex) })
                    }
                },
            )
        }
    }
}

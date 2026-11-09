@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)

package sh.hnet.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.navigationBarsPadding
import sh.hnet.music.ui.library.PlaylistActionsHost
import sh.hnet.music.ui.library.LocalAddToPlaylist
import sh.hnet.music.ui.library.LocalStarring
import sh.hnet.music.ui.library.Starring
import sh.hnet.music.ui.library.AddRequest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import sh.hnet.music.ui.player.MiniPlayer
import sh.hnet.music.ui.player.NowPlayingScreen
import sh.hnet.music.ui.player.PlayerViewModel
import sh.hnet.music.ui.player.QueueSheet
import sh.hnet.music.ui.player.TrackDetailsScreen
import sh.hnet.music.data.LibraryTrack
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import sh.hnet.music.ui.library.AlbumScreen
import sh.hnet.music.ui.library.ArtistScreen
import sh.hnet.music.ui.library.ArtistTracksScreen
import sh.hnet.music.library.Albums
import sh.hnet.music.ui.components.TrackNavigation
import sh.hnet.music.ui.library.BrowseViewModel
import sh.hnet.music.ui.library.LibraryScreen
import sh.hnet.music.ui.library.PlaylistScreen
import sh.hnet.music.ui.search.SearchScreen
import sh.hnet.music.ui.search.SearchViewModel
import sh.hnet.music.ui.settings.AboutScreen
import sh.hnet.music.ui.settings.FolderScreen
import sh.hnet.music.ui.settings.LibraryMessage
import sh.hnet.music.ui.settings.LibraryMessages
import sh.hnet.music.ui.settings.LibrarySettingsScreen
import sh.hnet.music.ui.settings.LibraryViewModel
import sh.hnet.music.ui.settings.SettingsRootScreen
import sh.hnet.music.ui.settings.PlaybackSettingsScreen
import sh.hnet.music.ui.settings.LicencesScreen

/** The app's shell: one back stack per navigation bar destination, the bottom block, snackbars. */
@Composable
fun MusicApp(openNowPlaying: Int = 0) {
    val app = LocalContext.current.applicationContext as MusicApplication
    val library: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val browse: BrowseViewModel = viewModel { BrowseViewModel(app) }
    val playerModel: PlayerViewModel = viewModel { PlayerViewModel(app) }
    val search: SearchViewModel = viewModel { SearchViewModel(app) }
    val playerState by playerModel.state.collectAsStateWithLifecycle()
    val currentTrack by playerModel.current.collectAsStateWithLifecycle()
    val currentDetails by playerModel.details.collectAsStateWithLifecycle()
    val currentGain by playerModel.gain.collectAsStateWithLifecycle()
    val summary by library.summary.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    val stacks: Map<Tab, NavBackStack<NavKey>> = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    // Kept here, outside the tab's content, so a tab's pages keep their state while another tab shows
    val decorators = Tab.entries.associateWith { rememberSaveableStateHolderNavEntryDecorator<NavKey>() }
    val stack = stacks.getValue(tab)

    // A destination's root goes back to Library, Library's root leaves the app (§3.1)
    BackHandler(enabled = tab != Tab.LIBRARY && stack.size == 1) { tab = Tab.LIBRARY }

    // Now playing: an overlay opened from the mini player; its transition can be driven by
    // predictive back and by a downward drag, so it is seekable
    val scope = rememberCoroutineScope()
    val nowPlaying = remember { SeekableTransitionState(false) }
    val nowPlayingTransition = rememberTransition(nowPlaying, label = "nowPlaying")
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    // Add to playlist and Save as playlist, shown over everything (SPEC-playlists §7, §8)
    var addRequest by remember { mutableStateOf<AddRequest?>(null) }
    var queueToSave by remember { mutableStateOf<List<LibraryTrack>?>(null) }
    val queue by playerModel.queue.collectAsStateWithLifecycle()
    val tracksByUri by playerModel.tracks.collectAsStateWithLifecycle()
    val hasQueue = playerState.queueSize > 0
    fun openNowPlaying() = scope.launch { nowPlaying.animateTo(true) }
    fun closeNowPlaying() = scope.launch { detailsOpen = false; queueOpen = false; nowPlaying.animateTo(false) }
    LaunchedEffect(openNowPlaying) { if (openNowPlaying > 0 && hasQueue) nowPlaying.animateTo(true) }
    LaunchedEffect(hasQueue) { if (!hasQueue) nowPlaying.snapTo(false) }
    val dragHeight = with(LocalDensity.current) { 600.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    // Swipe down on the top bar or the cover closes now playing, following the finger
    val dragToClose = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { scope.launch { if (dragged / dragHeight > 0.25f) nowPlaying.animateTo(false) else nowPlaying.animateTo(true) } },
            onDragCancel = { scope.launch { nowPlaying.animateTo(true) } },
        ) { change, amount ->
            change.consume()
            dragged = (dragged + amount).coerceAtLeast(0f)
            scope.launch { nowPlaying.seekTo((dragged / dragHeight).coerceIn(0f, 1f), targetState = false) }
        }
    }
    val goToArtist: (String) -> Unit = { name ->
        closeNowPlaying()
        tab = Tab.LIBRARY
        stacks.getValue(Tab.LIBRARY).add(Screen.Artist(name))
    }
    val goToAlbum: (LibraryTrack) -> Unit = { track ->
        closeNowPlaying()
        tab = Tab.LIBRARY
        stacks.getValue(Tab.LIBRARY).add(Screen.Album(Albums.keyOf(track)))
    }
    /** A playlist's page on the Library stack (View after saving the queue as one). */
    val openPlaylist: (Long) -> Unit = { id ->
        closeNowPlaying()
        tab = Tab.LIBRARY
        stacks.getValue(Tab.LIBRARY).add(Screen.Playlist(id))
    }

    val snackbars = remember { SnackbarHostState() }
    val alreadyAdded = stringResource(R.string.msg_folder_already_added)
    val lostOne = stringResource(R.string.msg_folder_lost_access)
    val fix = stringResource(R.string.button_fix)
    val resources = LocalResources.current
    val folderRows by library.folders.collectAsStateWithLifecycle()
    /** Settings › Library, or with [onlyFolder] the folder's own page when there's just one. */
    fun openFolders(onlyFolder: Boolean = false) {
        tab = Tab.SETTINGS
        val settings = stacks.getValue(Tab.SETTINGS)
        while (settings.size > 1) settings.removeAt(settings.lastIndex)
        settings.add(Screen.SettingsLibrary)
        val single = folderRows.singleOrNull()
        if (onlyFolder && single != null) settings.add(Screen.SettingsFolder(single.folder.treeUri))
    }
    LibraryMessages(library) { message ->
        when (message) {
            is LibraryMessage.AlreadyAdded -> snackbars.showSnackbar(
                AnnotatedString.fromHtml(alreadyAdded.format(message.name.htmlEncode())).text,
            )
            is LibraryMessage.LostAccess -> {
                val text = if (message.names.size == 1) {
                    AnnotatedString.fromHtml(lostOne.format(message.names[0].htmlEncode())).text
                } else {
                    resources.getQuantityString(R.plurals.msg_folders_lost_access, message.names.size, message.names.size)
                }
                if (snackbars.showSnackbar(text, actionLabel = fix, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) openFolders()
            }
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    val starredState = browse.starred.collectAsStateWithLifecycle()
    val starring = remember(browse) { Starring(starredState) { track, on -> browse.setStarred(track, on) } }
    CompositionLocalProvider(LocalAddToPlaylist provides { addRequest = it }, LocalStarring provides starring) {
    SharedTransitionLayout {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        },
        bottomBar = {
            Column {
                // Absent while the current track can't be shown (its folder lost access, SPEC §13)
                if (hasQueue && currentTrack != null) {
                    nowPlayingTransition.AnimatedVisibility(visible = { open -> !open }, enter = fadeIn(), exit = fadeOut()) {
                        MiniPlayer(
                            track = currentTrack,
                            state = playerState,
                            onPlayPause = playerModel.player::togglePlayPause,
                            onNext = playerModel.player::next,
                            onOpen = { openNowPlaying() },
                            modifier = Modifier.sharedBounds(rememberSharedContentState("player"), this),
                        )
                    }
                }
                ShortNavigationBar {
                    Tab.entries.forEach { item ->
                        val selected = item == tab
                        val label = stringResource(item.label)
                        val scanning = item == Tab.SETTINGS && summary.scanning > 0
                        // A dot while a folder has no access, until it's fixed or removed (SPEC §13)
                        val attention = item == Tab.SETTINGS && !scanning && summary.unavailable > 0
                        val scanningDescription = when {
                            scanning -> pluralStringResource(R.plurals.content_description_settings_scanning, summary.scanning, summary.scanning)
                            attention -> stringResource(R.string.content_description_settings_attention)
                            else -> null
                        }
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    // Re-selecting a destination returns to its root; Search also clears its query
                                    while (stack.size > 1) stack.removeAt(stack.lastIndex)
                                    if (item == Tab.SEARCH) search.clear()
                                } else {
                                    tab = item
                                    // Selecting Search puts the cursor in its field
                                    if (item == Tab.SEARCH && stacks.getValue(Tab.SEARCH).size == 1) search.requestFocus()
                                }
                            },
                            icon = {
                                BadgedBox(badge = { if (scanning) Badge { Text(formatCount(summary.scanning)) } else if (attention) Badge() }) {
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
            // The bottom bar's height is padding already given: the keyboard (imePadding in Search)
            // only adds what it covers beyond it, or the screen ends a bar's height above the keyboard
            modifier = Modifier.fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
            label = "destination",
        ) { current ->
            val currentStack = stacks.getValue(current)
            val back: () -> Unit = { currentStack.removeAt(currentStack.lastIndex) }
            // Go to artist / album from any track menu opens the page in this destination's stack
            val trackNavigation = TrackNavigation(
                onGoToArtist = { currentStack.add(Screen.Artist(it)) },
                onGoToAlbum = { track -> currentStack.add(Screen.Album(Albums.keyOf(track))) },
            )
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
                    entry<Screen.Library> {
                        LibraryScreen(
                            browse,
                            summary = summary,
                            navigation = trackNavigation,
                            onOpenAlbum = { currentStack.add(Screen.Album(it)) },
                            onOpenArtist = { currentStack.add(Screen.Artist(it)) },
                            onFolderPicked = { library.onFolderPicked(it) },
                            onSeeSkippedFiles = { openFolders(onlyFolder = true) },
                            snackbars = snackbars,
                            messageScope = scope,
                            onOpenPlaylist = { currentStack.add(Screen.Playlist(it)) },
                        )
                    }
                    entry<Screen.Album> { key ->
                        AlbumScreen(
                            browse,
                            key.key,
                            navigation = trackNavigation,
                            onBack = back,
                            onOpenArtist = { currentStack.add(Screen.Artist(it)) },
                        )
                    }
                    entry<Screen.Artist> { key ->
                        ArtistScreen(
                            browse,
                            key.name,
                            navigation = trackNavigation,
                            onBack = back,
                            onOpenAlbum = { currentStack.add(Screen.Album(it)) },
                            onShowAllTracks = { currentStack.add(Screen.ArtistTracks(key.name)) },
                        )
                    }
                    entry<Screen.Playlist> { key ->
                        PlaylistScreen(browse, key.id, trackNavigation, snackbars, scope, onBack = back)
                    }
                    entry<Screen.ArtistTracks> { key ->
                        ArtistTracksScreen(browse, key.name, trackNavigation, onBack = back)
                    }
                    entry<Screen.Search> {
                        SearchScreen(
                            search,
                            browse,
                            navigation = trackNavigation,
                            onOpenAlbum = { currentStack.add(Screen.Album(it)) },
                            onOpenArtist = { currentStack.add(Screen.Artist(it)) },
                            // Pushed on the Search stack (§11)
                            onOpenPlaylist = { currentStack.add(Screen.Playlist(it)) },
                            snackbars = snackbars,
                            messageScope = scope,
                        )
                    }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            library,
                            onOpenLibrary = { currentStack.add(Screen.SettingsLibrary) },
                            onOpenPlayback = { currentStack.add(Screen.SettingsPlayback) },
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
                    entry<Screen.SettingsPlayback> { PlaybackSettingsScreen(onBack = back) }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(onBack = back, onOpenLicences = { currentStack.add(Screen.SettingsLicences) })
                    }
                    entry<Screen.SettingsLicences> { LicencesScreen(onBack = back) }
                },
            )
        }
    }
    // Now playing over everything, without navigation bar and mini player (SPEC §3.3)
    nowPlayingTransition.AnimatedVisibility(visible = { open -> open }, enter = fadeIn(), exit = fadeOut()) {
        // Registered here, after the destinations, so they win over the pages' own Back while open
        BackHandler(enabled = detailsOpen) { detailsOpen = false }
        PredictiveBackHandler(enabled = nowPlaying.targetState && !detailsOpen) { events ->
            try {
                events.collect { nowPlaying.seekTo(it.progress, targetState = false) }
            } catch (cancelled: CancellationException) {
                // The gesture was cancelled: back to fully open
                scope.launch { nowPlaying.animateTo(true) }
                throw cancelled
            }
            // Closing disables this handler, which cancels its coroutine: run outside it
            scope.launch { nowPlaying.animateTo(false) }
        }
        Box(Modifier.fillMaxSize().sharedBounds(rememberSharedContentState("player"), this)) {
            NowPlayingScreen(
                track = currentTrack,
                details = currentDetails,
                gain = currentGain,
                state = playerState,
                player = playerModel.player,
                dragArea = dragToClose,
                onClose = { closeNowPlaying() },
                onGoToArtist = goToArtist,
                onGoToAlbum = goToAlbum,
                onDetails = { detailsOpen = true },
                onQueue = { queueOpen = true },
            )
            if (queueOpen) {
                QueueSheet(
                    queue, playerState.shuffle, tracksByUri, playerModel.player,
                    onDismiss = { queueOpen = false },
                    // The sheet closes, so the result's snackbar (with View) can be seen
                    onSaveAsPlaylist = { queueOpen = false; queueToSave = it },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = detailsOpen,
                enter = slideInHorizontally(spatial) { shift } + fadeIn(effects),
                exit = slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects),
            ) {
                TrackDetailsScreen(playerModel, onBack = { detailsOpen = false })
            }
            // Snackbars started here (Add to playlist) would show under this overlay otherwise
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
    }
    }
    PlaylistActionsHost(
        viewModel = browse,
        snackbars = snackbars,
        scope = scope,
        addRequest = addRequest,
        onAddDone = { addRequest = null },
        queueToSave = queueToSave,
        onSaveDone = { queueToSave = null },
        onOpenPlaylist = { id -> openPlaylist(id) },
    )
    }
}

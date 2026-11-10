package sh.hnet.music.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * How much of a screen's bottom the mini player covers: it floats over the content (no band of
 * background under it), so lists end this much lower to scroll their last item above it, and
 * things pinned to the bottom (a FAB, the fast scroller) stay clear of it. 0 while it's hidden.
 */
val LocalBottomOverlay = compositionLocalOf { 0.dp }

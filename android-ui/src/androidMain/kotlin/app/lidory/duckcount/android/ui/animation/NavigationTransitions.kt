package app.lidory.duckcount.android.ui.animation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * The spring `slideInHorizontally` moves on by default, and so the pace the settings slide always
 * had; the container slides default to a stiffer one, about twice as quick. Shared elements travel
 * on this same spring, so they land with the screen rather than after it.
 */
private val slideSpec = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * A screen opened on top of another: it comes in from the end while the one it covers leaves
 * towards the start, so that going back, [popTransition], reads as the same move reversed.
 * Towards the start and the end rather than left and right, so that it mirrors with the layout.
 */
fun <S> AnimatedContentTransitionScope<S>.pushTransition(): ContentTransform =
    slideIntoContainer(SlideDirection.Start, slideSpec) togetherWith
        slideOutOfContainer(SlideDirection.Start, slideSpec)

/** Back to the screen another was opened from: [pushTransition] played backwards. */
fun <S> AnimatedContentTransitionScope<S>.popTransition(): ContentTransform =
    slideIntoContainer(SlideDirection.End, slideSpec) togetherWith
        slideOutOfContainer(SlideDirection.End, slideSpec)

/**
 * A screen that takes the place of another rather than opening on top of it, signing in for
 * instance: nothing to slide back to, so the one simply fades into the other.
 */
fun replaceTransition(): ContentTransform = fadeIn() togetherWith fadeOut()

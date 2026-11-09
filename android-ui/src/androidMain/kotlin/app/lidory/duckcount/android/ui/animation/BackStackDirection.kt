package app.lidory.duckcount.android.ui.animation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Which way a back stack last moved, for a `NavDisplay` to slide its screens the right way, see
 * [directedTransition].
 *
 * `NavDisplay` tells a pop from the back stack it recorded as its animation started, but records
 * it again when a change interrupts that animation, by which time the back stack already has the
 * change: a Back pressed during the animation of the one before it then looks like no pop at all,
 * and the screens slide the way they do to open one.
 */
class BackStackDirection internal constructor(private var backStack: List<Any>) {

    /** Whether the last change took screens off the top of the back stack and did nothing else. */
    internal var isPop = false
        private set

    /** Takes [current] as the back stack now, which says nothing new if it did not change. */
    internal fun record(current: List<Any>) {
        if (current == backStack) return

        isPop = current.size < backStack.size && backStack.subList(0, current.size) == current
        backStack = current
    }
}

/** The [BackStackDirection] of [backStack], which has to be observable for it to keep up. */
@Composable
fun rememberBackStackDirection(backStack: List<Any>): BackStackDirection {
    // A copy, since a back stack changes in place.
    val current = backStack.toList()
    val direction = remember { BackStackDirection(backStack = current) }

    // Before the transition reads it: a NavDisplay starts animating to a change a frame after it.
    SideEffect { direction.record(current) }

    return direction
}

/** [popTransition] when [direction] says the back stack was last popped, [pushTransition] otherwise. */
fun <S> AnimatedContentTransitionScope<S>.directedTransition(direction: BackStackDirection): ContentTransform =
    if (direction.isPop) popTransition() else pushTransition()

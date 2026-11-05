package io.github.goooseby.keye.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

/** One rhythm for page chrome, content and the shared navigation bar. */
internal object PageMotion {
    private const val Duration = 280

    val navigationEnter: EnterTransition
        get() = slideInVertically(tween(Duration, easing=FastOutSlowInEasing)) {it} +
            fadeIn(tween(Duration))

    val navigationExit: ExitTransition
        get() = slideOutVertically(tween(Duration, easing=FastOutSlowInEasing)) {it} +
            fadeOut(tween(180))

    fun between(initialDepth: Int, targetDepth: Int, distance: Int): ContentTransform {
        val forward = targetDepth > initialDepth
        val backward = targetDepth < initialDepth
        val enter = fadeIn(tween(if(forward || backward) Duration else 220))
        val exit = fadeOut(tween(180))
        return when {
            forward -> (enter + slideInHorizontally(tween(Duration, easing=FastOutSlowInEasing)) {distance}) togetherWith
                (exit + slideOutHorizontally(tween(Duration, easing=FastOutSlowInEasing)) {-distance / 2})
            backward -> (enter + slideInHorizontally(tween(Duration, easing=FastOutSlowInEasing)) {-distance / 2}) togetherWith
                (exit + slideOutHorizontally(tween(Duration, easing=FastOutSlowInEasing)) {distance})
            else -> enter togetherWith exit
        }
    }
}

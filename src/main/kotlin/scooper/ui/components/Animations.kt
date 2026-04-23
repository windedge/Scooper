package scooper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun EnterAnimation(enable: Boolean = true, content: @Composable () -> Unit) {
    if (enable) {
        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false) }
                .apply { targetState = true },
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = 260,
                    delayMillis = 0,
                    easing = FastOutSlowInEasing
                )
            ) + slideInHorizontally(
                animationSpec = spring(),
                initialOffsetX = { width -> width / 12 }
            ),
            exit = ExitTransition.None,
        ) {
            content()
        }
    } else {
        content()
    }
}

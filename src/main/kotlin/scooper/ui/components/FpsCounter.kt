package scooper.ui.components

import androidx.compose.animation.core.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import scooper.ui.theme.dangerDefault
import scooper.ui.theme.updateDefault
import scooper.ui.theme.warningDefault
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures actual rendering FPS via an [InfiniteTransition] animation.
 *
 * Compose 1.10+ on Desktop skips rendering when no observable state changes.
 * A raw `withFrameNanos` loop therefore stalls because nothing keeps the
 * [MonotonicFrameClock] ticking.
 *
 * The animation framework avoids this by running its own coroutine that
 * registers on the frame clock; when a frame arrives it writes an internal
 * [MutableState], which triggers invalidation → recomposition → render
 * for the *next* frame, keeping the loop alive without any self-write
 * inside the composable body.
 *
 * [FpsCounter] is isolated so that only this tiny subtree
 * recomposes every ~16 ms; the parent is unaffected.
 */
@Composable
fun FpsCounter(modifier: Modifier = Modifier, onFps: (Int) -> Unit = {}) {
    val frameCount = remember { AtomicLong(0) }

    // Animation that advances every frame.  We must read the resulting state
    // so the snapshot system tracks it and schedules recomposition.
    val animDriver by rememberInfiniteTransition("fps_driver").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fps_driver_value",
    )
    @Suppress("UNUSED_EXPRESSION")
    animDriver

    // Each recomposition of this composable = one rendered frame.
    frameCount.incrementAndGet()

    // Sample the counter once per second and report FPS.
    LaunchedEffect(Unit) {
        while (isActive) {
            frameCount.set(0)
            delay(1000)
            onFps(frameCount.get().toInt().coerceAtMost(999))
        }
    }
}

/**
 * Displays the FPS value with color coding.
 */
@Composable
fun FpsLabel(fps: Int, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.caption) {
    val colors = MaterialTheme.colors
    val fpsColor = when {
        fps >= 55 -> colors.updateDefault
        fps >= 30 -> colors.warningDefault
        else -> colors.dangerDefault
    }
    Text(
        "$fps FPS",
        modifier = modifier,
        style = style.copy(color = fpsColor),
    )
}

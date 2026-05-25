package scooper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import scooper.ui.icons.*
import scooper.ui.theme.dangerDefault
import scooper.ui.theme.updateDefault
import scooper.viewmodels.ToastType
import kotlin.coroutines.resume

private const val SNACKBAR_DURATION_MS = 3000L

/**
 * A lightweight snackbar host state that carries [ToastType] information.
 *
 * Only one snackbar is shown at a time; a new call replaces the current one.
 */
@Stable
class CustomSnackbarHostState {
    internal var current by mutableStateOf<SnackbarEntry?>(null)
        private set

    private val mutex = Mutex()

    suspend fun showSnackbar(
        message: String,
        toastType: ToastType = ToastType.INFO,
    ) {
        mutex.withLock {
            suspendCancellableCoroutine<Unit> { cont ->
                current = SnackbarEntry(message, toastType) {
                    current = null
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    internal class SnackbarEntry(
        val message: String,
        val toastType: ToastType,
        val onTimeout: () -> Unit,
    )
}

@Composable
fun SnackbarHost(hostState: CustomSnackbarHostState) {
    val entry = hostState.current

    // Auto-dismiss after timeout
    entry?.let {
        LaunchedEffect(it) {
            delay(SNACKBAR_DURATION_MS)
            it.onTimeout()
        }
    }

    AnimatedVisibility(
        visible = entry != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
    ) {
        if (entry != null) {
            val (bgColor, contentColor, icon) = snackbarStyleForType(entry.toastType)

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 200.dp, max = 480.dp)
                        .shadow(6.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(bgColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = entry.toastType.name,
                            modifier = Modifier.size(18.dp),
                            tint = contentColor,
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.body2,
                            color = contentColor,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun snackbarStyleForType(type: ToastType): Triple<Color, Color, ImageVector> {
    val colors = MaterialTheme.colors
    return when (type) {
        ToastType.SUCCESS -> Triple(colors.updateDefault, colors.onPrimary, Lucide.CircleCheck)
        ToastType.ERROR -> Triple(colors.dangerDefault, colors.onPrimary, Lucide.CircleX)
        ToastType.INFO -> Triple(colors.primary, colors.onPrimary, Lucide.Info)
    }
}

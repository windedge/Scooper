package scooper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SnackbarHost(snackbarHostState: SnackbarHostState): Unit {
    SnackbarHost(snackbarHostState, modifier = Modifier.offset(y = (-30).dp)) { snackbarData ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Snackbar(
                modifier = Modifier.width(maxWidth / 3 * 2),
                backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colors.onPrimary
            ) {
                Text(snackbarData.message, style = MaterialTheme.typography.body1)
            }
        }
    }
}
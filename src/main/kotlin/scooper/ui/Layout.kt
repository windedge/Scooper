package scooper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import scooper.util.navigation.core.BackStack

@Composable
fun Layout(
    navigator: BackStack<AppRoute>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(modifier = modifier, color = MaterialTheme.colors.background) {
        Row(
            Modifier.fillMaxSize().padding(top = 2.dp, start = 1.dp, end = 1.dp, bottom = 1.dp)
        ) {
            val showSideBar = navigator.current.value != AppRoute.Output
            if (showSideBar) {
                SideBar(navigator)
                Spacer(Modifier.width(4.dp))
            }
            content()
        }
    }
}
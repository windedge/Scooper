package scooper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import scooper.util.cursorLink
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack

@Suppress("UNCHECKED_CAST")
@Composable
fun SettingScreen() {
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    Column {
        Surface(
            modifier = Modifier.height(60.dp).fillMaxWidth(),
            elevation = 2.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(
                        Icons.TwoTone.ArrowBack,
                        "",
                        Modifier.cursorLink(),
                        tint = MaterialTheme.colors.onSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Settings")
        }
    }
}

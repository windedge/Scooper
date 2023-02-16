package scooper.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import scooper.ui.AppRoute
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.paddingIfHeight

@Composable
fun PrefTextField(
    value: String,
    onValueChange: (String) -> Unit = {},
    label: String? = null,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
) {

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(label)
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            isError = isError,
            modifier = modifier.then(Modifier.widthIn(min = 100.dp).fillMaxWidth(0.6f).height(30.dp)),
            singleLine = true,
            contentPadding = PaddingValues(5.dp),
        )
        if (isError && errorMessage != null) {
            Text(errorMessage, color = colors.error)
        }
    }
}


@Composable
fun PrefRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    nestedContent: @Composable (() -> Unit)? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    PrefRow(
        title = { Text(title) },
        modifier = modifier,
        subtitle = subtitle?.let { { Text(subtitle) } },
        nestedContent = nestedContent,
        content = content
    )
}


@Composable
fun PrefRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    nestedContent: @Composable (() -> Unit)? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.then(Modifier.fillMaxWidth().padding(10.dp))) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.6f)) {
                ProvideTextStyle(MaterialTheme.typography.subtitle1) {
                    title()
                }

                ProvideTextStyle(MaterialTheme.typography.subtitle2.copy(color = colors.onSecondary)) {
                    subtitle?.invoke()
                }
            }
            if (content != null) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    this.content()
                }
            }
        }
        if (nestedContent != null) {
            val padding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            Box(modifier = Modifier.paddingIfHeight(padding)) {
                ProvideTextStyle(MaterialTheme.typography.body1) {
                    nestedContent()
                }
            }
        }
    }
}

@Composable
fun SettingContainer(
    modifier: Modifier = Modifier,
    onApply: (() -> Unit)? = null,
    applyEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier.widthIn(max = 850.dp).fillMaxWidth()
                .heightIn(min = 300.dp)
                .padding(start = 15.dp, top = 10.dp, end = 15.dp, bottom = 10.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(modifier = Modifier.padding(top = 10.dp), elevation = 4.dp, shape = RoundedCornerShape(6.dp)) {
                content()
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (onApply != null) {
                Button(onClick = { onApply.invoke() }, modifier = Modifier.cursorHand(), enabled = applyEnabled) {
                    Text("Apply")
                }
            }
        }

        VerticalScrollbar(
            rememberScrollbarAdapter(scrollState),
            modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd)
        )
    }
}

@Composable
fun NavBar(
    navItems: List<AppRoute.Settings>,
    activeRoute: AppRoute.Settings,
    onBack: () -> Unit = {},
    onClick: (AppRoute.Settings) -> Unit = {}
) {
    Surface(modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max).requiredWidthIn(min = 150.dp)) {
        Column {
            androidx.compose.material.IconButton(onClick = onBack, modifier = Modifier.padding(5.dp)) {
                Icon(
                    Icons.TwoTone.ArrowBack, "", Modifier.size(30.dp).cursorLink(), tint = colors.primary
                )
            }
            Spacer(modifier = Modifier.height(15.dp).fillMaxWidth())
            navItems.forEach { route ->
                NavItem(route.menuText, selected = activeRoute == route, onClick = { onClick(route) })
            }
        }
    }
}

@Composable
fun NavItem(menu: String, modifier: Modifier = Modifier, selected: Boolean, onClick: () -> Unit = {}) {
    var default = Modifier.fillMaxWidth().height(50.dp).cursorHand().clickable(onClick = onClick)
    if (selected) {
        default = default.background(
            color = colors.primaryVariant,
            shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
        )
    }
    Box(
        modifier = modifier.then(default), contentAlignment = Alignment.CenterStart
    ) {
        Text(menu, modifier = Modifier.padding(start = 15.dp), style = MaterialTheme.typography.subtitle1)
    }
}

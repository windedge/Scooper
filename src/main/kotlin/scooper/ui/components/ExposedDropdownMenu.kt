package scooper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import scooper.ui.theme.inputBackground
import scooper.ui.theme.inputBorder
import scooper.ui.theme.primarySubtle
import scooper.ui.theme.textMuted
import scooper.util.cursorHand
import scooper.util.onHover

/**
 * A basic implementation of the Exposed Dropdown Menu component
 *
 * @see https://material.io/components/menus#exposed-dropdown-menu
 * @source https://gist.github.com/jossiwolf/0f06894d2c07748041769c64510cd4d5
 */
@Composable
fun ExposedDropdownMenu(
    items: List<String>,
    modifier: Modifier = Modifier,
    selected: String = items[0],
    onItemSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuStack(
        textField = {
            BasicTextField(
                value = selected,
                onValueChange = {},
                modifier = modifier,
                readOnly = true,
                textStyle = TextStyle(color = colors.onSurface),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.height(36.dp)
                            .background(color = colors.inputBackground, shape = RoundedCornerShape(8.dp))
                            .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                            .cursorHand()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        innerTextField()
                        Spacer(modifier = Modifier.width(8.dp))
                        val rotation by animateFloatAsState(if (expanded) 180F else 0F)
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Dropdown Arrow",
                            modifier = Modifier.rotate(rotation),
                            tint = colors.textMuted,
                        )
                    }
                }
            )
        },
        dropdownMenu = { boxWidth, itemHeight ->
            Box(
                Modifier
                    .width(boxWidth)
                    .wrapContentSize(Alignment.TopStart)
            ) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    items.forEach { item ->
                        var hover by remember { mutableStateOf(false) }
                        DropdownMenuItem(
                            modifier = Modifier
                                .height(itemHeight)
                                .width(boxWidth)
                                .background(if (hover) colors.primarySubtle else colors.surface)
                                .onHover { hover = it }
                                .cursorHand(),
                            onClick = {
                                expanded = false
                                onItemSelected(item)
                            }
                        ) {
                            Text(item, color = colors.onSurface)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ExposedDropdownMenuStack(
    textField: @Composable () -> Unit,
    dropdownMenu: @Composable (boxWidth: Dp, itemHeight: Dp) -> Unit
) {
    SubcomposeLayout { constraints ->
        val textFieldPlaceable =
            subcompose(ExposedDropdownMenuSlot.TextField, textField).first().measure(constraints)

        val dropdownPlaceable = subcompose(ExposedDropdownMenuSlot.Dropdown) {
            dropdownMenu(textFieldPlaceable.width.toDp(), textFieldPlaceable.height.toDp())
        }.first().measure(constraints)

        layout(textFieldPlaceable.width, textFieldPlaceable.height) {
            textFieldPlaceable.placeRelative(0, 0)
            dropdownPlaceable.placeRelative(0, textFieldPlaceable.height)
        }
    }
}

private enum class ExposedDropdownMenuSlot { TextField, Dropdown }

@Suppress("unused")
@Composable
fun ExposedDropdownMenuX(
    items: List<String>,
    selected: String = items[0],
    onItemSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    BasicTextField(
        value = selected,
        onValueChange = { },
        modifier = Modifier,
        readOnly = true,
        textStyle = TextStyle(color = colors.onSurface),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.height(36.dp)
                    .background(color = colors.inputBackground, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                innerTextField()
                Spacer(modifier = Modifier.width(8.dp))
                val rotation by animateFloatAsState(if (expanded) 180F else 0F)
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown Arrow",
                    modifier = Modifier.rotate(rotation),
                    tint = colors.textMuted,
                )
                DropdownMenu(
                    expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    items.forEach { label ->
                        DropdownMenuItem(onClick = {
                            onItemSelected(label)
                            expanded = false
                        }) {
                            Text(label, color = colors.onSurface)
                        }
                    }
                }
            }
        }
    )
}

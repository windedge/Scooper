package scooper.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import scooper.ui.theme.*
import scooper.util.cursorHand

@Composable
fun ConfirmDialog(
    text: String? = null,
    title: String? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmText: String? = null,
    cancelText: String? = null,
    state: DialogState = DialogState(size = DpSize(320.dp, 180.dp)),
    content: @Composable (() -> Unit)? = null
) {
    val colors = MaterialTheme.colors
    DialogWindow(onCloseRequest = onCancel, state = state, title = title ?: "Scooper", resizable = false) {
        Surface(color = colors.surface) {
            Column(Modifier.fillMaxSize()) {
                // Content
                Box(Modifier.weight(1f).padding(24.dp)) {
                    if (content != null) content() else {
                        Text(
                            text ?: "",
                            style = MaterialTheme.typography.body1.copy(color = colors.textTitle)
                        )
                    }
                }

                Divider(color = colors.divider)
                Row(
                    Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.cursorHand()) {
                        Text(cancelText ?: "Cancel", color = colors.textBody)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (confirmText == "Delete") colors.dangerDefault else colors.primary
                        ),
                        elevation = null,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.cursorHand()
                    ) {
                        Text(confirmText ?: "OK", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = when {
        isError -> colors.error
        isFocused -> colors.primary
        else -> colors.inputBorder
    }

    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inputBackground)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.body2.copy(color = colors.textPlaceholder)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.body2.copy(color = colors.textTitle),
            interactionSource = interactionSource,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(if (isError) colors.error else colors.primary)
        )
    }
}

package scooper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover

@Composable
fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    pageSize: Int,
    onGoToPage: (Int) -> Unit,
    onPageSizeChange: (Int) -> Unit,
) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.surface)
            .border(width = 1.dp, color = colors.borderDefault)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Page size selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rows per page:", style = typography.body2.copy(color = colors.textBody))
            Spacer(Modifier.width(8.dp))
            val pageSizeOptions = listOf(10, 25, 50, 100)
            var expanded by remember { mutableStateOf(false) }
            Box {
                Row(
                    modifier = Modifier.height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.borderDefault, RoundedCornerShape(6.dp))
                        .background(colors.inputBackground)
                        .cursorHand()
                        .clickable { expanded = true }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$pageSize", style = typography.body2.copy(color = colors.onSurface))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(14.dp), tint = colors.textMuted)
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(80.dp).cursorHand()) {
                    pageSizeOptions.forEach { size ->
                        var hover by remember { mutableStateOf(false) }
                        DropdownMenuItem(
                            onClick = { expanded = false; onPageSizeChange(size) },
                            modifier = Modifier.sizeIn(maxHeight = 32.dp)
                                .background(if (hover) colors.primarySubtle else colors.surface)
                                .onHover { hover = it }
                        ) {
                            Text("$size", style = typography.button.copy(color = if (hover) colors.primary else colors.onSurface))
                        }
                    }
                }
            }
        }

        // Page navigation
        Row(verticalAlignment = Alignment.CenterVertically) {
            // First page
            IconButton(
                onClick = { onGoToPage(1) },
                enabled = currentPage > 1,
                modifier = Modifier.cursorHand()
            ) {
                Icon(painterResource("arrow-left-to-line.svg"), "First", modifier = Modifier.size(16.dp), tint = if (currentPage > 1) colors.textBody else colors.textMuted.copy(alpha = 0.4f))
            }

            Spacer(Modifier.width(12.dp))

            // Previous
            IconButton(
                onClick = { onGoToPage(currentPage - 1) },
                enabled = currentPage > 1,
                modifier = Modifier.cursorHand()
            ) {
                Icon(painterResource("chevron-left.svg"), "Prev", modifier = Modifier.size(16.dp), tint = if (currentPage > 1) colors.textBody else colors.textMuted.copy(alpha = 0.4f))
            }

            Spacer(Modifier.width(12.dp))

            // Page numbers
            val pageNumbers = remember(currentPage, totalPages) {
                val pages = mutableListOf<Any>()
                if (totalPages <= 5) {
                    for (i in 1..totalPages) pages.add(i)
                } else if (currentPage <= 3) {
                    pages.addAll(listOf(1, 2, 3, 4, "...", totalPages))
                } else if (currentPage >= totalPages - 2) {
                    pages.addAll(listOf(1, "...", totalPages - 3, totalPages - 2, totalPages - 1, totalPages))
                } else {
                    pages.addAll(listOf(1, "...", currentPage - 1, currentPage, currentPage + 1, "...", totalPages))
                }
                pages
            }

            pageNumbers.forEachIndexed { _, page ->
                if (page is Int) {
                    val isSelected = page == currentPage
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) colors.primary else colors.surface,
                        border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.borderDefault),
                        modifier = Modifier.size(30.dp).cursorHand().clickable { onGoToPage(page) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "$page",
                                style = typography.body2.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) Color.White else colors.textBody
                                )
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                } else {
                    Text("...", style = typography.body2.copy(color = colors.textMuted), modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            // Next
            IconButton(
                onClick = { onGoToPage(currentPage + 1) },
                enabled = currentPage < totalPages,
                modifier = Modifier.cursorHand()
            ) {
                Icon(painterResource("chevron-right.svg"), "Next", modifier = Modifier.size(16.dp), tint = if (currentPage < totalPages) colors.textBody else colors.textMuted.copy(alpha = 0.4f))
            }
            Spacer(Modifier.width(12.dp))
            // Last page
            IconButton(
                onClick = { onGoToPage(totalPages) },
                enabled = currentPage < totalPages,
                modifier = Modifier.cursorHand()
            ) {
                Icon(painterResource("arrow-right-to-line.svg"), "Last", modifier = Modifier.size(16.dp), tint = if (currentPage < totalPages) colors.textBody else colors.textMuted.copy(alpha = 0.4f))
            }
        }
    }
}

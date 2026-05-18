package scooper.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val parser: Parser by lazy {
    Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()
}

data class MarkdownStyle(
    val bodyColor: Color,
    val mutedColor: Color,
    val linkColor: Color,
    val codeBackgroundColor: Color,
)

fun parseMarkdown(markdown: String, style: MarkdownStyle): AnnotatedString {
    val doc = parser.parse(markdown)
    return buildAnnotatedString {
        renderNode(doc, style)
    }
}

private fun AnnotatedString.Builder.renderNode(node: Node, style: MarkdownStyle) {
    when (node) {
        is Document, is Paragraph -> {
            val suffix = when (node.parent) {
                is Document -> "\n\n"
                is ListItem -> ""
                else -> "\n"
            }
            renderChildren(node, style, suffix)
        }
        is Heading -> {
            val fontSize = when (node.level) {
                1 -> 1.25.em
                2 -> 1.15.em
                3 -> 1.05.em
                else -> 1.0.em
            }
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize, color = style.bodyColor))
            renderChildren(node, style)
            pop()
            append("\n\n")
        }
        is Emphasis -> {
            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = style.bodyColor))
            renderChildren(node, style)
            pop()
        }
        is StrongEmphasis -> {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = style.bodyColor))
            renderChildren(node, style)
            pop()
        }
        is Strikethrough -> {
            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = style.mutedColor))
            renderChildren(node, style)
            pop()
        }
        is Code -> {
            pushStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = style.bodyColor,
            ))
            append(node.literal)
            pop()
        }
        is FencedCodeBlock -> {
            pushStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = style.bodyColor,
            ))
            append(node.literal)
            pop()
            append("\n\n")
        }
        is IndentedCodeBlock -> {
            pushStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = style.bodyColor,
            ))
            append(node.literal)
            pop()
            append("\n\n")
        }
        is Text -> append(node.literal)
        is SoftLineBreak -> append(' ')
        is HardLineBreak -> append('\n')
        is Link -> {
            pushStyle(SpanStyle(color = style.linkColor, textDecoration = TextDecoration.Underline))
            renderChildren(node, style)
            pop()
        }
        is BulletList -> {
            renderListItems(node, style, isOrdered = false)
        }
        is OrderedList -> {
            renderListItems(node, style, isOrdered = true, startFrom = 1)
        }
        is ListItem -> renderChildren(node, style)
        is BlockQuote -> {
            pushStyle(SpanStyle(color = style.mutedColor))
            renderChildren(node, style)
            pop()
            append("\n\n")
        }
        is ThematicBreak -> {
            append("─".repeat(40))
            append("\n\n")
        }
        else -> renderChildren(node, style)
    }
}

private fun AnnotatedString.Builder.renderChildren(node: Node, style: MarkdownStyle, suffix: String = "") {
    var child = node.firstChild
    while (child != null) {
        renderNode(child, style)
        child = child.next
    }
    if (suffix.isNotEmpty()) append(suffix)
}

private fun AnnotatedString.Builder.renderListItems(
    listNode: Node,
    style: MarkdownStyle,
    isOrdered: Boolean,
    startFrom: Int = 1,
) {
    var index = startFrom
    val items = mutableListOf<ListItem>()
    var child = listNode.firstChild
    while (child != null) {
        if (child is ListItem) items.add(child)
        child = child.next
    }
    items.forEachIndexed { i, item ->
        val bullet = if (isOrdered) "${index + i}. " else "• "
        append(bullet)
        renderChildren(item, style)
        append("\n\n")
    }
}

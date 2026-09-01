package scooper.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

import scooper.ui.theme.borderDefault
import scooper.ui.theme.textMuted
import scooper.ui.theme.textTitle

/**
 * Self-built lightweight Markdown subset renderer (architecture proposal 5.5).
 *
 * Decision: instead of pulling a third-party markdown renderer (dependency weight and
 * Compose version coupling), this parser covers the subset that AI explanations
 * actually produce: paragraphs, **bold**, `inline code`, ``` fenced code blocks and
 * `-`/`*` unordered lists. Everything else degrades to plain text paragraphs.
 *
 * The parser ([parseMarkdown] / [parseMarkdownInlines]) is framework-agnostic and
 * pure, so JVM unit tests can assert on the block model directly. Cleaning rules:
 * fenced code blocks over [MAX_CODE_BLOCK_LINES] lines are truncated with
 * [CODE_TRUNCATION_SUFFIX], and control characters are stripped.
 */

/** Hard cap for a single fenced code block, after which it is truncated. */
internal const val MAX_CODE_BLOCK_LINES = 200

/** Suffix line appended to a truncated code block. */
internal const val CODE_TRUNCATION_SUFFIX = "...(truncated)"

/** A block-level Markdown element. */
internal sealed class MarkdownBlock {
    /** Plain paragraph holding parsed inline spans. */
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock()

    /** Fenced code block; [lines] are kept literally (no inline parsing). */
    data class CodeBlock(val lines: List<String>, val truncated: Boolean = false) : MarkdownBlock()

    /** Single unordered-list entry. */
    data class ListItem(val inlines: List<MarkdownInline>) : MarkdownBlock()
}

/** An inline Markdown span inside a paragraph or list item. */
internal sealed class MarkdownInline {
    data class Text(val value: String) : MarkdownInline()
    data class Bold(val value: String) : MarkdownInline()
    data class InlineCode(val value: String) : MarkdownInline()
}

private val listItemRegex = Regex("[-*] (.+)")

/**
 * Strip C0 control characters (plus DEL) while keeping newlines and tabs, so raw
 * model output with stray control bytes cannot distort text layout. Pure function.
 */
internal fun stripControlCharacters(text: String): String {
    if (text.none { it < ' ' || it == '\u007F' }) return text
    return buildString(text.length) {
        for (c in text) {
            if ((c < ' ' && c != '\n' && c != '\t') || c == '\u007F') continue
            append(c)
        }
    }
}

/**
 * Parse [raw] into a list of [MarkdownBlock]. Blank input yields an empty list.
 * Pure function (see class doc for the supported subset and cleaning rules).
 */
internal fun parseMarkdown(raw: String): List<MarkdownBlock> {
    val text = stripControlCharacters(raw)
    if (text.isBlank()) return emptyList()

    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(parseMarkdownInlines(paragraphLines.joinToString("\n")))
            paragraphLines.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val content = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    content += lines[i]
                    i++
                }
                if (i < lines.size) i++ // skip the closing fence (or run to EOF when unclosed)
                blocks += buildCodeBlock(content)
            }
            listItemRegex.matches(trimmed) -> {
                flushParagraph()
                val content = listItemRegex.matchEntire(trimmed)!!.groupValues[1]
                blocks += MarkdownBlock.ListItem(parseMarkdownInlines(content))
                i++
            }
            trimmed.isBlank() -> {
                flushParagraph()
                i++
            }
            else -> {
                paragraphLines += line
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

private fun buildCodeBlock(content: List<String>): MarkdownBlock.CodeBlock =
    if (content.size > MAX_CODE_BLOCK_LINES) {
        MarkdownBlock.CodeBlock(
            lines = content.take(MAX_CODE_BLOCK_LINES) + CODE_TRUNCATION_SUFFIX,
            truncated = true,
        )
    } else {
        MarkdownBlock.CodeBlock(lines = content, truncated = false)
    }

/**
 * Parse inline markup: `code` spans first (their content is literal), then **bold**.
 * Unmatched markers stay literal text. Pure function.
 */
internal fun parseMarkdownInlines(text: String): List<MarkdownInline> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<MarkdownInline>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out += MarkdownInline.Text(plain.toString())
            plain.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end == -1) {
                    plain.append(c)
                    i++
                } else {
                    flushPlain()
                    out += MarkdownInline.InlineCode(text.substring(i + 1, end))
                    i = end + 1
                }
            }
            c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end == -1) {
                    plain.append("**")
                    i += 2
                } else {
                    flushPlain()
                    out += MarkdownInline.Bold(text.substring(i + 2, end))
                    i = end + 2
                }
            }
            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flushPlain()
    return out
}

// -------------------------------------------------------------------
// Compose rendering
// -------------------------------------------------------------------

/**
 * Renders parsed Markdown blocks as styled Compose text: bold spans become bold,
 * inline code gets a monospace span on the theme surface color, and fenced code
 * blocks render inside a monospace background container.
 */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownParagraph(block.inlines)
                is MarkdownBlock.ListItem -> MarkdownListItem(block.inlines)
                is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block)
            }
        }
    }
}

@Composable
private fun markdownBodyStyle() = typography.caption.copy(
    color = colors.onSurface,
    lineHeight = typography.caption.fontSize * 1.5f,
)

@Composable
private fun inlineAnnotatedString(inlines: List<MarkdownInline>): AnnotatedString {
    val titleColor = colors.textTitle
    val codeBackground = colors.surface
    return buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.Text -> append(inline.value)
                is MarkdownInline.Bold -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = titleColor),
                ) { append(inline.value) }
                is MarkdownInline.InlineCode -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                ) { append(inline.value) }
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(inlines: List<MarkdownInline>) {
    Text(text = inlineAnnotatedString(inlines), style = markdownBodyStyle())
}

@Composable
private fun MarkdownListItem(inlines: List<MarkdownInline>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "-",
            style = markdownBodyStyle().copy(color = colors.textMuted),
        )
        Text(
            text = inlineAnnotatedString(inlines),
            style = markdownBodyStyle(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.CodeBlock) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.borderDefault, shape)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            block.lines.forEach { line ->
                Text(
                    line,
                    style = typography.caption.copy(
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = typography.caption.fontSize * 1.35f,
                    ),
                )
            }
        }
    }
}

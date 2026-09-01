package scooper.ui.create

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for the self-built lightweight Markdown subset parser backing
 * [MarkdownText] (architecture proposal 5.5: paragraphs / bold / inline code /
 * fenced code blocks / unordered lists; everything else stays a plain text
 * paragraph; code blocks over 200 lines are truncated; control chars stripped).
 */
class MarkdownParserTest {

    // ---------------------------------------------------------------
    // Paragraphs
    // ---------------------------------------------------------------

    @Test
    fun `plain text becomes a single paragraph with one text inline`() {
        val blocks = parseMarkdown("Just a plain sentence.")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals(listOf(MarkdownInline.Text("Just a plain sentence.")), paragraph.inlines)
    }

    @Test
    fun `consecutive non-blank lines join into one paragraph preserving line breaks`() {
        val blocks = parseMarkdown("line one\nline two")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MarkdownInline.Text("line one\nline two")), paragraph.inlines)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val blocks = parseMarkdown("first paragraph\n\nsecond paragraph")
        assertEquals(2, blocks.size)
        assertEquals("first paragraph", (blocks[0] as MarkdownBlock.Paragraph).inlines.single().let { (it as MarkdownInline.Text).value })
        assertEquals("second paragraph", (blocks[1] as MarkdownBlock.Paragraph).inlines.single().let { (it as MarkdownInline.Text).value })
    }

    @Test
    fun `empty and blank inputs produce no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("   \n  \n"))
    }

    // ---------------------------------------------------------------
    // Bold and inline code
    // ---------------------------------------------------------------

    @Test
    fun `bold markers produce bold inline spans`() {
        val inlines = parseMarkdownInlines("**bold** text")
        assertEquals(
            listOf(MarkdownInline.Bold("bold"), MarkdownInline.Text(" text")),
            inlines,
        )
    }

    @Test
    fun `multiple bold spans split surrounding plain text`() {
        val inlines = parseMarkdownInlines("a **b** c **d**")
        assertEquals(
            listOf(
                MarkdownInline.Text("a "),
                MarkdownInline.Bold("b"),
                MarkdownInline.Text(" c "),
                MarkdownInline.Bold("d"),
            ),
            inlines,
        )
    }

    @Test
    fun `inline code markers produce inline code spans`() {
        val inlines = parseMarkdownInlines("run `scoop install fd` now")
        assertEquals(
            listOf(
                MarkdownInline.Text("run "),
                MarkdownInline.InlineCode("scoop install fd"),
                MarkdownInline.Text(" now"),
            ),
            inlines,
        )
    }

    @Test
    fun `unmatched bold markers stay literal plain text`() {
        val inlines = parseMarkdownInlines("**unclosed bold")
        assertEquals(listOf(MarkdownInline.Text("**unclosed bold")), inlines)
    }

    @Test
    fun `single asterisk without a pair stays literal`() {
        val inlines = parseMarkdownInlines("2 * 3 = 6")
        assertEquals(listOf(MarkdownInline.Text("2 * 3 = 6")), inlines)
    }

    @Test
    fun `unmatched backtick stays literal`() {
        val inlines = parseMarkdownInlines("a ` b")
        assertEquals(listOf(MarkdownInline.Text("a ` b")), inlines)
    }

    // ---------------------------------------------------------------
    // Fenced code blocks
    // ---------------------------------------------------------------

    @Test
    fun `fenced code block captures lines literally and drops the language tag`() {
        val blocks = parseMarkdown("```json\n{\"a\": 1}\n```")
        assertEquals(listOf(MarkdownBlock.CodeBlock(lines = listOf("{\"a\": 1}"), truncated = false)), blocks)
    }

    @Test
    fun `markdown markers inside a code block are not parsed as inline markup`() {
        val blocks = parseMarkdown("```\n**not bold** and `not code`\n```")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals(listOf("**not bold** and `not code`"), code.lines)
    }

    @Test
    fun `unclosed fence captures until end of input`() {
        val blocks = parseMarkdown("```\nabc\ndef")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals(listOf("abc", "def"), code.lines)
        assertFalse(code.truncated)
    }

    @Test
    fun `code blocks over 200 lines are truncated with the suffix line`() {
        val body = (1..250).joinToString("\n") { "line $it" }
        val blocks = parseMarkdown("```\n$body\n```")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertTrue(code.truncated)
        assertEquals(201, code.lines.size, "200 kept lines plus the truncation suffix")
        assertEquals("line 200", code.lines[199])
        assertEquals("...(truncated)", code.lines.last())
    }

    @Test
    fun `code block of exactly 200 lines is not truncated`() {
        val body = (1..200).joinToString("\n") { "line $it" }
        val blocks = parseMarkdown("```\n$body\n```")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertFalse(code.truncated)
        assertEquals(200, code.lines.size)
    }

    // ---------------------------------------------------------------
    // Unordered lists
    // ---------------------------------------------------------------

    @Test
    fun `dash and asterisk bullets become list items`() {
        val blocks = parseMarkdown("- first\n* second")
        assertEquals(
            listOf(
                MarkdownBlock.ListItem(inlines = listOf(MarkdownInline.Text("first"))),
                MarkdownBlock.ListItem(inlines = listOf(MarkdownInline.Text("second"))),
            ),
            blocks,
        )
    }

    @Test
    fun `list item content is parsed for inline markup`() {
        val blocks = parseMarkdown("- use `scoop` for **fast** installs")
        val item = blocks.single() as MarkdownBlock.ListItem
        assertEquals(
            listOf(
                MarkdownInline.Text("use "),
                MarkdownInline.InlineCode("scoop"),
                MarkdownInline.Text(" for "),
                MarkdownInline.Bold("fast"),
                MarkdownInline.Text(" installs"),
            ),
            item.inlines,
        )
    }

    @Test
    fun `dash without a space is not a list item`() {
        val blocks = parseMarkdown("-not a list")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MarkdownInline.Text("-not a list")), paragraph.inlines)
    }

    // ---------------------------------------------------------------
    // Mixed documents
    // ---------------------------------------------------------------

    @Test
    fun `mixed document keeps paragraph, list, code block and trailing paragraph in order`() {
        val input = "Intro with **bold** and `code`.\n\n- first\n- second\n\n```json\n{\"a\":1}\n```\nTrailing text"
        val blocks = parseMarkdown(input)
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.ListItem)
        assertTrue(blocks[2] is MarkdownBlock.ListItem)
        assertEquals(MarkdownBlock.CodeBlock(lines = listOf("{\"a\":1}"), truncated = false), blocks[3])
        assertTrue(blocks[4] is MarkdownBlock.Paragraph)
    }

    // ---------------------------------------------------------------
    // Cleaning: control characters
    // ---------------------------------------------------------------

    @Test
    fun `control characters are stripped while newline and tab survive`() {
        assertEquals("textmore", stripControlCharacters("text\u0000\u0007more"))
        assertEquals("a\tb\nc", stripControlCharacters("a\tb\nc"))
        assertEquals("clean", stripControlCharacters("\u001fclean\u007f"))
    }

    @Test
    fun `parseMarkdown strips control characters before parsing`() {
        val blocks = parseMarkdown("bro\u0000ken **wor\u0007d**")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(MarkdownInline.Text("broken "), MarkdownInline.Bold("word")),
            paragraph.inlines,
        )
    }
}

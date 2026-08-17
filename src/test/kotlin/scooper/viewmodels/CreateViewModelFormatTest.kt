package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TDD tests for [formatForBucket], the top-level pure function extracted from
 * CreateViewModel that normalizes a manifest to Scoop bucket CI file style:
 * 4-space indent, CRLF line endings, no trailing whitespace, exactly one trailing CRLF.
 */
class CreateViewModelFormatTest {

    private val compact = """{"version":"1.0","url":"https://example.com/app.zip"}"""

    // ---------------------------------------------------------------
    // indentation
    // ---------------------------------------------------------------

    @Test
    fun `two-space pretty JSON is reformatted to four-space indent`() {
        val input = """{
  "version": "1.0",
  "url": "https://example.com/app.zip"
}"""
        assertEquals(
            "{\r\n    \"version\": \"1.0\",\r\n    \"url\": \"https://example.com/app.zip\"\r\n}\r\n",
            formatForBucket(input),
        )
    }

    @Test
    fun `compact JSON becomes four-space pretty JSON with trailing CRLF`() {
        assertEquals(
            "{\r\n    \"version\": \"1.0\",\r\n    \"url\": \"https://example.com/app.zip\"\r\n}\r\n",
            formatForBucket(compact),
        )
    }

    // ---------------------------------------------------------------
    // line endings
    // ---------------------------------------------------------------

    @Test
    fun `LF line endings become CRLF`() {
        val input = "{\n  \"version\": \"1.0\"\n}"
        assertEquals("{\r\n    \"version\": \"1.0\"\r\n}\r\n", formatForBucket(input))
    }

    @Test
    fun `mixed CRLF and LF line endings are unified to CRLF`() {
        val input = "{\r\n  \"version\": \"1.0\"\n}"
        assertEquals("{\r\n    \"version\": \"1.0\"\r\n}\r\n", formatForBucket(input))
    }

    // ---------------------------------------------------------------
    // trailing newline
    // ---------------------------------------------------------------

    @Test
    fun `exactly one trailing CRLF is ensured when extra newlines exist`() {
        val input = "{\r\n    \"version\": \"1.0\"\r\n}\r\n\r\n"
        assertEquals("{\r\n    \"version\": \"1.0\"\r\n}\r\n", formatForBucket(input))
    }

    @Test
    fun `missing trailing newline gets exactly one CRLF appended`() {
        val input = "{\r\n    \"version\": \"1.0\"\r\n}"
        assertEquals("{\r\n    \"version\": \"1.0\"\r\n}\r\n", formatForBucket(input))
    }

    // ---------------------------------------------------------------
    // trailing whitespace
    // ---------------------------------------------------------------

    @Test
    fun `trailing whitespace is removed from each line`() {
        val input = "{\r\n    \"version\": \"1.0\"  \r\n}\t"
        assertEquals("{\r\n    \"version\": \"1.0\"\r\n}\r\n", formatForBucket(input))
    }

    // ---------------------------------------------------------------
    // idempotency / failure handling
    // ---------------------------------------------------------------

    @Test
    fun `already compliant input is idempotent`() {
        val compliant = "{\r\n    \"version\": \"1.0\"\r\n}\r\n"
        assertEquals(compliant, formatForBucket(compliant))
    }

    @Test
    fun `invalid JSON is returned unchanged`() {
        val bad = "this is not json {"
        assertEquals(bad, formatForBucket(bad))
    }

    @Test
    fun `blank input is returned unchanged`() {
        assertEquals("", formatForBucket(""))
        assertEquals("   ", formatForBucket("   "))
    }
}

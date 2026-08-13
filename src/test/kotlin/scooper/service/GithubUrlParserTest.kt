package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GithubUrlParserTest {

    private val expected = "https://github.com/stablyai/orca"

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://github.com/stablyai/orca",
            "http://github.com/stablyai/orca",
            "https://www.github.com/stablyai/orca",
            "github.com/stablyai/orca",
            "https://github.com/stablyai/orca/",
            "https://github.com/stablyai/orca.git",
            "https://github.com/stablyai/orca.git/",
            "https://github.com/stablyai/orca/tree/main",
            "https://github.com/stablyai/orca?tab=readme-ov-file",
            "https://github.com/stablyai/orca/tree/main?tab=readme-ov-file",
            "  https://github.com/stablyai/orca  ",
        ],
    )
    fun `extracts and normalizes stablyai orca variants`(input: String) {
        val (url, remaining) = GithubUrlParser.extract(input)
        assertEquals(expected, url)
        assertEquals("", remaining)
    }

    @Test
    fun `extracts url and keeps remaining free text as prompt`() {
        val (url, remaining) = GithubUrlParser.extract(
            "please use https://github.com/stablyai/orca prefer portable zip",
        )
        assertEquals(expected, url)
        assertEquals("please use prefer portable zip", remaining)
    }

    @Test
    fun `extracts bare github host without scheme from free text`() {
        val (url, remaining) = GithubUrlParser.extract("github.com/stablyai/orca GUI app")
        assertEquals(expected, url)
        assertEquals("GUI app", remaining)
    }

    @Test
    fun `returns null when no github repo url present`() {
        val (url, remaining) = GithubUrlParser.extract("just some notes without a link")
        assertNull(url)
        assertEquals("just some notes without a link", remaining)
    }

    @Test
    fun `returns null for empty input`() {
        val (url, remaining) = GithubUrlParser.extract("   ")
        assertNull(url)
        assertEquals("", remaining)
    }

    @Test
    fun `deriveAppName uses repo segment`() {
        assertEquals("orca", GithubUrlParser.deriveAppName(expected))
        assertEquals("orca", GithubUrlParser.deriveAppName("$expected/"))
    }
}

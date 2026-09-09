package scooper.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TDD tests for whitespace-separated token filtering in the model search box:
 * every token must appear in the item, case-insensitively, order free.
 */
class FilterByTokensTest {

    // ---------------------------------------------------------------
    // Blank query
    // ---------------------------------------------------------------

    @Test
    fun `blank query returns the original list`() {
        val items = listOf("gpt-4o", "commandcode/deepseek-free")

        assertEquals(items, filterByTokens(items, ""))
        assertEquals(items, filterByTokens(items, "   "))
    }

    // ---------------------------------------------------------------
    // Single token
    // ---------------------------------------------------------------

    @Test
    fun `single word filters items by case-insensitive substring`() {
        val items = listOf("gpt-4o", "commandcode/deepseek-free", "deepseek-chat")

        assertEquals(
            listOf("commandcode/deepseek-free", "deepseek-chat"),
            filterByTokens(items, "deepseek"),
        )
    }

    // ---------------------------------------------------------------
    // Multiple tokens (AND semantics)
    // ---------------------------------------------------------------

    @Test
    fun `multiple tokens require every token to appear`() {
        val items = listOf(
            "commandcode/deepseek-free",
            "commandcode/deepseek-pro",
            "openrouter/deepseek-free",
        )

        assertEquals(
            listOf("commandcode/deepseek-free"),
            filterByTokens(items, "commandcode free"),
        )
    }

    // ---------------------------------------------------------------
    // Case insensitivity
    // ---------------------------------------------------------------

    @Test
    fun `matching is case-insensitive in both query and item`() {
        val items = listOf("CommandCode/DeepSeek-Free")

        assertEquals(
            listOf("CommandCode/DeepSeek-Free"),
            filterByTokens(items, "COMMANDCODE Free"),
        )
    }

    // ---------------------------------------------------------------
    // Token order independence
    // ---------------------------------------------------------------

    @Test
    fun `token order does not affect the result`() {
        val items = listOf(
            "commandcode/deepseek-free",
            "commandcode/deepseek-pro",
            "openrouter/deepseek-free",
        )

        assertEquals(
            filterByTokens(items, "commandcode free"),
            filterByTokens(items, "free commandcode"),
        )
    }
}

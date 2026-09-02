package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * TDD tests for [lastUserQuestion]: the text of the newest user bubble, used by
 * the RETRY_ANSWER error action to resend a failed pure-question assist round.
 */
class CreateViewModelLastUserQuestionTest {

    // ---------------------------------------------------------------
    // lastUserQuestion: newest user bubble text
    // ---------------------------------------------------------------

    @Test
    fun `returns the text of the last user message`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "first question"),
            ChatMessage(role = ChatRole.Agent, text = "answer"),
            ChatMessage(role = ChatRole.User, text = "second question"),
            ChatMessage(role = ChatRole.Agent, text = "another answer"),
        )
        assertEquals("second question", lastUserQuestion(messages))
    }

    @Test
    fun `returns null when there is no user message`() {
        val agentOnly = listOf(
            ChatMessage(role = ChatRole.Agent, text = "hello"),
        )
        assertNull(lastUserQuestion(agentOnly))
        assertNull(lastUserQuestion(emptyList()))
    }

    @Test
    fun `returns AskUser confirmation text as-is`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.Agent, text = "Should I download?"),
            ChatMessage(
                role = ChatRole.User,
                text = "Let AI decide",
                note = "download detail",
                noteSingleLine = true,
            ),
        )
        assertEquals("Let AI decide", lastUserQuestion(messages))
    }
}

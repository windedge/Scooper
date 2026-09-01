package scooper.ui.create

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TDD tests for the shared chat LazyColumn item counting used by both the LazyColumn
 * content and the auto-scroll itemCount: the scroll target index must always be a
 * valid item index. The manifest preview card joins the chat list only in the
 * single-column (narrow) layout; the wide layout shows it in the right pane.
 */
class CreateScreenChatListTest {

    // ---------------------------------------------------------------
    // wide layout: no manifest card in the chat list
    // ---------------------------------------------------------------

    @Test
    fun `wide layout counts messages and optional cards without the manifest panel`() {
        assertEquals(
            3,
            chatListItemCount(
                messageCount = 2,
                hasActiveExecution = false,
                hasAskUserRequest = true,
                includeManifestCard = false,
                manifestJsonNotBlank = true,
            ),
            "wide layout: the manifest preview lives in the right pane and must not be counted",
        )
    }

    // ---------------------------------------------------------------
    // narrow layout: manifest card only when a manifest exists
    // ---------------------------------------------------------------

    @Test
    fun `narrow layout counts the manifest card only when a manifest exists`() {
        assertEquals(
            4,
            chatListItemCount(
                messageCount = 2,
                hasActiveExecution = false,
                hasAskUserRequest = true,
                includeManifestCard = true,
                manifestJsonNotBlank = true,
            ),
            "messages + ask-user card + manifest card",
        )
        assertEquals(
            3,
            chatListItemCount(
                messageCount = 3,
                hasActiveExecution = false,
                hasAskUserRequest = false,
                includeManifestCard = true,
                manifestJsonNotBlank = false,
            ),
            "blank manifest adds no card",
        )
    }

    // ---------------------------------------------------------------
    // active execution card: counted whenever present (running included)
    // ---------------------------------------------------------------

    @Test
    fun `active execution card is counted while running`() {
        assertEquals(
            1,
            chatListItemCount(
                messageCount = 0,
                hasActiveExecution = true,
                hasAskUserRequest = false,
                includeManifestCard = false,
                manifestJsonNotBlank = false,
            ),
            "running state must still target the active execution card",
        )
    }

    @Test
    fun `empty chat counts zero items`() {
        assertEquals(
            0,
            chatListItemCount(
                messageCount = 0,
                hasActiveExecution = false,
                hasAskUserRequest = false,
                includeManifestCard = true,
                manifestJsonNotBlank = false,
            ),
        )
    }
}

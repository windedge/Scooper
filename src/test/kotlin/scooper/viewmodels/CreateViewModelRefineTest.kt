package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [isRefineRequest] pure function and refine routing logic.
 */
class CreateViewModelRefineTest {

    @Test
    fun `isRefineRequest returns true when manifest exists, note is present and url is blank`() {
        val result = isRefineRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "",
            inputNote = "Please remove arm64 support",
        )
        assertTrue(result, "Should route to refine when URL is blank and note is provided")
    }

    @Test
    fun `isRefineRequest returns true when manifest exists, note is present and url matches lastUrl`() {
        val result = isRefineRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "https://github.com/sharkdp/fd",
            inputNote = "Add start menu shortcut",
        )
        assertTrue(result, "Should route to refine when URL matches last generated URL")
    }

    @Test
    fun `isRefineRequest returns false when no manifest exists yet`() {
        val result = isRefineRequest(
            hasExistingManifest = false,
            lastUrl = "",
            inputUrl = "https://github.com/sharkdp/fd",
            inputNote = "prefer portable",
        )
        assertFalse(result, "Should not route to refine when no manifest exists")
    }

    @Test
    fun `isRefineRequest returns false when user inputs a brand new different url`() {
        val result = isRefineRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "https://github.com/BurntSushi/ripgrep",
            inputNote = "prefer portable",
        )
        assertFalse(result, "Should start fresh generation when new URL is given")
    }

    @Test
    fun `isRefineRequest returns false when note is blank`() {
        val result = isRefineRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "https://github.com/sharkdp/fd",
            inputNote = "   ",
        )
        assertFalse(result, "Should not refine when note is blank")
    }

    @Test
    fun `isRefineRequest returns true when manifest exists and instruction is entered in url field`() {
        val result = isRefineRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "去掉notes字段",
            inputNote = "",
        )
        assertTrue(result, "Should route to refine when user types natural language in url field")
        assertEquals("去掉notes字段", resolveRefineInstruction("去掉notes字段", ""))
    }

    @Test
    fun `CreateState isEmpty returns true when fresh and false when has messages or manifest`() {
        val emptyState = CreateState()
        assertTrue(emptyState.isEmpty)

        val withMessage = CreateState(messages = listOf(ChatMessage(role = ChatRole.User, text = "test")))
        assertFalse(withMessage.isEmpty)

        val withManifest = CreateState(manifestJson = """{"version":"1.0"}""")
        assertFalse(withManifest.isEmpty)
    }

    @Test
    fun `AskUserRequest creates unique id and stores question`() {
        val request1 = AskUserRequest(question = "Pick architecture")
        val request2 = AskUserRequest(question = "Pick architecture")
        assertTrue(request1.id.isNotBlank())
        assertTrue(request2.id.isNotBlank())
        assertTrue(request1.id != request2.id, "Each request should have a unique ID")
        assertEquals("Pick architecture", request1.question)
    }

    @Test
    fun `AskUserRequest isDownloadPrompt detects download questions correctly`() {
        val dlReq1 = AskUserRequest(
            questions = listOf(QuestionnaireQuestion(id = "download_confirm", prompt = "Do you want to proceed?")),
        )
        assertTrue(dlReq1.isDownloadPrompt)

        val dlReq2 = AskUserRequest(
            questions = listOf(QuestionnaireQuestion(id = "q1", prompt = "Download and inspect installer/archive?")),
        )
        assertTrue(dlReq2.isDownloadPrompt)

        val normalReq = AskUserRequest(
            questions = listOf(QuestionnaireQuestion(id = "q1", prompt = "Which architecture do you prefer?")),
        )
        assertFalse(normalReq.isDownloadPrompt)
    }

    @Test
    fun `resolveAskUserBubbleText formats download confirmation and normal text`() {
        assertEquals("Download confirmed", resolveAskUserBubbleText("yes", isDownloadPrompt = true))
        assertEquals("Download confirmed", resolveAskUserBubbleText("Proceed with download", isDownloadPrompt = true))
        assertEquals("Download skipped", resolveAskUserBubbleText("no", isDownloadPrompt = true))
        assertEquals("Download skipped", resolveAskUserBubbleText("skip", isDownloadPrompt = true))
        assertEquals("custom text", resolveAskUserBubbleText("custom text", isDownloadPrompt = true))
        assertEquals("yes", resolveAskUserBubbleText("yes", isDownloadPrompt = false))
    }

    @Test
    fun `extractUserHistory filters out questionnaire responses and preserves user prompts`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "https://github.com/sharkdp/fd", note = "prefer portable"),
            ChatMessage(role = ChatRole.Agent, text = "Starting..."),
            ChatMessage(role = ChatRole.User, text = "Download confirmed"),
            ChatMessage(role = ChatRole.User, text = "Let AI decide"),
            ChatMessage(role = ChatRole.User, text = "Question: user selected: 1. x64"),
            ChatMessage(role = ChatRole.User, text = "Please remove notes"),
        )
        val history = extractUserHistory(messages)
        assertEquals(2, history.size)
        assertEquals("https://github.com/sharkdp/fd (Requirements: prefer portable)", history[0])
        assertEquals("Please remove notes", history[1])
    }

    @Test
    fun `isLikelyUrl detects GitHub and http URLs`() {
        assertTrue(isLikelyUrl("https://github.com/owner/repo"))
        assertTrue(isLikelyUrl("http://example.com/app.zip"))
        assertTrue(isLikelyUrl("github.com/owner/repo"))
        assertFalse(isLikelyUrl("remove shortcuts"))
        assertFalse(isLikelyUrl("   "))
    }

    @Test
    fun `computeManifestDiff returns Initial manifest when previous is blank`() {
        val newJson = """{"version":"1.0.0","description":"test"}"""
        val diff = computeManifestDiff("", newJson)
        assertEquals(listOf("Initial manifest"), diff)
    }

    @Test
    fun `computeManifestDiff detects added, removed, and modified keys`() {
        val oldJson = """{"version":"1.0.0","description":"test","bin":["app.exe"],"notes":"some notes"}"""
        val newJson = """{"version":"1.1.0","description":"test","shortcuts":[["app.exe","App"]]}"""
        val diff = computeManifestDiff(oldJson, newJson)
        assertTrue(diff.any { it.contains("~ version") }, "Should detect modified version")
        assertTrue(diff.any { it.contains("- bin") }, "Should detect removed bin")
        assertTrue(diff.any { it.contains("- notes") }, "Should detect removed notes")
        assertTrue(diff.any { it.contains("+ shortcuts") }, "Should detect added shortcuts")
    }

    @Test
    fun `computeManifestDiff returns empty list when identical`() {
        val json = """{"version":"1.0.0","description":"test"}"""
        val diff = computeManifestDiff(json, json)
        assertTrue(diff.isEmpty())
    }

    @Test
    fun `extractReviewActionChips generates appropriate chips for name conflict and invalid json`() {
        val issues = listOf("Conflict: app name 'micro' already exists in official bucket")
        val chips = extractReviewActionChips(issues = issues, invalidJson = false, testResult = null)
        assertTrue(chips.any { it.label.contains("Fix name conflict") }, "Should have name conflict chip")
    }

    @Test
    fun `extractReviewActionChips generates chips for persist and license issues`() {
        val issues = listOf(
            "Illegal persist path: C:\\data must be relative",
            "License bare GPL-3.0 should use explicit suffix",
        )
        val chips = extractReviewActionChips(issues = issues, invalidJson = false, testResult = null)
        assertTrue(chips.any { it.label.contains("Fix persist") }, "Should have persist chip")
        assertTrue(chips.any { it.label.contains("Fix license") }, "Should have license chip")
    }

    @Test
    fun `extractReviewActionChips generates chip for invalid JSON`() {
        val chips = extractReviewActionChips(issues = emptyList(), invalidJson = true, testResult = null)
        assertTrue(chips.any { it.label.contains("Fix JSON") }, "Should have JSON fix chip")
    }

    @Test
    fun `extractReviewActionChips generates primary test fix chip when test failed`() {
        val failedResult = TestResult(testName = "test", success = false, errorMessage = "Failed to run test", log = "exit code 1")
        val chips = extractReviewActionChips(issues = emptyList(), invalidJson = false, testResult = failedResult)
        assertTrue(chips.any { it.isPrimary && it.label.contains("Fix install failure") }, "Should have primary test fix chip")
    }
}

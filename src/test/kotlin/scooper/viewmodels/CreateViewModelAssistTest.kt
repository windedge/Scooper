package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.service.AiErrorAction

/**
 * Unit tests for [isAssistRequest] pure function and assist routing logic.
 */
class CreateViewModelAssistTest {

    @Test
    fun `isAssistRequest returns true when manifest exists, note is present and url is blank`() {
        val result = isAssistRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "",
            inputNote = "Please remove arm64 support",
        )
        assertTrue(result, "Should route to assist when URL is blank and note is provided")
    }

    @Test
    fun `isAssistRequest returns true when manifest exists, note is present and url matches lastUrl`() {
        val result = isAssistRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "https://github.com/sharkdp/fd",
            inputNote = "Add start menu shortcut",
        )
        assertTrue(result, "Should route to assist when URL matches last generated URL")
    }

    @Test
    fun `isAssistRequest returns false when no manifest exists yet`() {
        val result = isAssistRequest(
            hasExistingManifest = false,
            lastUrl = "",
            inputUrl = "https://github.com/sharkdp/fd",
            inputNote = "prefer portable",
        )
        assertFalse(result, "Should not route to assist when no manifest exists")
    }

    @Test
    fun `isAssistRequest returns false when user inputs a brand new different url`() {
        val result = isAssistRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "https://github.com/BurntSushi/ripgrep",
            inputNote = "prefer portable",
        )
        assertFalse(result, "Should start fresh generation when new URL is given")
    }

    @Test
    fun `isAssistRequest returns false when note is blank`() {
        val result = isAssistRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "https://github.com/sharkdp/fd",
            inputNote = "   ",
        )
        assertFalse(result, "Should not assist when note is blank")
    }

    @Test
    fun `isAssistRequest returns true when manifest exists and instruction is entered in url field`() {
        val result = isAssistRequest(
            hasExistingManifest = true,
            lastUrl = "https://github.com/sharkdp/fd",
            inputUrl = "去掉notes字段",
            inputNote = "",
        )
        assertTrue(result, "Should route to assist when user types natural language in url field")
        assertEquals("去掉notes字段", resolveAssistInstruction("去掉notes字段", ""))
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
        assertEquals(3, history.size)
        assertEquals("User: https://github.com/sharkdp/fd (Requirements: prefer portable)", history[0])
        assertEquals("Assistant: Starting...", history[1])
        assertEquals("User: Please remove notes", history[2])
    }

    @Test
    fun `extractUserHistory interleaves user and assistant turns in chronological order`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "Do we need persist?"),
            ChatMessage(role = ChatRole.Agent, text = "No, persist is not required here."),
            ChatMessage(role = ChatRole.User, text = "Add it anyway and see"),
        )
        val history = extractUserHistory(messages)
        assertEquals(
            listOf(
                "User: Do we need persist?",
                "Assistant: No, persist is not required here.",
                "User: Add it anyway and see",
            ),
            history,
        )
    }

    @Test
    fun `extractUserHistory excludes agent error bubbles that carry actions`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "remove notes"),
            ChatMessage(
                role = ChatRole.Agent,
                text = "Assist failed: upstream timeout",
                actions = listOf(AiErrorAction.RETRY_FULL),
            ),
            ChatMessage(role = ChatRole.User, text = "try again"),
        )
        val history = extractUserHistory(messages)
        assertEquals(listOf("User: remove notes", "User: try again"), history)
    }

    @Test
    fun `extractUserHistory excludes agent bubbles with blank text`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "remove notes"),
            ChatMessage(role = ChatRole.Agent, text = "   "),
            ChatMessage(role = ChatRole.User, text = "and shortcuts too"),
        )
        val history = extractUserHistory(messages)
        assertEquals(listOf("User: remove notes", "User: and shortcuts too"), history)
    }

    @Test
    fun `extractUserHistory keeps only the last 12 entries after filtering`() {
        val messages = (1..15).map { i ->
            if (i % 2 == 1) ChatMessage(role = ChatRole.User, text = "u$i")
            else ChatMessage(role = ChatRole.Agent, text = "a$i")
        }
        val history = extractUserHistory(messages)
        assertEquals(12, history.size)
        assertEquals("Assistant: a4", history.first())
        assertEquals("User: u15", history.last())
    }

    @Test
    fun `extractUserHistory truncates a single entry beyond 400 chars`() {
        val longText = "x".repeat(500)
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = longText, note = "note"),
            ChatMessage(role = ChatRole.Agent, text = "y".repeat(500)),
        )
        val history = extractUserHistory(messages)
        assertEquals(2, history.size)
        history.forEach { line ->
            assertEquals(400, line.length)
            assertTrue(line.endsWith("..."))
        }
        assertTrue(history[0].startsWith("User: "))
        assertTrue(history[1].startsWith("Assistant: "))
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

    @Test
    fun `computeDetailedLineDiff marks all lines as added when old text is blank`() {
        val newText = "{\n  \"version\": \"1.0.0\"\n}"
        val diff = computeDetailedLineDiff("", newText)
        assertEquals(3, diff.size)
        assertTrue(diff.all { it.type == DiffLineType.ADDED })
    }

    @Test
    fun `computeDetailedLineDiff marks all lines unchanged when texts match`() {
        val text = "{\n  \"version\": \"1.0.0\"\n}"
        val diff = computeDetailedLineDiff(text, text)
        assertEquals(3, diff.size)
        assertTrue(diff.all { it.type == DiffLineType.UNCHANGED })
    }

    @Test
    fun `computeDetailedLineDiff detects added and deleted lines`() {
        val oldText = "line1\nline2\nline3"
        val newText = "line1\nline2-modified\nline3\nline4"
        val diff = computeDetailedLineDiff(oldText, newText)
        assertTrue(diff.any { it.type == DiffLineType.DELETED && it.text == "line2" })
        assertTrue(diff.any { it.type == DiffLineType.ADDED && it.text == "line2-modified" })
        assertTrue(diff.any { it.type == DiffLineType.ADDED && it.text == "line4" })
    }

    // ---------------------------------------------------------------
    // isAssistScenario: assist-phase classification
    // ---------------------------------------------------------------

    private val revisionsOne = listOf(
        ManifestRevision(revisionNumber = 1, source = RevisionSource.ASSIST, manifestJson = "{}"),
    )

    @Test
    fun `isAssistScenario true for assist phase with existing revisions`() {
        assertTrue(isAssistScenario(currentPhase = "assist", revisions = revisionsOne))
    }

    @Test
    fun `isAssistScenario false for repair phase`() {
        assertFalse(isAssistScenario(currentPhase = "repair", revisions = revisionsOne))
    }

    @Test
    fun `isAssistScenario false for generate phase`() {
        assertFalse(isAssistScenario(currentPhase = "generate", revisions = revisionsOne))
    }

    // ---------------------------------------------------------------
    // answered bubble fallback text
    // ---------------------------------------------------------------

    @Test
    fun `answered fallback uses generic English when explanation is null`() {
        assertEquals(
            "Sorry, I could not produce an answer.",
            answeredBubbleText(null),
        )
    }

    @Test
    fun `answered fallback uses generic English when explanation is blank`() {
        assertEquals(
            "Sorry, I could not produce an answer.",
            answeredBubbleText("   \n  "),
        )
    }

    @Test
    fun `answered fallback returns the original explanation`() {
        assertEquals(
            "The name shortens to a portable zip.",
            answeredBubbleText("The name shortens to a portable zip."),
        )
    }
}

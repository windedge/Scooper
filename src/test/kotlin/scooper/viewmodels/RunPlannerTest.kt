package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.service.AiErrorAction

// ---------------------------------------------------------------
// RunPlanning: the pure planning seam of the three Create starts
// (S2 step 5). planSend covers the onSend decision block,
// planGenerate the startGeneration decision block and planAssist
// the startAssistInternal one. The view model keeps only the
// intent shell, the epoch, the reduce and the bridge calls.
// ---------------------------------------------------------------
class RunPlannerTest {

    private fun image(id: String) = PendingImage(id = id, bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")

    private fun userMessage(text: String, images: List<PendingImage> = emptyList(), replayed: Boolean = false) =
        ChatMessage(role = ChatRole.User, text = text, images = images, imagesReplayed = replayed)

    private fun agentMessage(text: String) = ChatMessage(role = ChatRole.Agent, text = text)

    // -------------------------------------------------------------
    // planSend
    // -------------------------------------------------------------

    @Test
    fun `send with a github url plans a generation`() {
        val decision = planSend(
            input = "https://github.com/sharkdp/fd",
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        )
        assertTrue(decision is SendDecision.Generate, "a github url must generate, got: $decision")
        decision as SendDecision.Generate
        assertEquals("https://github.com/sharkdp/fd", decision.url)
        assertEquals("", decision.note)
    }

    @Test
    fun `send with url plus trailing text plans a generation with the note`() {
        val decision = planSend(
            input = "https://github.com/sharkdp/fd prefer portable",
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        ) as SendDecision.Generate
        assertEquals("https://github.com/sharkdp/fd", decision.url)
        assertEquals("prefer portable", decision.note)
    }

    @Test
    fun `send without a github url plans an assist`() {
        val decision = planSend(
            input = "Please remove arm64 support",
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        )
        assertTrue(decision is SendDecision.Assist, "plain text must assist, got: $decision")
        assertEquals("Please remove arm64 support", (decision as SendDecision.Assist).instruction)
    }

    @Test
    fun `send with a non github url plans an assist instead of a failed generation`() {
        val decision = planSend(
            input = "http://example.com/app.zip",
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        ) as SendDecision.Assist
        assertEquals("http://example.com/app.zip", decision.instruction)
    }

    @Test
    fun `pure image send plans an assist with the fixed image instruction`() {
        val attached = listOf(image("a.png"))
        val decision = planSend(
            input = null,
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = false,
            images = attached,
        ) as SendDecision.Assist
        assertEquals(CreateViewModel.PURE_IMAGE_INSTRUCTION, decision.instruction)
        assertEquals(attached, decision.images)
    }

    @Test
    fun `blank send without images is ignored`() {
        val decision = planSend(
            input = "   ",
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        )
        assertEquals(SendDecision.Ignored, decision)
    }

    @Test
    fun `pending questionnaire routes the text to the answer path`() {
        val decision = planSend(
            input = "x64",
            note = null,
            url = null,
            composerText = "",
            askUserPending = true,
            busy = false,
            images = emptyList(),
        ) as SendDecision.AnswerAskUser
        assertEquals("x64", decision.answer)
    }

    @Test
    fun `pending questionnaire ignores a blank answer`() {
        val decision = planSend(
            input = "",
            note = null,
            url = null,
            composerText = "",
            askUserPending = true,
            busy = false,
            images = emptyList(),
        )
        assertEquals(SendDecision.Ignored, decision)
    }

    @Test
    fun `busy state blocks the send`() {
        val decision = planSend(
            input = "https://github.com/sharkdp/fd",
            note = null,
            url = null,
            composerText = "",
            askUserPending = false,
            busy = true,
            images = emptyList(),
        )
        assertEquals(SendDecision.Busy, decision)
    }

    @Test
    fun `send prefers the explicit argument over the composer draft`() {
        val decision = planSend(
            input = null,
            note = "Add a shortcut",
            url = null,
            composerText = "stale draft",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        ) as SendDecision.Assist
        assertEquals("Add a shortcut", decision.instruction)
    }

    @Test
    fun `send falls back to the composer draft when no argument is given`() {
        val decision = planSend(
            input = null,
            note = null,
            url = null,
            composerText = "composer draft",
            askUserPending = false,
            busy = false,
            images = emptyList(),
        ) as SendDecision.Assist
        assertEquals("composer draft", decision.instruction)
    }

    // -------------------------------------------------------------
    // planGenerate
    // -------------------------------------------------------------

    @Test
    fun `planGenerate returns null when the input has no github url`() {
        assertNull(
            planGenerate(rawUrlInput = "not a repo url", noteInput = ""),
            "a url-less input must be rejected by the planner, not started",
        )
    }

    @Test
    fun `planGenerate derives the url app guess and research phase`() {
        val plan = planGenerate(
            rawUrlInput = "https://github.com/sharkdp/fd",
            noteInput = "",
        )!!
        assertEquals("https://github.com/sharkdp/fd", plan.url)
        assertEquals("fd", plan.appGuess)
        assertEquals("research", plan.initialPhase)
        assertEquals("Researching fd...", plan.phaseTitle)
        assertNull(plan.userPrompt)
        assertTrue(plan.ignoredNames.isEmpty(), "no saved app means nothing to exclude")
    }

    @Test
    fun `planGenerate starts at the retried phase`() {
        assertEquals(
            "analyze",
            planGenerate("https://github.com/sharkdp/fd", "", resumeFrom = AiErrorAction.RETRY_FROM_ANALYZE)?.initialPhase,
        )
        assertEquals(
            "generate",
            planGenerate("https://github.com/sharkdp/fd", "", resumeFrom = AiErrorAction.RETRY_FROM_GENERATE)?.initialPhase,
        )
        assertEquals(
            "research",
            planGenerate("https://github.com/sharkdp/fd", "", resumeFrom = AiErrorAction.RETRY_FULL)?.initialPhase,
        )
    }

    @Test
    fun `planGenerate merges the field note into the user prompt`() {
        val plan = planGenerate(
            rawUrlInput = "https://github.com/sharkdp/fd prefer portable",
            noteInput = "and add shortcuts",
        )!!
        assertEquals("prefer portable and add shortcuts", plan.userPrompt)
    }

    @Test
    fun `planGenerate excludes the saved app name from the conflict check`() {
        val plan = planGenerate(
            rawUrlInput = "https://github.com/sharkdp/fd",
            noteInput = "",
            savedAppName = "fd",
        )!!
        assertEquals(setOf("fd"), plan.ignoredNames)
    }

    @Test
    fun `planGenerate forwards the attached images`() {
        val attached = listOf(image("shot.png"))
        val plan = planGenerate(
            rawUrlInput = "https://github.com/sharkdp/fd",
            noteInput = "",
            images = attached,
        )!!
        assertEquals(attached, plan.images)
    }

    // -------------------------------------------------------------
    // planAssist
    // -------------------------------------------------------------

    @Test
    fun `planAssist returns null for a blank instruction`() {
        assertNull(
            planAssist(
                instruction = "  ",
                displayUserText = null,
                manifestJson = "{}",
                messages = emptyList(),
                images = emptyList(),
                suggestedAppName = null,
                savedAppName = null,
                attempt = 1,
            ),
            "a blank instruction must not start a round",
        )
    }

    @Test
    fun `planAssist trims the history to the recent turns`() {
        val messages = (1..15).map { userMessage("turn $it") }
        val plan = planAssist(
            instruction = "turn 16",
            displayUserText = null,
            manifestJson = "{}",
            messages = messages,
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertEquals(12, plan.history.size, "history must be capped at the tail window")
        assertEquals("User: turn 4", plan.history.first())
        assertEquals("User: turn 15", plan.history.last())
    }

    @Test
    fun `planAssist replays the newest unreplayed images on a text only round`() {
        val shot = image("shot.png")
        val messages = listOf(
            userMessage("see the error", images = listOf(shot)),
            agentMessage("Looks like a missing shortcut."),
        )
        val plan = planAssist(
            instruction = "fix it",
            displayUserText = null,
            manifestJson = "{}",
            messages = messages,
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertEquals(listOf(shot), plan.images)
        assertEquals(messages.first().id, plan.replaySourceId, "the replayed bubble must be flagged after the send")
    }

    @Test
    fun `planAssist skips images that were already replayed`() {
        val shot = image("shot.png")
        val messages = listOf(userMessage("see the error", images = listOf(shot), replayed = true))
        val plan = planAssist(
            instruction = "fix it",
            displayUserText = null,
            manifestJson = "{}",
            messages = messages,
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertTrue(plan.images.isEmpty(), "a replayed screenshot must not be sent twice")
        assertNull(plan.replaySourceId)
    }

    @Test
    fun `planAssist keeps freshly attached images over the replay`() {
        val fresh = image("fresh.png")
        val messages = listOf(userMessage("see the error", images = listOf(image("old.png"))))
        val plan = planAssist(
            instruction = "fix it",
            displayUserText = null,
            manifestJson = "{}",
            messages = messages,
            images = listOf(fresh),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertEquals(listOf(fresh), plan.images)
        assertNull(plan.replaySourceId, "a round with fresh images replays nothing")
    }

    @Test
    fun `planAssist registers manifest references when a user bubble carries one`() {
        val referenced = listOf(userMessage("compare with @main/fd"))
        val withRef = planAssist(
            instruction = "also add shortcuts",
            displayUserText = null,
            manifestJson = "{}",
            messages = referenced,
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertTrue(withRef.hasManifestRefs, "a referenced bucket app must register the read tool")

        val withoutRef = planAssist(
            instruction = "also add shortcuts",
            displayUserText = null,
            manifestJson = "{}",
            messages = listOf(userMessage("no reference here")),
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertFalse(withoutRef.hasManifestRefs, "with no references the read tool stays absent")
    }

    @Test
    fun `planAssist detects a reference typed in this round`() {
        val plan = planAssist(
            instruction = "mirror @extras/vscode",
            displayUserText = null,
            manifestJson = "{}",
            messages = emptyList(),
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = null,
            attempt = 1,
        )!!
        assertTrue(plan.hasManifestRefs, "this round's own message counts as a reference")
    }

    @Test
    fun `planAssist keeps the chip display text and the current manifest`() {
        val manifest = """{"version":"1.0.0"}"""
        val plan = planAssist(
            instruction = "remove the notes field",
            displayUserText = "⚡ Apply fix: Drop notes",
            manifestJson = manifest,
            messages = listOf(userMessage("hello")),
            images = emptyList(),
            suggestedAppName = "fd",
            savedAppName = "fd",
            attempt = 2,
        )!!
        assertEquals("remove the notes field", plan.instruction)
        assertEquals("⚡ Apply fix: Drop notes", plan.userPrompt)
        assertEquals(manifest, plan.currentManifest)
        assertEquals(setOf("fd"), plan.ignoredNames)
        assertEquals("Working on your request...", plan.phaseTitle)
    }
}

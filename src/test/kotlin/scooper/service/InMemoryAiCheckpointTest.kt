package scooper.service

import com.scoopai.core.artifacts.CheckpointResume.ResumeStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for InMemoryAiCheckpoint: the pure functions
 * (resumableStagesOf / withPhaseOutput / buildResumeData) plus the stateful
 * service lifecycle (beginRun / update / snapshot / reset), which back the
 * "Retry from X" recovery actions after a mid-phase AI failure.
 */
class InMemoryAiCheckpointTest {

    private val researchJson = """{"owner":"owner","repo":"repo","suggested_app_name":"app"}"""
    private val analyzeJson = """{"bin":["app.exe"],"url":"https://example.com/app.zip"}"""

    // ---------------------------------------------------------------
    // resumableStagesOf (three states)
    // ---------------------------------------------------------------

    @Test
    fun `resumableStagesOf with null checkpoint is empty`() {
        assertTrue(resumableStagesOf(null).isEmpty())
    }

    @Test
    fun `resumableStagesOf with only research allows analyze resume`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = researchJson,
        )
        assertEquals(setOf(AiErrorAction.RETRY_FROM_ANALYZE), resumableStagesOf(cp))
    }

    @Test
    fun `resumableStagesOf with research and analyze allows both resumes`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = researchJson,
            analyzeJson = analyzeJson,
        )
        assertEquals(
            setOf(AiErrorAction.RETRY_FROM_ANALYZE, AiErrorAction.RETRY_FROM_GENERATE),
            resumableStagesOf(cp),
        )
    }

    // ---------------------------------------------------------------
    // withPhaseOutput
    // ---------------------------------------------------------------

    @Test
    fun `withPhaseOutput research stores the research JSON`() {
        val cp = InMemoryAiCheckpoint(url = "https://github.com/o/r", userPrompt = "")
        val out = cp.withPhaseOutput("research", researchJson)
        assertEquals(researchJson, out.researchJson)
        assertNull(out.analyzeJson)
    }

    @Test
    fun `withPhaseOutput analyze stores the analyze JSON`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = researchJson,
        )
        val out = cp.withPhaseOutput("analyze", analyzeJson)
        assertEquals(researchJson, out.researchJson)
        assertEquals(analyzeJson, out.analyzeJson)
    }

    @Test
    fun `withPhaseOutput ignores generate and refine phases`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = researchJson,
        )
        assertSame(cp, cp.withPhaseOutput("generate", """{"name":"app"}"""))
        assertSame(cp, cp.withPhaseOutput("refine", """{"name":"app"}"""))
    }

    // ---------------------------------------------------------------
    // buildResumeData
    // ---------------------------------------------------------------

    @Test
    fun `buildResumeData ANALYZE works with research only`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = researchJson,
        )
        val data = buildResumeData(cp, AiErrorAction.RETRY_FROM_ANALYZE, runId = "run-1")
        assertNotNull(data)
        assertEquals(ResumeStage.ANALYZE, data!!.fromStage)
        assertEquals("run-1", data.runId)
    }

    @Test
    fun `buildResumeData GENERATE without analyze returns null`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = researchJson,
        )
        assertNull(buildResumeData(cp, AiErrorAction.RETRY_FROM_GENERATE, runId = "run-1"))
    }

    @Test
    fun `buildResumeData with corrupt research JSON returns null`() {
        val cp = InMemoryAiCheckpoint(
            url = "https://github.com/o/r",
            userPrompt = "",
            researchJson = "not-json{",
        )
        assertNull(buildResumeData(cp, AiErrorAction.RETRY_FROM_ANALYZE, runId = "run-1"))
    }

    @Test
    fun `buildResumeData with null checkpoint returns null`() {
        assertNull(buildResumeData(null, AiErrorAction.RETRY_FROM_ANALYZE, runId = "run-1"))
    }

    // ---------------------------------------------------------------
    // resolveErrorActions
    // ---------------------------------------------------------------

    @Test
    fun `resolveErrorActions keeps RETRY_FROM stages when resumable`() {
        val actions = listOf(
            AiErrorAction.RETRY_FULL,
            AiErrorAction.RETRY_FROM_ANALYZE,
            AiErrorAction.RETRY_FROM_GENERATE,
        )
        val resolved = resolveErrorActions(
            actions,
            resumableStages = setOf(AiErrorAction.RETRY_FROM_ANALYZE, AiErrorAction.RETRY_FROM_GENERATE),
        )
        assertEquals(actions, resolved)
    }

    @Test
    fun `resolveErrorActions filters non-resumable RETRY_FROM stages`() {
        val actions = listOf(
            AiErrorAction.RETRY_FULL,
            AiErrorAction.RETRY_FROM_ANALYZE,
            AiErrorAction.RETRY_FROM_GENERATE,
        )
        val resolved = resolveErrorActions(
            actions,
            resumableStages = setOf(AiErrorAction.RETRY_FROM_ANALYZE),
        )
        assertEquals(listOf(AiErrorAction.RETRY_FULL, AiErrorAction.RETRY_FROM_ANALYZE), resolved)
    }

    @Test
    fun `resolveErrorActions drops all RETRY_FROM stages when nothing resumable`() {
        val actions = listOf(
            AiErrorAction.RETRY_FULL,
            AiErrorAction.RETRY_FROM_ANALYZE,
            AiErrorAction.RETRY_FROM_GENERATE,
        )
        val resolved = resolveErrorActions(actions, resumableStages = emptySet())
        assertEquals(listOf(AiErrorAction.RETRY_FULL), resolved)
    }

    // ---------------------------------------------------------------
    // service lifecycle (beginRun / update / snapshot / reset)
    // ---------------------------------------------------------------

    @Test
    fun `service update accumulates phase outputs after beginRun`() {
        val svc = InMemoryAiCheckpointService()
        svc.beginRun("https://github.com/o/r", "note")
        svc.update("research", researchJson)
        svc.update("analyze", analyzeJson)
        val cp = svc.snapshot()
        assertEquals(researchJson, cp?.researchJson)
        assertEquals(analyzeJson, cp?.analyzeJson)
    }

    @Test
    fun `service beginRun resets to a fresh instance`() {
        val svc = InMemoryAiCheckpointService()
        svc.beginRun("https://github.com/o/r", "note")
        svc.update("research", researchJson)
        svc.update("analyze", analyzeJson)
        svc.beginRun("https://github.com/o/r", "note")
        val cp = svc.snapshot()
        assertEquals("https://github.com/o/r", cp?.url)
        assertNull(cp?.researchJson)
        assertNull(cp?.analyzeJson)
    }

    @Test
    fun `service update is a no-op before beginRun`() {
        val svc = InMemoryAiCheckpointService()
        svc.update("research", researchJson)
        assertNull(svc.snapshot())
    }

    @Test
    fun `service reset clears the checkpoint`() {
        val svc = InMemoryAiCheckpointService()
        svc.beginRun("https://github.com/o/r", "note")
        svc.update("research", researchJson)
        svc.reset()
        assertNull(svc.snapshot())
    }
}

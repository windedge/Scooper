package scooper.service

import com.scoopai.core.ScoopAiGenerationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for [AiErrorTranslator], the pure function that maps a generation
 * failure [Throwable] to a user-facing [AiErrorPresentation] (title / detail /
 * suggested actions) and masks secrets.
 *
 * The translator must never import ai.koog types; the scoop-ai core exception
 * is exercised directly, while Koog-style failures are simulated with plain
 * [RuntimeException] wrappers carrying the message text the gateway would emit.
 */
class AiErrorTranslatorTest {

    // ---------------------------------------------------------------
    // ScoopAiGenerationException passthrough (CAPABILITY)
    // ---------------------------------------------------------------

    @Test
    fun `ScoopAiGenerationException maps to CAPABILITY with pass-through detail and three retry actions`() {
        val original = "Manifest generation failed: you must call tool_calls; the model never issued a tool call."
        val e = ScoopAiGenerationException(original)

        val p = AiErrorTranslator.translate(e)

        assertEquals("Model capability issue", p.title)
        assertEquals(original, p.detail)
        assertEquals(
            listOf(
                AiErrorAction.RETRY_FULL,
                AiErrorAction.RETRY_FROM_ANALYZE,
                AiErrorAction.RETRY_FROM_GENERATE,
            ),
            p.actions,
        )
    }

    @Test
    fun `ScoopAiGenerationException nested under a wrapper still maps to CAPABILITY`() {
        val inner = ScoopAiGenerationException("you must call tool_calls")
        val e = IllegalStateException("generation wrapped", inner)

        val p = AiErrorTranslator.translate(e)

        assertEquals(AiErrorAction.RETRY_FULL, p.actions.first())
        assertTrue(p.actions.containsAll(listOf(AiErrorAction.RETRY_FROM_ANALYZE, AiErrorAction.RETRY_FROM_GENERATE)))
    }

    // ---------------------------------------------------------------
    // HTTP status codes (nested cause chain)
    // ---------------------------------------------------------------

    @Test
    fun `nested 401 cause chain maps to AUTH with OPEN_SETTINGS`() {
        val koogLike = IllegalStateException("Status code: 401")
        val llmClient = IllegalStateException("LLMClientException", koogLike)
        val e = IllegalStateException(llmClient)

        val p = AiErrorTranslator.translate(e)

        assertEquals("Authentication failed", p.title)
        assertTrue(p.detail.contains("401"))
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    @Test
    fun `403 maps to AUTH with OPEN_SETTINGS`() {
        val p = AiErrorTranslator.translate(IllegalStateException("Status code: 403"))

        assertEquals("Authentication failed", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    @Test
    fun `404 containing a model name maps to MODEL_NOT_FOUND with OPEN_SETTINGS`() {
        val p = AiErrorTranslator.translate(IllegalStateException("404 model 'deepseek-coder' not found"))

        assertEquals("Model not found", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    @Test
    fun `400 with unknown provider for model maps to MODEL_NOT_FOUND with OPEN_SETTINGS`() {
        val message = "Error from client: OpenAICompatibleLLMClient: Error from client: OpenAILLMClient: " +
            "Status code: 400. Body: {\"error\":\"unknown provider for model gemini-3.5-flash-lite\"}"
        val p = AiErrorTranslator.translate(IllegalStateException(message))

        assertEquals("Model not found", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
        assertFalse(p.detail.contains("Error from client: OpenAICompatibleLLMClient: Error from client: OpenAILLMClient"))
    }

    @Test
    fun `400 with model_not_found in body maps to MODEL_NOT_FOUND with OPEN_SETTINGS`() {
        val message = "Status code: 400. Body: {\"error\":{\"code\":\"model_not_found\",\"message\":\"Model foo not found\"}}"
        val p = AiErrorTranslator.translate(IllegalStateException(message))

        assertEquals("Model not found", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    @Test
    fun `plain 404 without a model name maps to BAD_PATH with OPEN_SETTINGS`() {
        val p = AiErrorTranslator.translate(IllegalStateException("404 Not Found"))

        assertEquals("Resource not found (404)", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    @Test
    fun `429 maps to RATE_LIMIT with RETRY_FULL`() {
        val p = AiErrorTranslator.translate(IllegalStateException("429 Too Many Requests"))

        assertEquals("Rate limit exceeded", p.title)
        assertEquals(listOf(AiErrorAction.RETRY_FULL), p.actions)
    }

    @Test
    fun `502 maps to SERVER with RETRY_FULL`() {
        val p = AiErrorTranslator.translate(IllegalStateException("502 Bad Gateway"))

        assertEquals("Server error", p.title)
        assertEquals(listOf(AiErrorAction.RETRY_FULL), p.actions)
    }

    // ---------------------------------------------------------------
    // network errors
    // ---------------------------------------------------------------

    @Test
    fun `UnknownHost maps to NETWORK_DNS with OPEN_SETTINGS`() {
        val p = AiErrorTranslator.translate(IllegalStateException("java.net.UnknownHostException: api.example.com"))

        assertEquals("Cannot resolve host", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    @Test
    fun `timed out maps to TIMEOUT with RETRY_FULL`() {
        val p = AiErrorTranslator.translate(IllegalStateException("Connect timeout: 30s elapsed"))

        assertEquals("Request timed out", p.title)
        assertEquals(listOf(AiErrorAction.RETRY_FULL), p.actions)
    }

    @Test
    fun `Connection refused maps to REFUSED with OPEN_SETTINGS`() {
        val p = AiErrorTranslator.translate(IllegalStateException("Connection refused: localhost/127.0.0.1:8000"))

        assertEquals("Connection refused", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    // ---------------------------------------------------------------
    // config
    // ---------------------------------------------------------------

    @Test
    fun `missing base URL maps to CONFIG with OPEN_SETTINGS`() {
        val p = AiErrorTranslator.translate(
            IllegalStateException("Base URL is required for openai-compatible. Fill it in Settings.AI."),
        )

        assertEquals("Configuration error", p.title)
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }

    // ---------------------------------------------------------------
    // fallback + robustness
    // ---------------------------------------------------------------

    @Test
    fun `exception without a message maps to UNKNOWN without NPE`() {
        val p = AiErrorTranslator.translate(object : Exception() {})

        assertEquals("Generation failed", p.title)
        assertEquals(listOf(AiErrorAction.RETRY_FULL), p.actions)
    }

    @Test
    fun `unrecognized message maps to UNKNOWN with RETRY_FULL`() {
        val p = AiErrorTranslator.translate(IllegalStateException("some random failure"))

        assertEquals("Generation failed", p.title)
        assertEquals(listOf(AiErrorAction.RETRY_FULL), p.actions)
    }

    @Test
    fun `circular cause chain does not stack overflow`() {
        val a = IllegalStateException("Status code: 401")
        val b = IllegalStateException("wrapped", a)
        // a.cause = b creates a cycle a <-> b
        a.initCause(b)

        val p = AiErrorTranslator.translate(a)

        assertEquals("Authentication failed", p.title)
    }

    // ---------------------------------------------------------------
    // maskSecrets
    // ---------------------------------------------------------------

    @Test
    fun `maskSecrets masks a sk- token`() {
        assertEquals("sk-***", AiErrorTranslator.maskSecrets("sk-abcDEF123_-XYZ"))
    }

    @Test
    fun `maskSecrets masks a sk- token embedded in text`() {
        assertEquals(
            "Error with key sk-*** and more",
            AiErrorTranslator.maskSecrets("Error with key sk-abcDEF123XYZ and more"),
        )
    }

    @Test
    fun `maskSecrets leaves text without a secret unchanged`() {
        val text = "No secrets here, just the base URL https://example.com/v1"
        assertEquals(text, AiErrorTranslator.maskSecrets(text))
    }

    @Test
    fun `maskSecrets does not mask a short token below the threshold`() {
        // sk- followed by fewer than 8 characters is left alone.
        assertEquals("sk-ab", AiErrorTranslator.maskSecrets("sk-ab"))
    }
}

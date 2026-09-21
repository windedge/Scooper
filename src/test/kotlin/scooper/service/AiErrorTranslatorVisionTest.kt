package scooper.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD tests for the vision-related branches of [AiErrorTranslator]: models
 * that reject image input must surface a switch-model hint with only
 * OPEN_SETTINGS, and must never offer generate-resume actions.
 */
class AiErrorTranslatorVisionTest {

    // ---------------------------------------------------------------
    // vision capability errors
    // ---------------------------------------------------------------

    @Test
    fun `capability IAE mentioning image maps to vision hint with only OPEN_SETTINGS`() {
        val e = IllegalArgumentException(
            "Model gpt-4o-mini does not support image input. Please use a multimodal model.",
        )

        val p = AiErrorTranslator.translate(e)

        assertTrue(p.detail.contains("multimodal model") || p.detail.contains("multimodal"))
        assertTrue(p.actions.contains(AiErrorAction.OPEN_SETTINGS))
        assertFalse(p.actions.contains(AiErrorAction.RETRY_FULL))
        assertFalse(p.actions.contains(AiErrorAction.RETRY_FROM_ANALYZE))
        assertFalse(p.actions.contains(AiErrorAction.RETRY_FROM_GENERATE))
    }

    @Test
    fun `400 mentioning vision maps to vision hint with OPEN_SETTINGS`() {
        val message = "Status code: 400. Body: {\"error\":{\"message\":\"vision is not enabled for this model\"}}"
        val p = AiErrorTranslator.translate(IllegalStateException(message))

        assertTrue(p.detail.contains("multimodal"))
        assertEquals(listOf(AiErrorAction.OPEN_SETTINGS), p.actions)
    }
}

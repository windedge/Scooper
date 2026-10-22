package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// ---------------------------------------------------------------
// ProviderCatalog: host side metadata facade over the engine
// ProviderEndpoints table. The table itself lives in the engine, so
// these tests pin the host contract: display names, and the
// derived flags the settings page relies on.
// ---------------------------------------------------------------
class ProviderCatalogTest {

    @Test
    fun `providers list every engine provider in engine order`() {
        assertEquals(
            listOf("deepseek", "openai", "anthropic", "ollama", "openrouter", "openai-compatible"),
            ProviderCatalog.providers.map { it.id },
        )
    }

    @Test
    fun `display names are host defined`() {
        assertEquals(
            listOf("DeepSeek", "OpenAI", "Anthropic", "Ollama", "OpenRouter", "OpenAI Compatible"),
            ProviderCatalog.providers.map { it.displayName },
        )
    }

    @Test
    fun `exists is true for every supported provider`() {
        ProviderCatalog.providers.forEach { assertTrue(ProviderCatalog.exists(it.id), it.id) }
    }

    @Test
    fun `exists is false for an unknown provider`() {
        assertFalse(ProviderCatalog.exists("unknown"))
        assertFalse(ProviderCatalog.exists(""))
    }

    @Test
    fun `requiresBaseUrl is true only for the provider without a default endpoint`() {
        assertFalse(ProviderCatalog.requiresBaseUrl("deepseek"))
        assertFalse(ProviderCatalog.requiresBaseUrl("openai"))
        assertFalse(ProviderCatalog.requiresBaseUrl("anthropic"))
        assertFalse(ProviderCatalog.requiresBaseUrl("ollama"))
        assertFalse(ProviderCatalog.requiresBaseUrl("openrouter"))
        assertTrue(ProviderCatalog.requiresBaseUrl("openai-compatible"))
    }

    @Test
    fun `requiresApiKey is false only for ollama`() {
        assertTrue(ProviderCatalog.requiresApiKey("deepseek"))
        assertTrue(ProviderCatalog.requiresApiKey("openai"))
        assertTrue(ProviderCatalog.requiresApiKey("anthropic"))
        assertFalse(ProviderCatalog.requiresApiKey("ollama"))
        assertTrue(ProviderCatalog.requiresApiKey("openrouter"))
        assertTrue(ProviderCatalog.requiresApiKey("openai-compatible"))
    }

    @Test
    fun `apiKeyEnvHint exposes the provider environment variable`() {
        assertEquals("DEEPSEEK_API_KEY", ProviderCatalog.apiKeyEnvHint("deepseek"))
        assertEquals("OPENAI_API_KEY", ProviderCatalog.apiKeyEnvHint("openai"))
        assertEquals("ANTHROPIC_API_KEY", ProviderCatalog.apiKeyEnvHint("anthropic"))
        assertNull(ProviderCatalog.apiKeyEnvHint("ollama"))
        assertEquals("OPENROUTER_API_KEY", ProviderCatalog.apiKeyEnvHint("openrouter"))
        assertEquals("OPENAI_COMPATIBLE_API_KEY", ProviderCatalog.apiKeyEnvHint("openai-compatible"))
    }

    @Test
    fun `apiKeyEnvHint is null for an unknown provider`() {
        assertNull(ProviderCatalog.apiKeyEnvHint("unknown"))
    }

    @Test
    fun `defaultModelId returns the engine default model of each provider`() {
        assertEquals("deepseek-v4-flash", ProviderCatalog.defaultModelId("deepseek"))
        assertEquals("gpt-4o", ProviderCatalog.defaultModelId("openai"))
        assertEquals("claude-opus-4-1", ProviderCatalog.defaultModelId("anthropic"))
        assertEquals("llama3.2:latest", ProviderCatalog.defaultModelId("ollama"))
        assertEquals("gpt-4o", ProviderCatalog.defaultModelId("openrouter"))
        assertEquals("default", ProviderCatalog.defaultModelId("openai-compatible"))
    }

    @Test
    fun `defaultModelId is null for an unknown provider`() {
        assertNull(ProviderCatalog.defaultModelId("unknown"))
        assertNull(ProviderCatalog.defaultModelId(""))
    }

    @Test
    fun `defaultBaseUrl mirrors the engine endpoint table`() {
        assertEquals("https://api.deepseek.com", ProviderCatalog.defaultBaseUrl("deepseek"))
        assertEquals("https://api.openai.com", ProviderCatalog.defaultBaseUrl("openai"))
        assertEquals("https://api.anthropic.com", ProviderCatalog.defaultBaseUrl("anthropic"))
        assertEquals("http://localhost:11434", ProviderCatalog.defaultBaseUrl("ollama"))
        assertEquals("https://openrouter.ai", ProviderCatalog.defaultBaseUrl("openrouter"))
        assertNull(ProviderCatalog.defaultBaseUrl("openai-compatible"))
    }

    @Test
    fun `baseUrlPlaceholder shows an example for the provider that needs one`() {
        assertEquals(
            "https://your-gateway.example.com/v1",
            ProviderCatalog.baseUrlPlaceholder("openai-compatible"),
        )
        assertEquals("https://api.deepseek.com", ProviderCatalog.baseUrlPlaceholder("deepseek"))
        assertEquals("http://localhost:11434", ProviderCatalog.baseUrlPlaceholder("ollama"))
    }

    @Test
    fun `stripV1Suffix is false for openrouter and ollama`() {
        assertTrue(ProviderCatalog.stripV1Suffix("deepseek"))
        assertTrue(ProviderCatalog.stripV1Suffix("openai"))
        assertTrue(ProviderCatalog.stripV1Suffix("anthropic"))
        assertTrue(ProviderCatalog.stripV1Suffix("openai-compatible"))
        assertFalse(ProviderCatalog.stripV1Suffix("openrouter"))
        assertFalse(ProviderCatalog.stripV1Suffix("ollama"))
    }
}

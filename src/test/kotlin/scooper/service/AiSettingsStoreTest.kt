package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.ConfigRepository

// ---------------------------------------------------------------
// AiSettingsStore: AI settings reads (DB first, env fallback) and
// config persistence split out of ScoopAiBridge (S5). The fallback
// chain, persist write points and normalize semantics must stay
// identical to the pre-split bridge behavior.
// ---------------------------------------------------------------
class AiSettingsStoreTest {

    private class FakeConfigRepository(initial: UIConfig = UIConfig()) : ConfigRepository() {
        // Named to avoid the JVM signature clash with getConfig().
        var stored: UIConfig = initial
            private set
        override fun getConfig(): UIConfig = stored
        override fun setConfig(config: UIConfig) {
            this.stored = config
        }
    }

    private class ThrowingConfigRepository : ConfigRepository() {
        override fun getConfig(): UIConfig = throw IllegalStateException("db down")
        override fun setConfig(config: UIConfig) = throw IllegalStateException("db down")
    }

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? = { name ->
        pairs.firstOrNull { it.first == name }?.second
    }

    // -------------------------------------------------------------
    // Read fallback chain: DB first, then SCOOP_AI_* env, then the
    // provider specific key env, then defaults.
    // -------------------------------------------------------------

    @Test
    fun `readSettings prefers the DB over the environment variables`() {
        val store = AiSettingsStore(
            FakeConfigRepository(
                UIConfig(
                    aiProvider = "deepseek",
                    aiApiKey = "db-key",
                    aiBaseUrl = "http://db:8317",
                    aiModel = "db-model",
                    aiGithubToken = "db-token",
                ),
            ),
        )
        val settings = store.readSettings(
            envOf(
                "SCOOP_AI_PROVIDER" to "openai",
                "SCOOP_AI_API_KEY" to "env-key",
                "SCOOP_AI_BASE_URL" to "http://env:8317",
                "SCOOP_AI_MODEL" to "env-model",
                "SCOOP_AI_GITHUB_TOKEN" to "env-token",
            ),
        )
        assertEquals("deepseek", settings.provider)
        assertEquals("db-key", settings.apiKey)
        assertEquals("http://db:8317", settings.baseUrl)
        assertEquals("db-model", settings.model)
        assertEquals("db-token", settings.githubToken)
    }

    @Test
    fun `readSettings falls back to SCOOP_AI variables when the DB is blank`() {
        val store = AiSettingsStore(FakeConfigRepository())
        val settings = store.readSettings(
            envOf(
                "SCOOP_AI_PROVIDER" to "openai-compatible",
                "SCOOP_AI_API_KEY" to "env-key",
                "SCOOP_AI_BASE_URL" to "http://env:8317/v1/",
                "SCOOP_AI_MODEL" to "env-model",
                "SCOOP_AI_GITHUB_TOKEN" to "env-token",
            ),
        )
        assertEquals("openai-compatible", settings.provider)
        assertEquals("env-key", settings.apiKey)
        // openai-compatible strips the /v1 suffix (and trailing slash).
        assertEquals("http://env:8317", settings.baseUrl)
        assertEquals("env-model", settings.model)
        assertEquals("env-token", settings.githubToken)
    }

    @Test
    fun `readSettings falls back to the provider environment variable for the api key`() {
        val store = AiSettingsStore(FakeConfigRepository(UIConfig(aiProvider = "deepseek")))
        val settings = store.readSettings(envOf("DEEPSEEK_API_KEY" to "ds-key"))
        assertEquals("ds-key", settings.apiKey)
    }

    @Test
    fun `readSettings falls back to GITHUB_TOKEN when SCOOP_AI_GITHUB_TOKEN is unset`() {
        val store = AiSettingsStore(FakeConfigRepository())
        val settings = store.readSettings(envOf("GITHUB_TOKEN" to "plain-token"))
        assertEquals("plain-token", settings.githubToken)
    }

    @Test
    fun `readSettings keeps the raw base url for providers that keep their versioned path`() {
        val store = AiSettingsStore(FakeConfigRepository(UIConfig(aiProvider = "openrouter")))
        val settings = store.readSettings(envOf("SCOOP_AI_BASE_URL" to "https://openrouter.ai/api/v1"))
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    }

    // -------------------------------------------------------------
    // Persistence: persistModel writes through so a fresh read sees
    // the new value and nothing else changes; failures are swallowed
    // (best-effort, never breaks generation).
    // -------------------------------------------------------------

    @Test
    fun `persistModel writes through so readSettings sees the new model`() {
        val repo = FakeConfigRepository(UIConfig(aiProvider = "deepseek", aiModel = "old-model"))
        val store = AiSettingsStore(repo)

        store.persistModel("new-model")

        assertEquals("new-model", repo.stored.aiModel)
        assertEquals("new-model", store.readSettings { null }.model)
        // Only aiModel changes; every other field stays untouched.
        assertEquals("deepseek", repo.stored.aiProvider)
        assertTrue(repo.stored.aiApiKey.isEmpty())
    }

    @Test
    fun `persistModel swallows repository failures`() {
        val store = AiSettingsStore(ThrowingConfigRepository())
        store.persistModel("new-model")
    }

    @Test
    fun `persistLastBucket then readLastBucket round-trips`() {
        val repo = FakeConfigRepository()
        val store = AiSettingsStore(repo)

        store.persistLastBucket("games")

        assertEquals("games", store.readLastBucket())
        assertEquals("games", repo.stored.aiLastBucket)
    }

    @Test
    fun `readLastBucket returns empty and readCommitPreference returns true when the repository fails`() {
        val store = AiSettingsStore(ThrowingConfigRepository())
        assertEquals("", store.readLastBucket())
        assertTrue(store.readCommitPreference())
    }
}

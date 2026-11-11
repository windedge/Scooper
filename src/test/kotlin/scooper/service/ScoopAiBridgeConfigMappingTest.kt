package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.AiSettingsSnapshot

// ---------------------------------------------------------------
// ScoopAiBridge.buildScoopAiConfig: pure mapping from AiSettingsSnapshot
// to ScoopAiConfig. Focus here is the autoDownload flag flowing into
// skipDownloadConfirm, plus the guarantee that readiness checks
// (missingReason) stay independent of it. Tested via the companion
// pure function to avoid constructing a bridge (needs a DB repository).
// ---------------------------------------------------------------
class ScoopAiBridgeConfigMappingTest {

    private fun snapshot(
        autoDownload: Boolean,
        apiKey: String = "sk-test",
        baseUrl: String = "http://localhost:8317",
    ) = AiSettingsSnapshot(
        provider = "openai-compatible",
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = "test-model",
        githubToken = "",
        autoDownload = autoDownload,
    )

    @Test
    fun `autoDownload true maps to skipDownloadConfirm true`() {
        val config = ScoopAiBridge.buildScoopAiConfig(snapshot(autoDownload = true))
        assertTrue(config.skipDownloadConfirm)
    }

    @Test
    fun `autoDownload defaults to false and maps to skipDownloadConfirm false`() {
        val defaults = AiSettingsSnapshot(
            provider = "openai-compatible",
            apiKey = "sk-test",
            baseUrl = "http://localhost:8317",
            model = "test-model",
            githubToken = "",
        )
        assertFalse(defaults.autoDownload)
        val config = ScoopAiBridge.buildScoopAiConfig(defaults)
        assertFalse(config.skipDownloadConfirm)
    }

    @Test
    fun `missingReason is unaffected by autoDownload`() {
        // Ready settings: missingReason stays null whether autoDownload is set or not.
        assertNull(snapshot(autoDownload = false).missingReason())
        assertNull(snapshot(autoDownload = true).missingReason())

        // Incomplete settings: missing reason is reported identically either way.
        val without = snapshot(autoDownload = false, apiKey = "")
        val with = snapshot(autoDownload = true, apiKey = "")
        assertEquals(without.missingReason(), with.missingReason())
        assertNotNull(with.missingReason()!!.msgid)
    }

    // -------------------------------------------------------------
    // aiInstallVerify switch removal: install verification is always
    // on in the engine, so no settings field may carry the switch.
    // -------------------------------------------------------------

    @Test
    fun `settings expose no aiInstallVerify switch`() {
        val fields = scooper.data.UIConfig::class.java.declaredFields.map { it.name }
        assertFalse(
            fields.any { it.equals("aiInstallVerify", ignoreCase = true) },
            "UIConfig must not carry aiInstallVerify: verification is always on",
        )
    }

    // -------------------------------------------------------------
    // AiSettingsStore.withModel: pure config copy used by persistModel
    // (Create model selection). Only aiModel changes; every other
    // field must stay untouched.
    // -------------------------------------------------------------

    // -------------------------------------------------------------
    // Provider aware readiness checks and settings resolution.
    // The provider metadata comes from the engine endpoint table, so
    // the host never hardcodes a provider name.
    // -------------------------------------------------------------

    private fun providerSnapshot(
        provider: String,
        apiKey: String = "sk-test",
        baseUrl: String = "",
    ) = AiSettingsSnapshot(
        provider = provider,
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = "test-model",
        githubToken = "",
    )

    @Test
    fun `supported providers cover every engine provider`() {
        assertEquals(
            setOf("deepseek", "openai", "anthropic", "ollama", "openrouter", "openai-compatible"),
            ScoopAiBridge.SUPPORTED_PROVIDERS,
        )
    }

    @Test
    fun `missingReason reports an unsupported provider`() {
        val reason = providerSnapshot(provider = "unknown").missingReason()
        assertNotNull(reason)
        assertEquals("Unsupported provider: unknown. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible", reason!!.raw)
        assertNull(reason.msgid)
    }

    @Test
    fun `missingReason names the provider environment variable for the api key`() {
        val reason = providerSnapshot(provider = "deepseek", apiKey = "").missingReason()
        assertNotNull(reason)
        assertTrue(reason!!.msgid!!.contains("API key is missing"), reason.msgid)
        assertEquals("SCOOP_AI_API_KEY or DEEPSEEK_API_KEY", reason.args.single().second)
    }

    @Test
    fun `missingReason accepts ollama without an api key`() {
        assertNull(providerSnapshot(provider = "ollama", apiKey = "").missingReason())
    }

    @Test
    fun `missingReason accepts anthropic without a base url`() {
        assertNull(providerSnapshot(provider = "anthropic", baseUrl = "").missingReason())
    }

    @Test
    fun `missingReason still requires a base url for openai-compatible`() {
        val reason = providerSnapshot(provider = "openai-compatible", baseUrl = "").missingReason()
        assertNotNull(reason)
        assertTrue(reason!!.msgid!!.contains("Base URL is required"), reason.msgid)
        assertEquals("openai-compatible", reason.args.single().second)
    }

    @Test
    fun `missingReason accepts a base url for openai-compatible`() {
        assertNull(
            providerSnapshot(provider = "openai-compatible", baseUrl = "http://localhost:8317").missingReason(),
        )
    }

    @Test
    fun `readSettings falls back to the provider environment variable for the api key`() {
        val bridge = ScoopAiBridge(
            ConfigRepositoryStub(UIConfig(aiProvider = "deepseek")),
            InMemoryAiCheckpointService(),
        )
        val settings = bridge.readSettings { name -> if (name == "DEEPSEEK_API_KEY") "ds-key" else null }
        assertEquals("ds-key", settings.apiKey)
    }

    @Test
    fun `readSettings does not fall back to another provider environment variable`() {
        val bridge = ScoopAiBridge(
            ConfigRepositoryStub(UIConfig(aiProvider = "openai-compatible")),
            InMemoryAiCheckpointService(),
        )
        val settings = bridge.readSettings { name -> if (name == "DEEPSEEK_API_KEY") "ds-key" else null }
        assertEquals("", settings.apiKey)
    }

    @Test
    fun `readSettings falls back to the provider environment variable of each provider`() {
        val cases = mapOf(
            "deepseek" to "DEEPSEEK_API_KEY",
            "openai" to "OPENAI_API_KEY",
            "anthropic" to "ANTHROPIC_API_KEY",
            "openrouter" to "OPENROUTER_API_KEY",
            "openai-compatible" to "OPENAI_COMPATIBLE_API_KEY",
        )
        cases.forEach { (provider, envKey) ->
            val bridge = ScoopAiBridge(
                ConfigRepositoryStub(UIConfig(aiProvider = provider)),
                InMemoryAiCheckpointService(),
            )
            val settings = bridge.readSettings { name -> if (name == envKey) "key-$provider" else null }
            assertEquals("key-$provider", settings.apiKey, provider)
        }
    }

    @Test
    fun `buildScoopAiConfig drops a stale base url for providers with a default endpoint`() {
        val config = ScoopAiBridge.buildScoopAiConfig(
            providerSnapshot(provider = "deepseek", baseUrl = "http://localhost:8317"),
        )
        assertNull(config.baseUrl)
    }

    @Test
    fun `buildScoopAiConfig keeps the base url for openai-compatible`() {
        val config = ScoopAiBridge.buildScoopAiConfig(
            providerSnapshot(provider = "openai-compatible", baseUrl = "http://localhost:8317"),
        )
        assertEquals("http://localhost:8317", config.baseUrl)
    }

    @Test
    fun `normalizeAiBaseUrl keeps the v1 suffix for openrouter`() {
        assertEquals(
            "https://openrouter.ai/api/v1",
            ScoopAiBridge.normalizeAiBaseUrl("https://openrouter.ai/api/v1", "openrouter"),
        )
    }

    @Test
    fun `normalizeAiBaseUrl keeps the v1 suffix for ollama`() {
        assertEquals(
            "http://localhost:11434/v1",
            ScoopAiBridge.normalizeAiBaseUrl("http://localhost:11434/v1", "ollama"),
        )
    }

    @Test
    fun `normalizeAiBaseUrl strips the v1 suffix for versioned providers`() {
        assertEquals("http://localhost:8317", ScoopAiBridge.normalizeAiBaseUrl("http://localhost:8317/v1", "deepseek"))
        assertEquals("http://localhost:8317", ScoopAiBridge.normalizeAiBaseUrl("http://localhost:8317", "openai"))
        assertEquals(
            "http://localhost:8317",
            ScoopAiBridge.normalizeAiBaseUrl("http://localhost:8317/v1/chat/completions", "anthropic"),
        )
    }

    @Test
    fun `normalizeAiBaseUrl without a provider keeps the legacy behavior`() {
        assertEquals("http://localhost:8317", ScoopAiBridge.normalizeAiBaseUrl("http://localhost:8317/v1"))
        assertEquals("http://localhost:8317", ScoopAiBridge.normalizeAiBaseUrl("http://localhost:8317/"))
    }

    private fun fullConfig() = UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "sk-old",
        aiBaseUrl = "http://localhost:8317",
        aiModel = "old-model",
        aiGithubToken = "ghp_old",
        aiAutoDownload = true,
        showTrayIcon = true,
        theme = scooper.data.Theme.Dark,
    )

    @Test
    fun `withModel replaces only aiModel`() {
        val updated = AiSettingsStore.withModel(fullConfig(), "new-model")
        assertEquals("new-model", updated.aiModel)
    }

    @Test
    fun `withModel keeps every other field untouched`() {
        val base = fullConfig()
        val updated = AiSettingsStore.withModel(base, "new-model")
        assertEquals(base.copy(aiModel = "new-model"), updated)
        assertEquals(base.aiApiKey, updated.aiApiKey)
        assertEquals(base.aiBaseUrl, updated.aiBaseUrl)
        assertEquals(base.aiGithubToken, updated.aiGithubToken)
        assertEquals(base.aiAutoDownload, updated.aiAutoDownload)
        assertEquals(base.showTrayIcon, updated.showTrayIcon)
        assertEquals(base.theme, updated.theme)
    }

    @Test
    fun `withModel accepts blank model to clear the selection`() {
        val updated = AiSettingsStore.withModel(fullConfig(), "")
        assertEquals("", updated.aiModel)
    }

    // -------------------------------------------------------------
    // AiSettingsStore.withLastBucket: pure config copy used by
    // persistLastBucket (Create save dialog). Only aiLastBucket
    // changes; every other field must stay untouched.
    // -------------------------------------------------------------

    @Test
    fun `withLastBucket replaces only aiLastBucket`() {
        val updated = AiSettingsStore.withLastBucket(fullConfig(), "extras")
        assertEquals("extras", updated.aiLastBucket)
    }

    @Test
    fun `withLastBucket keeps every other field untouched`() {
        val base = fullConfig()
        val updated = AiSettingsStore.withLastBucket(base, "extras")
        assertEquals(base.copy(aiLastBucket = "extras"), updated)
        assertEquals(base.aiModel, updated.aiModel)
        assertEquals(base.aiApiKey, updated.aiApiKey)
        assertEquals(base.aiAutoDownload, updated.aiAutoDownload)
        assertEquals(base.showTrayIcon, updated.showTrayIcon)
        assertEquals(base.theme, updated.theme)
    }

    // -------------------------------------------------------------
    // readLastBucket / persistLastBucket round-trip through a fake
    // repository; persist failures are swallowed (best-effort).
    // -------------------------------------------------------------

    @Test
    fun `persistLastBucket then readLastBucket round-trips`() {
        val store = AiSettingsStore(FakeConfigRepository())
        store.persistLastBucket("games")
        assertEquals("games", store.readLastBucket())
    }

    @Test
    fun `persistLastBucket swallows repository failures`() {
        val store = AiSettingsStore(ThrowingConfigRepository())
        store.persistLastBucket("games")
    }

    // -------------------------------------------------------------
    // AiSettingsStore.withCommitPreference: pure config copy used by
    // persistCommitPreference (Create save dialog checkbox). Only
    // aiCommitToGit changes; every other field must stay untouched.
    // -------------------------------------------------------------

    @Test
    fun `withCommitPreference replaces only aiCommitToGit`() {
        val updated = AiSettingsStore.withCommitPreference(fullConfig(), false)
        assertFalse(updated.aiCommitToGit)
    }

    @Test
    fun `withCommitPreference keeps every other field untouched`() {
        val base = fullConfig()
        val updated = AiSettingsStore.withCommitPreference(base, false)
        assertEquals(base.copy(aiCommitToGit = false), updated)
        assertEquals(base.aiModel, updated.aiModel)
        assertEquals(base.aiApiKey, updated.aiApiKey)
        assertEquals(base.aiAutoDownload, updated.aiAutoDownload)
        assertEquals(base.aiLastBucket, updated.aiLastBucket)
        assertEquals(base.showTrayIcon, updated.showTrayIcon)
        assertEquals(base.theme, updated.theme)
    }

    @Test
    fun `persistCommitPreference then readCommitPreference round-trips`() {
        val store = AiSettingsStore(FakeConfigRepository())
        store.persistCommitPreference(false)
        assertFalse(store.readCommitPreference())
        store.persistCommitPreference(true)
        assertTrue(store.readCommitPreference())
    }

    @Test
    fun `readCommitPreference returns true when repository fails`() {
        val store = AiSettingsStore(ThrowingConfigRepository())
        assertTrue(store.readCommitPreference())
    }

    @Test
    fun `persistCommitPreference swallows repository failures`() {
        val store = AiSettingsStore(ThrowingConfigRepository())
        store.persistCommitPreference(false)
    }

    private class ConfigRepositoryStub(private val config: UIConfig) : ConfigRepository() {
        override fun getConfig(): UIConfig = config
        override fun setConfig(config: UIConfig) = Unit
    }

    private class FakeConfigRepository : ConfigRepository() {
        private var config = UIConfig()
        override fun getConfig(): UIConfig = config
        override fun setConfig(config: UIConfig) {
            this.config = config
        }
    }

    private class ThrowingConfigRepository : ConfigRepository() {
        override fun getConfig(): UIConfig = throw IllegalStateException("db down")
        override fun setConfig(config: UIConfig) = throw IllegalStateException("db down")
    }
}

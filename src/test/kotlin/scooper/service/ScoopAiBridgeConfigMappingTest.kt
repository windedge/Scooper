package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.service.ScoopAiBridge.AiSettingsSnapshot

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
        assertTrue(with.missingReason()!!.contains("API key"))
    }

    // -------------------------------------------------------------
    // ScoopAiBridge.withModel: pure config copy used by persistModel
    // (Create model selection). Only aiModel changes; every other
    // field must stay untouched.
    // -------------------------------------------------------------

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
        val updated = ScoopAiBridge.withModel(fullConfig(), "new-model")
        assertEquals("new-model", updated.aiModel)
    }

    @Test
    fun `withModel keeps every other field untouched`() {
        val base = fullConfig()
        val updated = ScoopAiBridge.withModel(base, "new-model")
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
        val updated = ScoopAiBridge.withModel(fullConfig(), "")
        assertEquals("", updated.aiModel)
    }
}

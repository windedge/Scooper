package scooper.ui

import scooper.service.ProviderCatalog
import scooper.util.tr

/**
 * Presentation hints of the AI settings form for one provider.
 *
 * @property showApiKey `false` when the provider needs no API key (Ollama).
 * @property showBaseUrl `true` only when the provider has no default endpoint.
 */
data class AiProviderFormHints(
    val showApiKey: Boolean,
    val showBaseUrl: Boolean,
    val providerSubtitle: String,
    val apiKeySubtitle: String,
    val baseUrlSubtitle: String,
    val baseUrlPlaceholder: String,
    val modelSubtitle: String,
    val modelPlaceholder: String,
)

/**
 * Pure derivation of the AI settings form from the engine provider table.
 *
 * The show/hide rules live here instead of inside the composable so they
 * can be tested without a Compose runtime. Subtitles are tr()-wrapped at source so
 * the gettext extractor picks up the msgids; with no .po entry tr() is an identity
 * passthrough, keeping raw-msgid assertions in tests valid.
 */
object AiProviderForm {

    const val DEFAULT_MODEL_PLACEHOLDER = "model-id"

    /** Legacy placeholder of the base URL field, kept for openai-compatible. */
    private const val LEGACY_BASE_URL_PLACEHOLDER = "https://api.openai.com"

    fun hints(provider: String): AiProviderFormHints {
        val envKey = ProviderCatalog.apiKeyEnvHint(provider)
        val defaultModel = ProviderCatalog.defaultModelId(provider)
        return AiProviderFormHints(
            showApiKey = ProviderCatalog.requiresApiKey(provider),
            showBaseUrl = ProviderCatalog.requiresBaseUrl(provider),
            providerSubtitle = tr("LLM provider used to generate Scoop manifests."),
            apiKeySubtitle = if (envKey == null) {
                tr("Stored locally. Never written to logs or toast messages.")
            } else {
                tr("Stored locally. Leave empty to fall back to the {{env}} environment variable.", "env" to envKey)
            },
            baseUrlSubtitle = tr("Required for openai-compatible. The client appends /v1/chat/completions."),
            baseUrlPlaceholder = if (ProviderCatalog.requiresBaseUrl(provider)) {
                LEGACY_BASE_URL_PLACEHOLDER
            } else {
                ProviderCatalog.baseUrlPlaceholder(provider)
            },
            modelSubtitle = if (defaultModel == null) {
                tr("Search fetched models or type an id manually.")
            } else {
                // Two independent msgids joined at the render site so each
                // sentence translates with its own word order.
                listOf(
                    tr("Defaults to {{model}}.", "model" to defaultModel),
                    tr("Search fetched models or type an id manually."),
                ).joinToString(" ")
            },
            modelPlaceholder = defaultModel ?: DEFAULT_MODEL_PLACEHOLDER,
        )
    }
}

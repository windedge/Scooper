package scooper.ui

import scooper.service.ProviderCatalog

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
 * can be tested without a Compose runtime.
 */
object AiProviderForm {

    const val DEFAULT_MODEL_PLACEHOLDER = "model-id"

    private const val PROVIDER_SUBTITLE = "LLM provider used to generate Scoop manifests."

    private const val API_KEY_SUBTITLE = "Stored locally. Never written to logs or toast messages."

    private const val BASE_URL_SUBTITLE = "Required for openai-compatible. The client appends /v1/chat/completions."

    /** Legacy placeholder of the base URL field, kept for openai-compatible. */
    private const val LEGACY_BASE_URL_PLACEHOLDER = "https://api.openai.com"

    private const val MODEL_HINT = "Search fetched models or type an id manually."

    fun hints(provider: String): AiProviderFormHints {
        val envKey = ProviderCatalog.apiKeyEnvHint(provider)
        val defaultModel = ProviderCatalog.defaultModelId(provider)
        return AiProviderFormHints(
            showApiKey = ProviderCatalog.requiresApiKey(provider),
            showBaseUrl = ProviderCatalog.requiresBaseUrl(provider),
            providerSubtitle = PROVIDER_SUBTITLE,
            apiKeySubtitle = if (envKey == null) {
                API_KEY_SUBTITLE
            } else {
                "Stored locally. Leave empty to fall back to the $envKey environment variable."
            },
            baseUrlSubtitle = BASE_URL_SUBTITLE,
            baseUrlPlaceholder = if (ProviderCatalog.requiresBaseUrl(provider)) {
                LEGACY_BASE_URL_PLACEHOLDER
            } else {
                ProviderCatalog.baseUrlPlaceholder(provider)
            },
            modelSubtitle = if (defaultModel == null) {
                MODEL_HINT
            } else {
                "Defaults to $defaultModel. $MODEL_HINT"
            },
            modelPlaceholder = defaultModel ?: DEFAULT_MODEL_PLACEHOLDER,
        )
    }
}

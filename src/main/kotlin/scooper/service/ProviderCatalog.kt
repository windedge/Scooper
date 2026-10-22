package scooper.service

import com.scoopai.tools.llm.ProviderEndpoints

/**
 * Host side metadata for every LLM provider the engine supports.
 *
 * The protocol facts (default endpoints, required fields, environment
 * variable names, default model) come from the engine table
 * [ProviderEndpoints], so the host never keeps a second copy of the
 * protocol. Only the display names live here, because they are a UI
 * concern the engine does not model.
 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
)

object ProviderCatalog {

    private val displayNames = mapOf(
        "deepseek" to "DeepSeek",
        "openai" to "OpenAI",
        "anthropic" to "Anthropic",
        "ollama" to "Ollama",
        "openrouter" to "OpenRouter",
        "openai-compatible" to "OpenAI Compatible",
    )

    /** All supported providers, in the order the UI shows them. */
    val providers: List<ProviderInfo> = ProviderEndpoints.supportedProviders().map { id ->
        ProviderInfo(id = id, displayName = displayNames[id] ?: id)
    }

    /** Supported provider ids, in UI order. */
    val supportedProviderIds: List<String> = providers.map { it.id }

    fun exists(provider: String): Boolean = ProviderEndpoints.forProvider(provider) != null

    fun displayName(provider: String): String = displayNames[provider] ?: provider

    /**
     * `true` when the provider has no default endpoint, so the user must
     * supply a base URL. Derived from the engine table, never from a
     * provider name.
     */
    fun requiresBaseUrl(provider: String): Boolean =
        ProviderEndpoints.forProvider(provider)?.defaultBaseUrl == null

    fun requiresApiKey(provider: String): Boolean =
        ProviderEndpoints.forProvider(provider)?.requiresApiKey ?: true

    /** Environment variable holding the API key, `null` when there is none. */
    fun apiKeyEnvHint(provider: String): String? =
        ProviderEndpoints.forProvider(provider)?.envKey

    /** Default model of the provider, `null` for an unknown provider. */
    fun defaultModelId(provider: String): String? =
        ProviderEndpoints.defaultModelId(provider)

    fun defaultBaseUrl(provider: String): String? =
        ProviderEndpoints.forProvider(provider)?.defaultBaseUrl

    /** Placeholder shown in the base URL input field. */
    fun baseUrlPlaceholder(provider: String): String =
        if (requiresBaseUrl(provider)) {
            "https://your-gateway.example.com/v1"
        } else {
            defaultBaseUrl(provider).orEmpty()
        }

    /**
     * `true` when a trailing `/v1` must be removed from a user supplied
     * base URL. Providers keeping their own versioned path (OpenRouter,
     * Ollama) keep the suffix.
     */
    fun stripV1Suffix(provider: String): Boolean =
        ProviderEndpoints.forProvider(provider)?.stripV1Suffix ?: true
}

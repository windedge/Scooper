package scooper.viewmodels

import kotlinx.coroutines.suspendCancellableCoroutine
import scooper.service.AiSettingsSnapshot
import scooper.service.AiSettingsStore
import scooper.service.ScoopAiBridge
import scooper.util.LocalizedText
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Failure of [AiGate.fetchModels]. Carries the provider message so the view
 * model can toast it; never swallowed inside the gate, because only the view
 * model owns toasts.
 */
internal class AiModelsFetchException(message: String) : Exception(message)

/**
 * Single readiness/model/fetch seam shared by the Create generate and assist
 * entries (S2 step 4). It answers the three questions the view model used to
 * duplicate per entry point: is the AI configured, which model is selected,
 * and which models the gateway offers.
 *
 * The gate is deliberately side-effect free: it returns results and the view
 * model decides whether they become state, a toast or a log line.
 */
internal class AiGate(
    private val settings: AiSettingsStore,
    private val bridge: ScoopAiBridge,
    /**
     * Settings read seam. Defaults to the real environment-backed read; tests
     * pass an env-free lookup so readiness assertions stay machine independent.
     */
    private val snapshot: () -> AiSettingsSnapshot = settings::readSettings,
) {
    /** Outcome of the readiness check; [reason] is structured copy that banners render
     *  at composition time so a language switch re-translates it. */
    data class Readiness(val configured: Boolean, val reason: LocalizedText?)

    /**
     * Configured check shared by both start entries. A missing reason means the
     * engine can run; otherwise [Readiness.reason] explains what to fix.
     */
    fun readiness(): Readiness {
        val reason = snapshot().missingReason()
        return Readiness(configured = reason == null, reason = reason)
    }

    /** Model persisted in Settings.AI (env fallback included); blank when unset. */
    fun currentModel(): String = snapshot().model

    /** Persist the model chosen in Create; best effort, failures are logged by the store. */
    fun persistModel(model: String) {
        settings.persistModel(model)
    }

    /**
     * Query the provider's /models endpoint with the stored settings. Throws
     * [AiModelsFetchException] when the probe fails so the caller can keep its
     * previous list and toast the reason.
     */
    suspend fun fetchModels(): List<String> {
        val current = snapshot()
        return suspendCancellableCoroutine { continuation ->
            bridge.fetchModels(current.provider, current.apiKey, current.baseUrl) { result ->
                // The bridge callback may arrive after the intent was cancelled.
                if (!continuation.isActive) return@fetchModels
                if (result.ok) {
                    continuation.resume(result.models)
                } else {
                    continuation.resumeWithException(
                        AiModelsFetchException(
                            result.errorMessage?.takeIf { it.isNotBlank() } ?: DEFAULT_FETCH_FAILURE,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_FETCH_FAILURE = "Could not fetch the model list from the provider."
    }
}

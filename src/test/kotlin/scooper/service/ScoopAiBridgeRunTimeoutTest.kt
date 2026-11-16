package scooper.service

import com.scoopai.core.ManifestResult
import com.scoopai.core.artifacts.ResumeData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.repository.ConfigRepository
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * TDD for the host-side whole-run timeout in [ScoopAiBridge]: every engine run
 * (generate and assist) must be bounded by a withTimeout guard around the
 * generator call. Without it an engine HTTP black hole leaves the host waiting
 * forever (a RESEARCH phase once hung for 57 minutes).
 *
 * Cancellation vs timeout must stay distinguishable: a user cancel propagates a
 * plain CancellationException and the failure must NOT say "timed out", while a
 * withTimeout expiry surfaces as a failure whose message contains "timed out".
 */
class ScoopAiBridgeRunTimeoutTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /**
     * Test double suspending inside the [ScoopAiBridge.runGenerate] /
     * [ScoopAiBridge.runAssist] network boundary until released or cancelled,
     * so the real base-class launch/onFinished/error path runs while only the
     * engine call is replaced.
     */
    private class HangingBridge(
        configRepository: ConfigRepository,
        checkpointService: InMemoryAiCheckpointService,
        aiRunTimeoutMs: Long = ScoopAiBridge.AI_RUN_TIMEOUT_MS,
    ) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = checkpointService,
        aiRunTimeoutMs = aiRunTimeoutMs,
    ) {
        var enteredRun = false
        var cancelled = false
        var gate = CompletableDeferred<ManifestResult>()

        fun resetGate() {
            gate = CompletableDeferred()
        }

        override suspend fun runGenerate(
            settings: AiSettingsSnapshot,
            url: String,
            userPrompt: String?,
            resumeData: ResumeData?,
            callbacks: ScoopAiBridge.GeneratorCallbacks,
            ignoreAppNames: Set<String>,
        ): ManifestResult {
            enteredRun = true
            try {
                return gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        override suspend fun runAssist(
            settings: AiSettingsSnapshot,
            currentManifest: String,
            instruction: String,
            history: List<String>,
            suggestedAppName: String?,
            images: List<AiImage>,
            evidence: String?,
            callbacks: ScoopAiBridge.GeneratorCallbacks,
            onSaveToBucket: (suspend (String?, String?, Boolean) -> String)?,
            onInstallApp: (suspend (String?, String) -> String)?,
            onReadManifestRef: (suspend (String, String) -> String)?,
            ignoreAppNames: Set<String>,
        ): ManifestResult {
            enteredRun = true
            try {
                return gate.await()
            } catch (e: CancellationException) {
                cancelled = true
            }
            throw CancellationException("assist gate released by timeout")
        }

        fun finish(appName: String?) {
            gate.complete(
                ManifestResult(
                    manifest = """{"version":"1.0.0"}""",
                    suggestedAppName = appName,
                    answered = false,
                )
            )
        }
    }

    private class FakeRepo : ConfigRepository() {
        override fun getConfig() = scooper.data.UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "k",
            aiBaseUrl = "http://127.0.0.1:9",
        )
        override fun setConfig(config: scooper.data.UIConfig) = Unit
    }

    private fun newBridge(timeoutMs: Long): HangingBridge =
        HangingBridge(FakeRepo(), InMemoryAiCheckpointService(), aiRunTimeoutMs = timeoutMs)

    /** Poll [predicate] on a worker thread until it holds or the 5s test budget expires. */
    private suspend fun awaitPredicate(predicate: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate()) kotlinx.coroutines.delay(10) }
        }
    }

    // ---------------------------------------------------------------
    // generate path
    // ---------------------------------------------------------------

    @Test
    fun `generate run that hangs past the injected timeout fails with a timed out message`() = runBlocking {
        val bridge = newBridge(timeoutMs = 100)
        try {
            val finished = AtomicReference<Result<ManifestResult>?>(null)
            bridge.startGenerate(
                url = "https://github.com/owner/repo",
                userPrompt = null,
                onPhase = {},
                onFinished = { finished.set(it) },
            )

            awaitPredicate { finished.get() != null }
            val failure = finished.get()
            assertTrue(failure!!.isFailure, "a hung engine run must end in failure")
            val message = failure.exceptionOrNull()!!.message.orEmpty()
            assertTrue(message.contains("timed out"), "message must mention the timeout, got: $message")
            assertTrue(bridge.cancelled, "the hung runGenerate coroutine must have been cancelled")
            awaitPredicate { !bridge.isRunning }
            assertFalse(bridge.isRunning, "the bridge job must finish after the timeout")
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `generate run finishing before the timeout succeeds normally`() = runBlocking {
        val bridge = newBridge(timeoutMs = 10_000)
        try {
            val finished = AtomicReference<Result<ManifestResult>?>(null)
            bridge.startGenerate(
                url = "https://github.com/owner/repo",
                userPrompt = null,
                onPhase = {},
                onFinished = { finished.set(it) },
            )
            bridge.finish("fd")
            awaitPredicate { finished.get() != null }
            assertTrue(finished.get()!!.isSuccess, "a run inside the budget must succeed")
            assertEquals("fd", finished.get()!!.getOrNull()!!.suggestedAppName)
        } finally {
            bridge.close()
        }
    }

    // ---------------------------------------------------------------
    // assist path
    // ---------------------------------------------------------------

    @Test
    fun `assist run that hangs past the injected timeout fails with a timed out message`() = runBlocking {
        val bridge = newBridge(timeoutMs = 100)
        try {
            val finished = AtomicReference<Result<ManifestResult>?>(null)
            bridge.startAssist(
                currentManifest = """{"version":"1.0.0"}""",
                instruction = "remove notes",
                onFinished = { finished.set(it) },
            )

            awaitPredicate { finished.get() != null }
            val failure = finished.get()
            assertTrue(failure!!.isFailure, "a hung assist run must end in failure too")
            val message = failure.exceptionOrNull()!!.message.orEmpty()
            assertTrue(message.contains("timed out"), "message must mention the timeout, got: $message")
            assertTrue(bridge.cancelled)
        } finally {
            bridge.close()
        }
    }

    // ---------------------------------------------------------------
    // cancel vs timeout
    // ---------------------------------------------------------------

    @Test
    fun `external cancel is not reported as a timeout`() = runBlocking {
        val bridge = newBridge(timeoutMs = 60_000)
        try {
            val finished = AtomicReference<Result<ManifestResult>?>(null)
            bridge.startGenerate(
                url = "https://github.com/owner/repo",
                userPrompt = null,
                onPhase = {},
                onFinished = { finished.set(it) },
            )
            awaitPredicate { bridge.enteredRun }
            bridge.cancel()

            awaitPredicate { finished.get() != null }
            val failure = finished.get()
            assertTrue(failure!!.isFailure, "a cancelled run must surface as failure through onFinished")
            val message = failure.exceptionOrNull()!!.message.orEmpty()
            assertFalse(
                message.contains("timed out"),
                "a user cancel must not be misreported as a timeout, got: $message",
            )
        } finally {
            bridge.close()
        }
    }
}
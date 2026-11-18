package scooper.service

import com.scoopai.core.ManifestResult
import com.scoopai.core.artifacts.ResumeData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.repository.ConfigRepository
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * TDD for the host-side whole-run timeout in [ScoopAiBridge]: an engine run
 * must be bounded by a withTimeout guard around the generator call. Without it
 * an HTTP black hole leaves the host waiting forever (a RESEARCH phase once
 * hung for 57 minutes).
 *
 * Cancellation vs timeout must stay distinguishable: a user cancel propagates
 * a plain CancellationException and the failure must NOT say "timed out", while
 * a withTimeout expiry surfaces as a failure whose message contains "timed out".
 */
class ScoopAiBridgeRunTimeoutTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /**
     * Test double suspending inside the [ScoopAiBridge.runGenerate] network
     * boundary until cancelled, so the real base-class launch/onFinished/error
     * path runs while only the engine call is replaced.
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

    /** Start one run, wait for onFinished, verify, then close the bridge. */
    private suspend fun runAndAwait(
        timeoutMs: Long,
        start: suspend HangingBridge.(AtomicReference<Result<ManifestResult>?>) -> Unit,
        verify: suspend HangingBridge.(Result<ManifestResult>) -> Unit,
    ) {
        val bridge = newBridge(timeoutMs)
        val finished = AtomicReference<Result<ManifestResult>?>(null)
        try {
            bridge.start(finished)
            awaitPredicate { finished.get() != null }
            bridge.verify(finished.get()!!)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `generate run that hangs past the injected timeout fails with a timed out message`() = runBlocking {
        runAndAwait(
            timeoutMs = 100,
            start = { finished ->
                startGenerate(
                    url = "https://github.com/owner/repo",
                    userPrompt = null,
                    onPhase = {},
                    onFinished = { finished.set(it) },
                )
            },
            verify = { result ->
                assertTrue(result.isFailure, "a hung engine run must end in failure")
                val message = result.exceptionOrNull()!!.message.orEmpty()
                assertTrue(message.contains("timed out"), "message must mention the timeout, got: $message")
                assertTrue(cancelled, "the hung runGenerate coroutine must have been cancelled")
                awaitPredicate { !isRunning }
                assertFalse(isRunning, "the bridge job must finish after the timeout")
            },
        )
    }

    @Test
    fun `external cancel is not reported as a timeout`() = runBlocking {
        runAndAwait(
            timeoutMs = 60_000,
            start = { finished ->
                startGenerate(
                    url = "https://github.com/owner/repo",
                    userPrompt = null,
                    onPhase = {},
                    onFinished = { finished.set(it) },
                )
                awaitPredicate { enteredRun }
                cancel()
            },
            verify = { result ->
                assertTrue(result.isFailure, "a cancelled run must surface as failure through onFinished")
                val message = result.exceptionOrNull()!!.message.orEmpty()
                assertFalse(
                    message.contains("timed out"),
                    "a user cancel must not be misreported as a timeout, got: $message",
                )
            },
        )
    }
}

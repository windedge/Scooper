@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import scooper.data.UIConfig
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for reference-aware save defaults (batch 4): a produced app name
 * matching a referenced @bucket/app locks that bucket/name as the save dialog
 * default (still editable), overriding the lastBucket memory. A name mismatch
 * keeps the existing resolveDefaultSaveBucket behavior.
 */
class CreateViewModelRefSaveDefaultTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // referenceSaveDefault: pure lock rule
    // ---------------------------------------------------------------

    private val buckets = listOf("extras", "games", "main")

    @Test
    fun `referenceSaveDefault locks the referenced bucket when the produced name matches`() {
        assertEquals(
            "main" to "fd",
            referenceSaveDefault(
                appName = "fd",
                refs = listOf("main" to "fd"),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault matches case insensitively and keeps the reference casing`() {
        assertEquals(
            "main" to "Fd",
            referenceSaveDefault(
                appName = "FD",
                refs = listOf("main" to "Fd"),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault picks the last matching reference among several`() {
        assertEquals(
            "games" to "fd",
            referenceSaveDefault(
                appName = "fd",
                refs = listOf("main" to "golang", "extras" to "fd", "games" to "fd"),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault keeps scanning past a match whose bucket is missing`() {
        assertEquals(
            "extras" to "fd",
            referenceSaveDefault(
                appName = "fd",
                refs = listOf("deleted-bucket" to "fd", "extras" to "fd"),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault skips a match whose bucket is not available locally`() {
        assertNull(
            referenceSaveDefault(
                appName = "fd",
                refs = listOf("deleted-bucket" to "fd"),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault returns null when no reference name matches`() {
        assertNull(
            referenceSaveDefault(
                appName = "fd",
                refs = listOf("main" to "golang", "extras" to "ripgrep"),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault returns null without references`() {
        assertNull(
            referenceSaveDefault(
                appName = "fd",
                refs = emptyList(),
                availableBuckets = buckets,
            ),
        )
    }

    @Test
    fun `referenceSaveDefault returns null for a blank app name`() {
        assertNull(
            referenceSaveDefault(
                appName = "  ",
                refs = listOf("main" to "fd"),
                availableBuckets = buckets,
            ),
        )
    }

    // ---------------------------------------------------------------
    // refLockHintText: save dialog hint line
    // ---------------------------------------------------------------

    @Test
    fun `refLockHintText describes the locked reference`() {
        assertEquals(
            "Pre-filled from @main/fd reference",
            refLockHintText("main" to "fd"),
        )
    }

    @Test
    fun `refLockHintText returns null without a lock`() {
        assertNull(refLockHintText(null))
    }

    // ---------------------------------------------------------------
    // Real VM: onSave dialog defaults (ScoopAiBridge base instance, the
    // save path never dials out through the bridge)
    // ---------------------------------------------------------------

    private class PrefConfigRepository(private val config: UIConfig) : ConfigRepository() {
        override fun getConfig(): UIConfig = config
        override fun setConfig(config: UIConfig) = Unit
    }

    private fun configWithLastBucket(lastBucket: String) = UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
        aiLastBucket = lastBucket,
    )

    private fun makeBucket(name: String) {
        tempDir.resolve("buckets").resolve(name).resolve("bucket").apply { mkdirs() }
    }

    private suspend fun preset(vm: CreateViewModel, manifestJson: String, messages: List<ChatMessage>) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce {
                        state.copy(
                            manifestJson = manifestJson,
                            suggestedAppName = "fd",
                            messages = messages,
                        )
                    }
                }
                while (vm.container.stateFlow.value.manifestJson != manifestJson) delay(10)
            }
        }
    }

    private fun createViewModel(config: UIConfig): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = PrefConfigRepository(config),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = AppsRepository(
                scoopClient = scoopClient,
                gitHistoryService = GitHistoryService(),
            ),
            gitHistoryService = GitHistoryService(),
        )
    }

    @Test
    fun `reference lock wins over lastBucket memory when the produced name matches`() = runBlocking {
        makeBucket("main")
        makeBucket("extras")
        val vm = createViewModel(configWithLastBucket("extras"))
        try {
            preset(
                vm,
                manifestJson = "{\"version\":\"1.2.3\"}",
                messages = listOf(ChatMessage(role = ChatRole.User, text = "参考 @main/fd 的写法")),
            )

            withContext(Dispatchers.Default) { vm.onSave().join() }

            val s = vm.container.stateFlow.value
            assertEquals("main", s.saveBucket, "the referenced bucket must be locked as the default")
            assertEquals("fd", s.saveAppName, "the referenced app name must prefill the dialog")
            assertEquals(
                "Pre-filled from @main/fd reference",
                s.saveRefHint,
                "the dialog must explain where the locked default came from",
            )
            assertEquals(listOf("extras", "main"), s.bucketOptions)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `name mismatch falls back to lastBucket memory`() = runBlocking {
        makeBucket("main")
        makeBucket("extras")
        val vm = createViewModel(configWithLastBucket("extras"))
        try {
            preset(
                vm,
                manifestJson = "{\"version\":\"1.2.3\"}",
                messages = listOf(ChatMessage(role = ChatRole.User, text = "参考 @main/golang 的写法")),
            )

            withContext(Dispatchers.Default) { vm.onSave().join() }

            val s = vm.container.stateFlow.value
            assertEquals("extras", s.saveBucket, "lastBucket memory must apply when no reference matches")
            assertEquals("fd", s.saveAppName, "the produced app name must prefill the dialog")
            assertNull(s.saveRefHint, "no hint without a reference lock")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `no references keeps the existing save default behavior`() = runBlocking {
        makeBucket("main")
        makeBucket("extras")
        val vm = createViewModel(configWithLastBucket("extras"))
        try {
            preset(
                vm,
                manifestJson = "{\"version\":\"1.2.3\"}",
                messages = listOf(ChatMessage(role = ChatRole.User, text = "做个便携版")),
            )

            withContext(Dispatchers.Default) { vm.onSave().join() }

            val s = vm.container.stateFlow.value
            assertEquals("extras", s.saveBucket, "lastBucket memory must survive without references")
            assertEquals("fd", s.saveAppName)
            assertNull(s.saveRefHint)
        } finally {
            vm.close()
        }
    }
}

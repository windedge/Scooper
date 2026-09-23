@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.UIConfig
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.AiImage
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import com.scoopai.core.ManifestResult
import java.io.File

/**
 * TDD tests for @bucket/app reference scanning (batch 2 of the manifest
 * reference design) and the relaxed send routing: any input containing a
 * GitHub URL generates, everything else assists (including a first message
 * with no manifest, a non-GitHub URL, and a pure-image send).
 */
class CreateViewModelManifestRefTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // extractManifestRefs: pure scanning rule
    // ---------------------------------------------------------------

    @Test
    fun `extractManifestRefs finds a reference at start of text`() {
        assertEquals(
            listOf("main" to "golang"),
            extractManifestRefs("@main/golang 加上 autoupdate"),
        )
    }

    @Test
    fun `extractManifestRefs finds references after whitespace in order`() {
        assertEquals(
            listOf("main" to "golang", "extras" to "vscode"),
            extractManifestRefs("对比 @main/golang 和 @extras/vscode"),
        )
    }

    @Test
    fun `extractManifestRefs ignores email addresses`() {
        assertTrue(extractManifestRefs("contact a@b.com about this").isEmpty())
    }

    @Test
    fun `extractManifestRefs ignores references inside URLs`() {
        assertTrue(extractManifestRefs("see https://example.com/@main/golang for details").isEmpty())
    }

    @Test
    fun `extractManifestRefs keeps original casing`() {
        assertEquals(listOf("Main" to "Golang"), extractManifestRefs("@Main/Golang"))
    }

    @Test
    fun `extractManifestRefs dedups keeping first occurrence order`() {
        assertEquals(
            listOf("main" to "golang", "extras" to "vscode"),
            extractManifestRefs("@main/golang then @extras/vscode and again @main/golang"),
        )
    }

    @Test
    fun `extractManifestRefs returns empty for text without references`() {
        assertTrue(extractManifestRefs("remove the notes field please").isEmpty())
    }

    // ---------------------------------------------------------------
    // splitManifestRefSegments: bubble text segmentation for chip rendering
    // ---------------------------------------------------------------

    @Test
    fun `splitManifestRefSegments splits text into text and reference segments`() {
        val segments = splitManifestRefSegments("compare @main/golang with @extras/vscode now")
        assertEquals(
            listOf(
                ManifestRefSegment.Text("compare "),
                ManifestRefSegment.Ref("main", "golang"),
                ManifestRefSegment.Text(" with "),
                ManifestRefSegment.Ref("extras", "vscode"),
                ManifestRefSegment.Text(" now"),
            ),
            segments,
        )
    }

    @Test
    fun `splitManifestRefSegments returns single text segment without references`() {
        assertEquals(
            listOf(ManifestRefSegment.Text("plain instructions only")),
            splitManifestRefSegments("plain instructions only"),
        )
    }

    @Test
    fun `splitManifestRefSegments keeps original reference casing`() {
        val segments = splitManifestRefSegments("see @Main/Golang please")
        assertEquals(
            listOf(
                ManifestRefSegment.Text("see "),
                ManifestRefSegment.Ref("Main", "Golang"),
                ManifestRefSegment.Text(" please"),
            ),
            segments,
        )
    }

    @Test
    fun `splitManifestRefSegments treats emails as plain text`() {
        assertEquals(
            listOf(ManifestRefSegment.Text("contact a@b.com about this")),
            splitManifestRefSegments("contact a@b.com about this"),
        )
    }

    @Test
    fun `splitManifestRefSegments treats url fragments as plain text`() {
        assertEquals(
            listOf(ManifestRefSegment.Text("see https://example.com/@main/golang for details")),
            splitManifestRefSegments("see https://example.com/@main/golang for details"),
        )
    }

    @Test
    fun `splitManifestRefSegments skips invalid partial tokens`() {
        // "@main" without a "/name" part is not a reference token.
        assertEquals(
            listOf(ManifestRefSegment.Text("use @main as prefix")),
            splitManifestRefSegments("use @main as prefix"),
        )
    }

    @Test
    fun `splitManifestRefSegments matches extractManifestRefs across mixed cases`() {
        val samples = listOf(
            "@main/golang 加上 autoupdate",
            "对比 @main/golang 和 @extras/vscode",
            "contact a@b.com about this",
            "see https://example.com/@main/golang for details",
            "@main/golang then @extras/vscode and again @main/golang",
            "no refs here",
            "@-invalid/@name",
        )
        for (text in samples) {
            val refs = splitManifestRefSegments(text)
                .filterIsInstance<ManifestRefSegment.Ref>()
                .map { it.bucket to it.name }
                .distinct()
            assertEquals(extractManifestRefs(text), refs, "segmentation must agree with extractManifestRefs for: $text")
        }
    }

    // ---------------------------------------------------------------
    // filterRefSuggestions: completion menu filtering rule
    // ---------------------------------------------------------------

    private val refSuggestions = listOf(
        ManifestRefSuggestion("main", "golang"),
        ManifestRefSuggestion("main", "gcc"),
        ManifestRefSuggestion("extras", "vscode"),
        ManifestRefSuggestion("extras", "vscodium"),
        ManifestRefSuggestion("games", "0ad"),
    )

    @Test
    fun `filterRefSuggestions with empty prefix returns all sorted`() {
        val filtered = filterRefSuggestions(refSuggestions, "")
        assertEquals(refSuggestions.size, filtered.size)
        assertEquals(listOf("0ad", "gcc", "golang", "vscode", "vscodium"), filtered.map { it.name })
    }

    @Test
    fun `filterRefSuggestions matches prefix case insensitively on name`() {
        val filtered = filterRefSuggestions(refSuggestions, "GOL")
        assertEquals(listOf("main/golang"), filtered.map { "${it.bucket}/${it.name}" })
    }

    @Test
    fun `filterRefSuggestions matches prefix on bucket name`() {
        val filtered = filterRefSuggestions(refSuggestions, "ext")
        assertEquals(listOf("extras/vscode", "extras/vscodium"), filtered.map { "${it.bucket}/${it.name}" })
    }

    @Test
    fun `filterRefSuggestions matches qualified bucket slash name prefix`() {
        val filtered = filterRefSuggestions(refSuggestions, "extras/vsc")
        assertEquals(listOf("extras/vscode", "extras/vscodium"), filtered.map { "${it.bucket}/${it.name}" })
    }

    @Test
    fun `filterRefSuggestions caps results at the limit`() {
        val many = (0 until 20).map { ManifestRefSuggestion("main", "app$it") }
        assertEquals(REF_SUGGESTION_LIMIT, filterRefSuggestions(many, "").size)
    }

    @Test
    fun `filterRefSuggestions returns empty when nothing matches`() {
        assertTrue(filterRefSuggestions(refSuggestions, "zzz").isEmpty())
    }

    // ---------------------------------------------------------------
    // userManifestRefs: only user messages count
    // ---------------------------------------------------------------

    @Test
    fun `userManifestRefs only scans user messages and merges in order`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "参考 @main/golang 的写法"),
            ChatMessage(role = ChatRole.Agent, text = "我对比了 @main/golang 和 @extras/vscode"),
            ChatMessage(role = ChatRole.User, text = "再加 @extras/vscode 的 shortcuts"),
        )
        assertEquals(
            listOf("main" to "golang", "extras" to "vscode"),
            userManifestRefs(messages),
        )
    }

    @Test
    fun `userManifestRefs ignores agent mentions entirely`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.Agent, text = "@main/golang is a good example"),
            ChatMessage(role = ChatRole.User, text = "ok"),
        )
        assertTrue(userManifestRefs(messages).isEmpty())
    }

    // ---------------------------------------------------------------
    // Real VM: relaxed onSend routing (CapturingBridge pattern)
    // ---------------------------------------------------------------

    @Test
    fun `first plain text message with no manifest routes to assist`(): Unit = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("what should I do next?").join() }
            }
            awaitRoutes(bridge)

            assertTrue(bridge.generated.isEmpty(), "plain text must not trigger generation")
            assertEquals(listOf("what should I do next?"), bridge.assisted)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `first valid GitHub URL routes to generation`(): Unit = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("https://github.com/sharkdp/fd prefer portable").join() }
            }
            awaitRoutes(bridge)

            assertEquals(1, bridge.generated.size, "a valid GitHub URL must trigger generation")
            assertEquals("https://github.com/sharkdp/fd", bridge.generated.single().first)
            assertEquals("prefer portable", bridge.generated.single().second)
            assertTrue(bridge.assisted.isEmpty(), "generation must not also assist")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `non github url input routes to assist instead of failing`(): Unit = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("http://example.com/app.zip the latest build").join() }
            }
            awaitRoutes(bridge)

            assertTrue(bridge.generated.isEmpty(), "a non-GitHub URL must not trigger generation")
            assertEquals(
                listOf("http://example.com/app.zip the latest build"),
                bridge.assisted,
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `pure image send with empty manifest routes to assist`(): Unit = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.attachImages(listOf(pngImage("shot-1"))).join()
                    vm.onSend("").join()
                }
            }
            awaitRoutes(bridge)

            assertTrue(bridge.generated.isEmpty())
            assertEquals(1, bridge.assistImages.size, "exactly one assist call expected")
            assertEquals(
                listOf("shot-1"),
                bridge.assistImages.single().map { String(it.bytes) },
                "the attached screenshot must reach the engine",
            )
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // onReadManifestRef wiring: session-persistent tool registration
    // ---------------------------------------------------------------

    @Test
    fun `assist round with @ reference passes a non null read handler`() = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("参考 @main/golang 的写法").join() }
            }
            awaitRoutes(bridge)

            assertEquals(1, bridge.readRefHandlers.size)
            assertTrue(bridge.readRefHandlers.single() != null, "a round with an @ reference must pass a handler")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `assist round without @ reference passes null read handler`() = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("remove the notes field please").join() }
            }
            awaitRoutes(bridge)

            assertEquals(1, bridge.readRefHandlers.size)
            assertTrue(bridge.readRefHandlers.single() == null, "a round without any @ reference must pass null")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `handler stays present in later rounds without new references`() = runBlocking {
        val bridge = RouteCapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    vm.onSend("参考 @main/golang 的写法").join()
                    awaitUntil { !vm.container.stateFlow.value.running }
                    vm.onSend("再把 version 改成 2.0").join()
                    awaitUntil { bridge.readRefHandlers.size == 2 }
                }
            }

            assertEquals(2, bridge.readRefHandlers.size)
            assertTrue(bridge.readRefHandlers[0] != null)
            assertTrue(
                bridge.readRefHandlers[1] != null,
                "session-persistent semantics: a later round still passes the handler once a @ reference exists",
            )
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // scanManifestRefSuggestions: local bucket app index
    // ---------------------------------------------------------------

    @Test
    fun `scanManifestRefSuggestions lists manifests across buckets`() {
        writeBucketManifest("main", "golang", "{}")
        writeBucketManifest("extras", "vscode", "{}")
        writeBucketManifest("extras", "vscodium", "{}")
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        val suggestions = scanManifestRefSuggestions(scoopClient)

        assertEquals(
            listOf(
                ManifestRefSuggestion("extras", "vscode"),
                ManifestRefSuggestion("extras", "vscodium"),
                ManifestRefSuggestion("main", "golang"),
            ),
            suggestions,
        )
    }

    @Test
    fun `scanManifestRefSuggestions skips non manifest and nested files`() {
        writeBucketManifest("main", "golang", "{}")
        tempDir.resolve("buckets/main/bucket/.hidden").apply { mkdirs() }
        tempDir.resolve("buckets/main/bucket/notes.txt").writeText("x")
        tempDir.resolve("buckets/main/scripts").apply { mkdirs() }
        tempDir.resolve("buckets/main/scripts/prefix.json").writeText("{}")
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        val suggestions = scanManifestRefSuggestions(scoopClient)

        assertEquals(listOf(ManifestRefSuggestion("main", "golang")), suggestions)
    }

    @Test
    fun `scanManifestRefSuggestions returns empty when buckets dir is missing`() {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        assertTrue(scanManifestRefSuggestions(scoopClient).isEmpty())
    }

    // ---------------------------------------------------------------
    // readManifestRefReply: real ScoopClient against a temp scoop root
    // ---------------------------------------------------------------

    @Test
    fun `handler returns success with manifest embedded verbatim`() {
        val manifestText = """{"version":"1.21.0","bin":"golang.exe"}"""
        writeBucketManifest("main", "golang", manifestText)
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        val reply = readManifestRefReply(scoopClient, "main", "golang")

        val json = Json.parseToJsonElement(reply).jsonObject
        assertEquals(true, json["success"]!!.jsonPrimitive.boolean)
        assertEquals("main", json["bucket"]!!.jsonPrimitive.content)
        assertEquals("golang", json["name"]!!.jsonPrimitive.content)
        val expectedPath = tempDir.resolve("buckets/main/bucket/golang.json").absolutePath
        assertEquals(expectedPath, json["path"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(manifestText),
            json["manifest"],
            "manifest must be embedded as a JSON value, preserving the original content",
        )
    }

    @Test
    fun `handler returns failure when bucket does not exist`() {
        writeBucketManifest("main", "golang", "{}")
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        val reply = readManifestRefReply(scoopClient, "man", "golang")

        val json = Json.parseToJsonElement(reply).jsonObject
        assertEquals(false, json["success"]!!.jsonPrimitive.boolean)
        val error = json["error"]!!.jsonPrimitive.content
        assertTrue("man" in error, "error must name the missing bucket, got: $error")
    }

    @Test
    fun `handler returns failure when app is missing from the bucket`() {
        writeBucketManifest("main", "golang", "{}")
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        val reply = readManifestRefReply(scoopClient, "main", "rust")

        val json = Json.parseToJsonElement(reply).jsonObject
        assertEquals(false, json["success"]!!.jsonPrimitive.boolean)
        val error = json["error"]!!.jsonPrimitive.content
        assertTrue("rust" in error && "main" in error, "error must be diagnosable, got: $error")
    }

    @Test
    fun `handler returns failure on unreadable or invalid json manifest`() {
        writeBucketManifest("main", "golang", "not json at all")
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )

        val reply = readManifestRefReply(scoopClient, "main", "golang")

        val json = Json.parseToJsonElement(reply).jsonObject
        assertEquals(false, json["success"]!!.jsonPrimitive.boolean)
        assertTrue("error" in json)
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Writes buckets/<bucket>/bucket/<name>.json under the temp scoop root. */
    private fun writeBucketManifest(bucket: String, name: String, content: String) {
        val file = tempDir.resolve("buckets").resolve(bucket).resolve("bucket").resolve("$name.json")
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun pngImage(id: String): PendingImage =
        PendingImage(id = id, bytes = id.toByteArray(), mimeType = "image/png")

    private class RouteCapturingBridge(configRepository: ConfigRepository) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        /** Instructions handed to each assist call. */
        val assisted = mutableListOf<String>()

        /** Images handed to each assist call. */
        val assistImages = mutableListOf<List<AiImage>>()

        /** Read handlers handed to each assist call (null = no @ reference). */
        val readRefHandlers = mutableListOf<(suspend (bucket: String, name: String) -> String)?>()

        /** (url, userPrompt) pairs handed to each generate call. */
        val generated = mutableListOf<Pair<String, String?>>()

        override fun startAssist(
            currentManifest: String,
            instruction: String,
            history: List<String>,
            suggestedAppName: String?,
            overrideModel: String?,
            images: List<AiImage>,
            ignoreAppNames: Set<String>,
            onPhase: ((String) -> Unit)?,
            onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onSaveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)?,
            onInstallApp: (suspend (bucket: String?, name: String) -> String)?,
            onReadManifestRef: (suspend (bucket: String, name: String) -> String)?,
            onFinished: (Result<com.scoopai.core.ManifestResult>) -> Unit,
        ) {
            assisted.add(instruction)
            assistImages.add(images)
            readRefHandlers.add(onReadManifestRef)
            onFinished(Result.success(ManifestResult(manifest = currentManifest)))
        }

        override fun startGenerate(
            url: String,
            userPrompt: String?,
            resumeFrom: scooper.service.AiErrorAction?,
            overrideModel: String?,
            ignoreAppNames: Set<String>,
            onPhase: (String) -> Unit,
            onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onFinished: (Result<ManifestResult>) -> Unit,
        ) {
            generated.add(url to userPrompt)
            onFinished(Result.success(ManifestResult(manifest = "{\"version\":\"1.0.0\"}")))
        }
    }

    private class FakeConfigRepository : ConfigRepository() {
        private val config = UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "test-key",
            aiBaseUrl = "http://127.0.0.1:9",
        )

        override fun getConfig(): UIConfig = config

        override fun setConfig(config: UIConfig) = Unit
    }

    private suspend fun awaitRoutes(bridge: RouteCapturingBridge) {
        awaitUntil { bridge.assisted.isNotEmpty() || bridge.generated.isNotEmpty() }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            withContext(Dispatchers.Default) { delay(10) }
        }
    }

    private fun createViewModel(bridge: ScoopAiBridge): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = bridge,
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = AppsRepository(
                scoopClient = scoopClient,
                gitHistoryService = GitHistoryService(),
            ),
            gitHistoryService = GitHistoryService(),
        )
    }
}

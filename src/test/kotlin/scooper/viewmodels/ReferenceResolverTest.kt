package scooper.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.service.BucketIndexEnv
import java.io.File

/**
 * TDD tests for [ReferenceResolver], the local bucket index behind the @
 * completion menu and the `read_manifest_reference` tool backend. The fake env
 * only exposes a root directory, so nothing touches a real Scoop installation.
 */
class ReferenceResolverTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------

    /** Temp-dir Scoop layout: buckets/<bucket>/bucket/<name>.json, with read counting. */
    private inner class FakeEnv : BucketIndexEnv {
        var rootReads = 0
            private set

        override val bucketsBaseDir: File
            get() {
                rootReads++
                return tempDir.resolve("buckets")
            }
    }

    /** Env whose root lookup always fails, to prove scan errors never escape. */
    private class FailingEnv(private val failure: () -> Throwable) : BucketIndexEnv {
        override val bucketsBaseDir: File get() = throw failure()
    }

    /** Writes buckets/<bucket>/bucket/<name>.json under the temp root. */
    private fun writeBucketManifest(bucket: String, name: String, content: String) {
        val file = tempDir.resolve("buckets").resolve(bucket).resolve("bucket").resolve("$name.json")
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun resolver(env: BucketIndexEnv = FakeEnv()) = ReferenceResolver(env)

    // ---------------------------------------------------------------
    // ensureIndex: one-shot, best-effort bucket scan
    // ---------------------------------------------------------------

    @Test
    fun `ensureIndex returns an empty index when the scoop root has no buckets`() = runBlocking {
        assertEquals(emptyList<ManifestRefSuggestion>(), resolver().ensureIndex())
    }

    @Test
    fun `ensureIndex lists manifests across buckets`() = runBlocking {
        writeBucketManifest("main", "golang", "{}")
        writeBucketManifest("extras", "vscode", "{}")
        writeBucketManifest("extras", "vscodium", "{}")

        val index = resolver().ensureIndex()!!.sortedWith(compareBy({ it.bucket }, { it.name }))

        assertEquals(
            listOf(
                ManifestRefSuggestion("extras", "vscode"),
                ManifestRefSuggestion("extras", "vscodium"),
                ManifestRefSuggestion("main", "golang"),
            ),
            index,
        )
    }

    @Test
    fun `ensureIndex degrades to an empty index when the root cannot be read`() = runBlocking {
        val resolver = resolver(FailingEnv { IllegalStateException("root unavailable") })

        assertEquals(emptyList<ManifestRefSuggestion>(), resolver.ensureIndex())
    }

    @Test
    fun `ensureIndex rethrows cancellation instead of degrading`() {
        val resolver = resolver(FailingEnv { CancellationException("cancelled") })

        assertThrows(CancellationException::class.java) {
            runBlocking { resolver.ensureIndex() }
        }
    }

    @Test
    fun `ensureIndex scans once and reports null afterwards`() = runBlocking {
        writeBucketManifest("main", "golang", "{}")
        val env = FakeEnv()
        val resolver = resolver(env)

        val first = resolver.ensureIndex()
        val readsAfterFirst = env.rootReads
        val second = resolver.ensureIndex()

        assertEquals(listOf(ManifestRefSuggestion("main", "golang")), first)
        assertNull(second, "the latch must skip a second scan")
        assertEquals(readsAfterFirst, env.rootReads, "a latched resolver must not touch the disk again")
    }

    // ---------------------------------------------------------------
    // readRef: tool backend, always answers JSON
    // ---------------------------------------------------------------

    @Test
    fun `readRef embeds the manifest verbatim`() = runBlocking {
        val manifestText = """{"version":"1.21.0","bin":"golang.exe"}"""
        writeBucketManifest("main", "golang", manifestText)

        val reply = resolver().readRef("main", "golang")

        val json = Json.parseToJsonElement(reply).jsonObject
        assertEquals(true, json["success"]!!.jsonPrimitive.boolean)
        assertEquals("main", json["bucket"]!!.jsonPrimitive.content)
        assertEquals("golang", json["name"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(manifestText), json["manifest"])
    }

    @Test
    fun `readRef degrades when the bucket or the app is missing`() = runBlocking {
        writeBucketManifest("main", "golang", "{}")
        val resolver = resolver()

        val missingBucket = Json.parseToJsonElement(resolver.readRef("man", "golang")).jsonObject
        assertEquals(false, missingBucket["success"]!!.jsonPrimitive.boolean)
        assertTrue("man" in missingBucket["error"]!!.jsonPrimitive.content)

        val missingApp = Json.parseToJsonElement(resolver.readRef("main", "rust")).jsonObject
        assertEquals(false, missingApp["success"]!!.jsonPrimitive.boolean)
        val error = missingApp["error"]!!.jsonPrimitive.content
        assertTrue("rust" in error && "main" in error, "error must stay diagnosable, got: $error")
    }

    @Test
    fun `readRef degrades on malformed manifest json`() = runBlocking {
        writeBucketManifest("main", "golang", "not json at all")

        val json = Json.parseToJsonElement(resolver().readRef("main", "golang")).jsonObject

        assertEquals(false, json["success"]!!.jsonPrimitive.boolean)
        assertTrue("error" in json)
    }
}

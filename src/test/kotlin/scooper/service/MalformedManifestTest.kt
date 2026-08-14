package scooper.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import scooper.taskqueue.TaskQueue

/**
 * Malformed-manifest robustness tests.
 * One bad manifest in a bucket must never crash the app (see parseShortcuts
 * IndexOutOfBoundsException that killed startup for a customer with an empty
 * shortcuts array).
 */
class MalformedManifestTest {

    private val client = ScoopClient(ScoopLogStream(), TaskQueue())

    private fun manifest(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // ---- buildAppFromJson via reflection-free path: use public wrappers ----

    @Test
    fun `empty shortcuts array does not crash`() {
        val json = Json.parseToJsonElement(
            """{"version": "1.0", "description": "app", "shortcuts": []}"""
        ).let { it as JsonObject }
        val app = buildViaLoadAll(json) { }
        assertEquals(emptyList<scooper.data.ShortCut>(), app!!.shortcuts)
    }

    @Test
    fun `normal nested shortcuts parse`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement(
                """{"version": "1.0", "shortcuts": [["App", "app.exe"], ["App2", "app2.exe"]]}"""
            ) as JsonObject
        ) { }
        assertEquals(2, app!!.shortcuts!!.size)
    }

    @Test
    fun `single non-nested shortcut pair parses`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement(
                """{"version": "1.0", "shortcuts": ["App", "app.exe"]}"""
            ) as JsonObject
        ) { }
        assertEquals(1, app!!.shortcuts!!.size)
        assertEquals("App", app.shortcuts!![0].title)
    }

    @Test
    fun `shortcuts with single-element pair is skipped`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement("""{"version": "1.0", "shortcuts": [["only"]]}""") as JsonObject
        ) { }
        assertEquals(emptyList<ScooperShortCutAlias>(), app!!.shortcuts)
    }

    @Test
    fun `shortcuts with non-primitive element is skipped`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement("""{"version": "1.0", "shortcuts": [[{"a": 1}, "b"]]}""") as JsonObject
        ) { }
        assertEquals(emptyList<ScooperShortCutAlias>(), app!!.shortcuts)
    }

    @Test
    fun `shortcuts as object does not crash`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement("""{"version": "1.0", "shortcuts": {"weird": "manifest"}}""") as JsonObject
        ) { }
        assertEquals(emptyList<ScooperShortCutAlias>(), app!!.shortcuts)
    }

    @Test
    fun `architecture as non-object does not crash url resolution`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement("""{"version": "1.0", "architecture": "broken"}""") as JsonObject
        ) { }
        assertNotNull(app)
        assertNull(app!!.url)
    }

    @Test
    fun `architecture with non-object arch block does not crash`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement("""{"version": "1.0", "architecture": {"64bit": "broken"}}""") as JsonObject
        ) { }
        assertNotNull(app)
    }

    @Test
    fun `top-level url string resolves`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement("""{"version": "1.0", "url": "https://example.com/app.zip"}""") as JsonObject
        ) { }
        assertEquals("https://example.com/app.zip", app!!.url)
    }

    @Test
    fun `architecture url array with object element does not crash`() {
        val app = buildViaLoadAll(
            Json.parseToJsonElement(
                """{"version": "1.0", "architecture": {"64bit": {"url": [{"nested": 1}]}}}"""
            ) as JsonObject
        ) { }
        assertNotNull(app)
        assertNull(app!!.url)
    }

    // ---- helper: exercise the full manifest -> App path used at startup ----

    private typealias ScooperShortCutAlias = scooper.data.ShortCut

    private fun buildViaLoadAll(json: JsonObject, unused: () -> Unit): scooper.data.App? =
        try {
            val method = ScoopClient::class.java.getDeclaredMethod(
                "buildAppFromJson",
                JsonObject::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                scooper.data.Bucket::class.java,
            )
            method.isAccessible = true
            method.invoke(client, json, "testapp", "1.0", false, null) as scooper.data.App
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Re-throw the real cause so a regression fails the test loudly
            throw e.cause ?: e
        }
}

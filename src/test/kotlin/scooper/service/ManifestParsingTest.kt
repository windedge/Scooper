package scooper.service

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.taskqueue.TaskQueue
import scooper.util.getString
import java.io.File
import java.nio.file.Path

/**
 * Real-world malformed manifests found in the wild (anderlli0053/DEV-tools bucket).
 * Scoop's PowerShell parser tolerates BOMs, comments and trailing commas, so
 * buckets contain such manifests; we must parse them too instead of erroring.
 */
class ManifestParsingTest {

    private val client = ScoopClient(ScoopLogStream(), TaskQueue())

    @TempDir
    lateinit var manifestTempDir: Path

    private fun parseFile(content: String): JsonObject? {
        val file = File(manifestTempDir.toFile(), "test-manifest.json")
        file.writeText(content)
        val method = ScoopClient::class.java.getDeclaredMethod("tryParseManifest", File::class.java)
        method.isAccessible = true
        return method.invoke(client, file) as JsonObject?
    }

    @Test
    fun `manifest with UTF-8 BOM parses`() {
        // PowerShell-written manifests are UTF-8 with BOM (seen in DEV-tools bucket)
        val json = parseFile("\uFEFF{\"version\": \"1.0\", \"homepage\": \"https://example.com\"}")
        assertNotNull(json)
        assertEquals("1.0", json!!.getString("version"))
    }

    @Test
    fun `manifest with trailing comma in array parses`() {
        // scoop's PowerShell parser tolerates trailing commas (goldutil manifest)
        val json = parseFile("""{"version": "1.0", "bin": ["goldutil.exe",]}""")
        assertNotNull(json)
    }

    @Test
    fun `manifest with comments parses`() {
        // JSONC with // line comments (seen in .dprint.json style configs)
        val json = parseFile(
            """{
                // scoop manifest with comments
                "version": "1.0"
            }"""
        )
        assertNotNull(json)
        assertEquals("1.0", json!!.getString("version"))
    }

    @Test
    fun `invalid manifest returns null instead of throwing`() {
        assertNull(parseFile("not json at all {"))
    }

    @Test
    fun `root-level JSON array is not a manifest and is skipped`() {
        // custom-snippets.json in DEV-tools is a VS Code snippets array
        val json = parseFile("[{\"name\": \"Scoop app manifest template\"}]")
        assertNull(json)
    }

    @Test
    fun `manifest with single-quoted value is skipped as unparseable`() {
        // fonts-nasu.json uses single quotes - invalid JSON that breaks scoop too
        assertNull(parseFile("""{"version": "1.0", "url": 'https://example.com/a.zip'}"""))
    }
}

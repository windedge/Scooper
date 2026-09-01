package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for [classifyManifestFinish] / [isScoopManifestJson], the top-level pure
 * functions extracted from CreateViewModel so the exhausted escape-door rule is directly
 * testable: a valid JSON manifest with refine exhaustion must keep invalidJson=false so
 * the UI can still enable Test Install / Save; only invalid JSON disables the buttons.
 *
 * Validity uses the CLI bar: kotlinx Json parse AND root element is a JsonObject;
 * blank manifest counts as invalid.
 */
class CreateViewModelFinishClassificationTest {

    private val validManifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"sha256:abc"}"""

    // ---------------------------------------------------------------
    // valid JSON + not exhausted: fully ready
    // ---------------------------------------------------------------

    @Test
    fun `valid JSON without exhaustion is fully ready`() {
        val c = classifyManifestFinish(validManifest, exhausted = false)
        assertFalse(c.invalidJson, "valid JSON must not be flagged invalid")
        assertFalse(c.finishedWithIssues, "no issues when valid and not exhausted")
    }

    // ---------------------------------------------------------------
    // valid JSON + exhausted: escape door stays open
    // ---------------------------------------------------------------

    @Test
    fun `valid JSON with exhaustion keeps invalidJson false but finishedWithIssues true`() {
        val c = classifyManifestFinish(validManifest, exhausted = true)
        assertFalse(c.invalidJson, "valid JSON must not disable Test Install / Save")
        assertTrue(c.finishedWithIssues, "badge/toast must still report issues")
    }

    // ---------------------------------------------------------------
    // invalid JSON: always invalid, always finishedWithIssues
    // ---------------------------------------------------------------

    @Test
    fun `invalid JSON with exhaustion flags both invalidJson and finishedWithIssues`() {
        val c = classifyManifestFinish("not json {{", exhausted = true)
        assertTrue(c.invalidJson)
        assertTrue(c.finishedWithIssues)
    }

    @Test
    fun `invalid JSON alone flags finishedWithIssues even without exhaustion`() {
        val c = classifyManifestFinish("""{"version":"""" , exhausted = false)
        assertTrue(c.invalidJson)
        assertTrue(c.finishedWithIssues)
    }

    // ---------------------------------------------------------------
    // finishedWithIssuesNotice: refine-aware issues notice text
    // ---------------------------------------------------------------

    @Test
    fun `finishedWithIssuesNotice says Generation for the initial generate scenario`() {
        assertEquals(
            "Generation finished with issues",
            finishedWithIssuesNotice(isRefineScenario = false),
        )
    }

    @Test
    fun `finishedWithIssuesNotice says Refine for the refine scenario`() {
        assertEquals(
            "Refine finished with issues",
            finishedWithIssuesNotice(isRefineScenario = true),
        )
    }

    // ---------------------------------------------------------------
    // blank manifest counts as invalid JSON
    // ---------------------------------------------------------------

    @Test
    fun `blank manifest is invalid JSON`() {
        assertTrue(classifyManifestFinish("", exhausted = false).invalidJson)
        assertTrue(classifyManifestFinish("   \n  ", exhausted = false).invalidJson)
        assertTrue(classifyManifestFinish("", exhausted = true).invalidJson)
    }

    // ---------------------------------------------------------------
    // CLI bar: parses as JSON but root is not an object => invalid manifest
    // ---------------------------------------------------------------

    @Test
    fun `valid JSON that is not an object is still an invalid manifest`() {
        assertTrue(classifyManifestFinish("""["a","b"]""", exhausted = false).invalidJson)
        assertTrue(classifyManifestFinish("""42""", exhausted = false).invalidJson)
        assertTrue(classifyManifestFinish(""""hello"""", exhausted = false).invalidJson)
        assertTrue(classifyManifestFinish("null", exhausted = false).invalidJson)
    }

    @Test
    fun `isScoopManifestJson accepts a normal JSON object and rejects the rest`() {
        assertTrue(isScoopManifestJson(validManifest))
        assertFalse(isScoopManifestJson(""))
        assertFalse(isScoopManifestJson("[1,2]"))
        assertFalse(isScoopManifestJson("{broken"))
        assertEquals(false, isScoopManifestJson("\"str\""))
    }
}
package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhaseSummaryFormatterTest {

    // -------------------------------------------------------------------------
    // formatPhaseStart
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseStart returns contextual message for research with app name`() {
        val text = PhaseSummaryFormatter.formatPhaseStart("research", "fd", refineAttempt = 0)
        assertEquals("Researching repository metadata, releases, and assets for fd...", text)
    }

    @Test
    fun `formatPhaseStart returns generic message for research without app name`() {
        val text = PhaseSummaryFormatter.formatPhaseStart("research", null, refineAttempt = 0)
        assertEquals("Researching repository metadata, releases, and assets...", text)
    }

    @Test
    fun `formatPhaseStart returns specific message for analyze and generate`() {
        assertEquals(
            "Analyzing Windows package (hashes, archive structure, executables)...",
            PhaseSummaryFormatter.formatPhaseStart("analyze", "fd", refineAttempt = 0),
        )
        assertEquals(
            "Assembling Scoop manifest schema (architecture, checkver, autoupdate)...",
            PhaseSummaryFormatter.formatPhaseStart("generate", "fd", refineAttempt = 0),
        )
    }

    @Test
    fun `formatPhaseStart returns attempt count for refine`() {
        assertEquals(
            "Refining manifest (attempt 1/3): resolving validation & review feedback...",
            PhaseSummaryFormatter.formatPhaseStart("refine", "fd", refineAttempt = 1),
        )
        assertEquals(
            "Refining manifest (attempt 2/3): resolving validation & review feedback...",
            PhaseSummaryFormatter.formatPhaseStart("refine", "fd", refineAttempt = 2),
        )
    }

    // -------------------------------------------------------------------------
    // formatPhaseOutput: Research
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseOutput summarizes research output with version and assets`() {
        val researchJson = """
            {
                "status": "OK",
                "version": "10.2.0",
                "suggested_app_name": "fd",
                "windows_assets": [
                    {"url": "https://.../fd.zip", "architecture": "64bit", "asset_type": "zip"}
                ]
            }
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("research", researchJson)
        assertEquals("Found release v10.2.0 with 1 Windows asset (zip)", summary)
    }

    @Test
    fun `formatPhaseOutput summarizes research with no windows assets`() {
        val researchJson = """{"status":"NO_WINDOWS_ASSETS","windows_assets":[]}"""
        val summary = PhaseSummaryFormatter.formatPhaseOutput("research", researchJson)
        assertEquals("No Windows assets found for this repository", summary)
    }

    // -------------------------------------------------------------------------
    // formatPhaseOutput: Analyze
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseOutput summarizes analyze output with strategy and executable`() {
        val analyzeJson = """
            {
                "status": "OK",
                "strategy": "portable_zip",
                "bin": ["fd.exe"],
                "entries": [{"architecture": "64bit", "url": "...", "hash": "abc"}]
            }
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("analyze", analyzeJson)
        assertEquals("Detected strategy 'portable_zip' with executable 'fd.exe'", summary)
    }

    // -------------------------------------------------------------------------
    // formatPhaseOutput: Generate & Refine
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseOutput summarizes generate manifest json`() {
        val manifestJson = """
            {
                "version": "10.2.0",
                "architecture": {
                    "64bit": {"url": "https://...", "hash": "abc"}
                },
                "checkver": "github",
                "autoupdate": {"architecture": {"64bit": {"url": "..."}}}
            }
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("generate", manifestJson)
        assertTrue(summary!!.contains("Generated manifest v10.2.0"))
        assertTrue(summary.contains("64bit"))
        assertTrue(summary.contains("checkver & autoupdate"))
    }

    @Test
    fun `formatPhaseOutput summarizes refine manifest with pre_install and shortcuts`() {
        val manifestJson = """
            {
                "version": "0.3.0",
                "architecture": {
                    "64bit": {"url": "https://...", "hash": "abc"}
                },
                "shortcuts": [["Minke.exe", "Minke"]],
                "pre_install": ["Expand-7zipArchive ..."]
            }
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("refine", manifestJson)
        assertTrue(summary!!.contains("Updated manifest v0.3.0"))
        assertTrue(summary.contains("64bit"))
        assertTrue(summary.contains("pre_install"))
        assertTrue(summary.contains("shortcuts"))
    }

    @Test
    fun `formatPhaseOutput summarizes refine manifest with persist and environment`() {
        val manifestJson = """
            {
                "version": "1.2.0",
                "persist": ["data", "config"],
                "env_set": {"APP_HOME": "${'$'}dir"}
            }
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("refine", manifestJson)
        assertTrue(summary!!.contains("Updated manifest v1.2.0"))
        assertTrue(summary.contains("persist"))
        assertTrue(summary.contains("environment"))
    }

    @Test
    fun `formatPhaseOutput summarizes refine minimal manifest json`() {
        val manifestJson = """{"version": "10.2.0"}"""
        val summary = PhaseSummaryFormatter.formatPhaseOutput("refine", manifestJson)
        assertTrue(summary!!.contains("Updated manifest v10.2.0"))
    }

    @Test
    fun `formatPhaseOutput returns null on malformed json without throwing`() {
        assertNull(PhaseSummaryFormatter.formatPhaseOutput("research", "invalid-json"))
        assertNull(PhaseSummaryFormatter.formatPhaseOutput("unknown_phase", "{}"))
    }
}

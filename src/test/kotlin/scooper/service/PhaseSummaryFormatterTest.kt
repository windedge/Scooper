package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhaseSummaryFormatterTest {

    // -------------------------------------------------------------------------
    // formatPhaseStart
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseStart returns contextual message for research with app name`() {
        val text = PhaseSummaryFormatter.formatPhaseStart("research", "fd", attempt = 0)
        assertEquals("Researching fd...", text)
    }

    @Test
    fun `formatPhaseStart returns generic message for research without app name`() {
        val text = PhaseSummaryFormatter.formatPhaseStart("research", null, attempt = 0)
        assertEquals("Researching repository...", text)
    }

    @Test
    fun `formatPhaseStart returns specific message for analyze and generate`() {
        assertEquals(
            "Analyzing package...",
            PhaseSummaryFormatter.formatPhaseStart("analyze", "fd", attempt = 0),
        )
        assertEquals(
            "Writing manifest...",
            PhaseSummaryFormatter.formatPhaseStart("generate", "fd", attempt = 0),
        )
    }

    @Test
    fun `formatPhaseStart returns working title for assist without attempt suffix`() {
        assertEquals(
            "Working on your request...",
            PhaseSummaryFormatter.formatPhaseStart("assist", "fd", attempt = 1),
        )
        assertEquals(
            "Working on your request...",
            PhaseSummaryFormatter.formatPhaseStart("assist", "fd", attempt = 2),
        )
    }

    @Test
    fun `formatPhaseStart returns attempt count for repair`() {
        assertEquals(
            "Repairing manifest (attempt 1)...",
            PhaseSummaryFormatter.formatPhaseStart("repair", "fd", attempt = 1),
        )
        assertEquals(
            "Repairing manifest (attempt 2)...",
            PhaseSummaryFormatter.formatPhaseStart("repair", "fd", attempt = 2),
        )
    }

    @Test
    fun `formatPhaseStart repair shows attempt without hardcoded denominator`() {
        val text = PhaseSummaryFormatter.formatPhaseStart("repair", "fd", attempt = 4)
        assertEquals("Repairing manifest (attempt 4)...", text)
        assertFalse(text.contains("/3"))
    }

    @Test
    fun `formatPhaseStart titles stay short so the single-line ellipsis is only a fallback`() {
        listOf("research", "analyze", "generate", "assist", "repair", "verify").forEach { phase ->
            val title = PhaseSummaryFormatter.formatPhaseStart(phase, "fd", attempt = 1)
            assertTrue(
                title.length <= 40,
                "phase '$phase' start title must stay short, got: $title",
            )
            assertFalse(title.contains('\n'), "phase '$phase' title must stay on one line")
        }
    }

    // -------------------------------------------------------------------------
    // formatPhaseStart: verify (architecture proposal 4.6)
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseStart returns validating title for verify`() {
        assertEquals(
            "Validating manifest...",
            PhaseSummaryFormatter.formatPhaseStart("verify", "fd", attempt = 0),
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
    // formatPhaseOutput: Generate & Revision
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
    fun `formatPhaseOutput summarizes assist manifest with pre_install and shortcuts`() {
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
        val summary = PhaseSummaryFormatter.formatPhaseOutput("assist", manifestJson)
        assertTrue(summary!!.contains("Updated manifest v0.3.0"))
        assertTrue(summary.contains("64bit"))
        assertTrue(summary.contains("pre_install"))
        assertTrue(summary.contains("shortcuts"))
    }

    @Test
    fun `formatPhaseOutput summarizes assist manifest with persist and environment`() {
        val manifestJson = """
            {
                "version": "1.2.0",
                "persist": ["data", "config"],
                "env_set": {"APP_HOME": "${'$'}dir"}
            }
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("assist", manifestJson)
        assertTrue(summary!!.contains("Updated manifest v1.2.0"))
        assertTrue(summary.contains("persist"))
        assertTrue(summary.contains("environment"))
    }

    @Test
    fun `formatPhaseOutput summarizes assist minimal manifest json`() {
        val manifestJson = """{"version": "10.2.0"}"""
        val summary = PhaseSummaryFormatter.formatPhaseOutput("assist", manifestJson)
        assertTrue(summary!!.contains("Updated manifest v10.2.0"))
    }

    @Test
    fun `formatPhaseOutput returns null on malformed json without throwing`() {
        assertNull(PhaseSummaryFormatter.formatPhaseOutput("research", "invalid-json"))
        assertNull(PhaseSummaryFormatter.formatPhaseOutput("unknown_phase", "{}"))
    }

    @Test
    fun `formatPhaseOutput returns null for verify which has no output summary`() {
        val manifestJson = """{"version": "1.0"}"""
        assertNull(PhaseSummaryFormatter.formatPhaseOutput("verify", manifestJson))
    }

    // -------------------------------------------------------------------------
    // formatPhaseOutput: _meta.activities (phase activities, engine 0.1.11)
    // -------------------------------------------------------------------------

    @Test
    fun `formatPhaseOutput prefers _meta activities array for generate output`() {
        val output = """
            {
                "_meta": {"activities": ["Added checkver for GitHub releases", "Set bin to fd.exe"]},
                "version": "10.2.0",
                "checkver": "github"
            }
        """.trimIndent()
        assertEquals(
            "Added checkver for GitHub releases\nSet bin to fd.exe",
            PhaseSummaryFormatter.formatPhaseOutput("generate", output),
        )
    }

    @Test
    fun `formatPhaseOutput prefers _meta activities array for repair output`() {
        val output = """
            {
                "_meta": {"activities": ["Added persist directive to keep config"]},
                "version": "1.2.0",
                "persist": ["config"]
            }
        """.trimIndent()
        assertEquals(
            "Added persist directive to keep config",
            PhaseSummaryFormatter.formatPhaseOutput("repair", output),
        )
    }

    @Test
    fun `formatPhaseOutput falls back to template when _meta activities missing`() {
        val output = """{"version": "10.2.0", "checkver": "github"}"""
        val summary = PhaseSummaryFormatter.formatPhaseOutput("generate", output)
        assertTrue(summary!!.contains("Generated manifest v10.2.0"))
        assertTrue(summary.contains("checkver"))
    }

    @Test
    fun `formatPhaseOutput falls back to template on empty or non-string activities`() {
        val emptyArray = """{"_meta": {"activities": []}, "version": "1.0"}"""
        val summaryEmpty = PhaseSummaryFormatter.formatPhaseOutput("generate", emptyArray)
        assertTrue(summaryEmpty!!.contains("Generated manifest v1.0"))

        val mixedArray = """{"_meta": {"activities": ["ok entry", 42]}, "version": "1.0"}"""
        val summaryMixed = PhaseSummaryFormatter.formatPhaseOutput("generate", mixedArray)
        assertTrue(summaryMixed!!.contains("Generated manifest v1.0"))

        val blankString = """{"_meta": {"activities": "   "}, "version": "1.0"}"""
        val summaryBlank = PhaseSummaryFormatter.formatPhaseOutput("generate", blankString)
        assertTrue(summaryBlank!!.contains("Generated manifest v1.0"))
    }

    @Test
    fun `formatPhaseOutput accepts string shaped activities as single bullet`() {
        val output = """{"_meta": {"activities": "Lowered architecture to 32bit"}, "version": "1.0"}"""
        assertEquals(
            "Lowered architecture to 32bit",
            PhaseSummaryFormatter.formatPhaseOutput("repair", output),
        )
    }

    @Test
    fun `formatPhaseOutput strips code fence before summarizing repair output`() {
        val fenced = """
            ```json
            {
                "version": "2.1.0",
                "bin": "app.exe"
            }
            ```
        """.trimIndent()
        val summary = PhaseSummaryFormatter.formatPhaseOutput("repair", fenced)
        assertTrue(summary!!.contains("Updated manifest v2.1.0"))
        assertTrue(summary.contains("bin"))
    }

    @Test
    fun `formatPhaseOutput still summarizes unfenced manifest after fence stripping change`() {
        val plain = """{"version": "3.0.0", "persist": ["data"]}"""
        val summary = PhaseSummaryFormatter.formatPhaseOutput("assist", plain)
        assertTrue(summary!!.contains("Updated manifest v3.0.0"))
    }
}

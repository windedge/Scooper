package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// ---------------------------------------------------------------
// buildDownloadConfirmDetail: secondary note under the user bubble
// after answering a download confirmation questionnaire. The note is
// a single line: the URL (middle elided when overlong) given the whole
// 88-char budget; when no URL is present the file name (plus "(size)")
// is the fallback. elideUrlMiddle is the pure helper behind it.
// ---------------------------------------------------------------
class AskUserBubbleDetailTest {

    private fun downloadRequest(
        prompt: String = "Ready to download installer/archive 'fd-10.3.0-x86_64-pc-windows-msvc.zip' (1.2 MB) for analysis from:\nhttps://github.com/sharkdp/fd/releases/download/v10.3.0/fd-v10.3.0-x86_64-pc-windows-msvc.zip",
        options: List<QuestionnaireOption> = listOf(
            QuestionnaireOption(label = "Download and proceed", description = "Download fd-10.3.0-x86_64-pc-windows-msvc.zip into cache for SHA-256 and archive inspection"),
            QuestionnaireOption(label = "Skip downloading", description = "Skip downloading; agent will proceed with alternative information"),
        ),
    ): AskUserRequest = AskUserRequest(
        questions = listOf(
            QuestionnaireQuestion(id = "download_confirm", label = "Download", prompt = prompt, options = options),
        ),
    )

    // -----------------------------------------------------------
    // elideUrlMiddle: pure URL middle-elision helper
    // -----------------------------------------------------------

    @Test
    fun `short URL is returned unchanged`() {
        assertEquals(
            "https://example.com/dl/app.zip",
            elideUrlMiddle("https://example.com/dl/app.zip", 88),
        )
    }

    @Test
    fun `long URL keeps scheme host and file name while eliding the middle`() {
        // Spec example: scheme + host stay complete, the last path segment
        // stays complete, and the longest intermediate prefix that fits wins.
        val url = "https://github.com/tanlethanh/zedra/archive/refs/tags/v0.4.4.zip"
        assertEquals(
            "https://github.com/tanlethanh/…/v0.4.4.zip",
            elideUrlMiddle(url, 44),
        )
    }

    @Test
    fun `query is dropped when dropping it alone makes the URL fit`() {
        val url = "https://example.com/dl/app.zip?token=abc123&ref=release"
        assertEquals("https://example.com/dl/app.zip", elideUrlMiddle(url, 40))
    }

    @Test
    fun `query is dropped and the middle elided when the URL is still too long`() {
        val url = "https://example.com/a/b/c/app.zip?token=abcdef"
        assertEquals("https://example.com/…/app.zip", elideUrlMiddle(url, 30))
    }

    @Test
    fun `extremely long file name is tail truncated within maxLen`() {
        val url = "https://example.com/dl/" + "n".repeat(100) + ".zip"
        val elided = elideUrlMiddle(url, 40)
        assertEquals("https://example.com/…/" + "n".repeat(17) + "…", elided)
        assertEquals(40, elided.length)
    }

    @Test
    fun `host only URL fits as is and tail truncates when overlong`() {
        assertEquals("https://example.com", elideUrlMiddle("https://example.com", 88))
        assertEquals("https://exampl…", elideUrlMiddle("https://example.com", 15))
    }

    @Test
    fun `tiny maxLen still respects the cap`() {
        assertEquals("http…", elideUrlMiddle("https://example.com/a/b/c/app.zip", 5))
        assertEquals("…", elideUrlMiddle("https://example.com/a/b/c/app.zip", 1))
        assertEquals("", elideUrlMiddle("https://example.com/a/b/c/app.zip", 0))
    }

    @Test
    fun `elided result never exceeds maxLen across budgets`() {
        val url = "https://github.com/tanlethanh/zedra/archive/refs/tags/v0.4.4.zip"
        for (maxLen in listOf(5, 10, 20, 31, 32, 40, 44, 48, 50, 64, 88)) {
            assertTrue(elideUrlMiddle(url, maxLen).length <= maxLen, "maxLen=$maxLen")
        }
    }

    // -----------------------------------------------------------
    // Prompt extraction: URL gets the whole single-line budget
    // -----------------------------------------------------------

    @Test
    fun `full prompt with quoted name size and URL yields just the URL`() {
        val request = downloadRequest(
            prompt = "Ready to download installer/archive 'main.zip' (1.2 MB) for analysis from:\n" +
                "https://github.com/iAmCorey/Wake/releases/download/v0.3.1/main.zip",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals(
            "https://github.com/iAmCorey/Wake/releases/download/v0.3.1/main.zip",
            detail,
        )
    }

    @Test
    fun `prompt without size yields just the URL`() {
        val request = downloadRequest(
            prompt = "Ready to download installer/archive 'main.zip' for analysis from:\n" +
                "https://example.com/dl/main.zip",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("https://example.com/dl/main.zip", detail)
    }

    @Test
    fun `size variants are appended to the file name fallback when no URL is present`() {
        for (size in listOf("512 KB", "3.4 GB", "1.5 GiB", "2,048 KB")) {
            val request = downloadRequest(
                prompt = "Ready to download installer/archive 'app.zip' ($size) for analysis from:",
            )
            val detail = buildDownloadConfirmDetail(request, "yes")
            assertEquals("app.zip ($size)", detail, "size=$size")
        }
    }

    @Test
    fun `URL with query parameters is kept when the single line fits`() {
        val request = downloadRequest(
            prompt = "Ready to download installer/archive 'app.zip' for analysis from:\n" +
                "https://example.com/dl/app.zip?token=abc123&ref=release",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("https://example.com/dl/app.zip?token=abc123&ref=release", detail)
    }

    @Test
    fun `URL only prompt with query parameters stays a single line`() {
        val request = downloadRequest(
            prompt = "Ready to download installer/archive from:\n" +
                "https://example.com/releases/latest/download?token=abc",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("https://example.com/releases/latest/download?token=abc", detail)
    }

    @Test
    fun `overlong URL alone is middle elided within the line budget`() {
        val longUrl = "https://example.com/" + "a".repeat(240)
        val request = downloadRequest(prompt = "Ready to download from:\n$longUrl")
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals(88, detail!!.length)
        assertTrue(detail.endsWith("…"))
        assertTrue(detail.startsWith("https://example.com/…/"))
    }

    @Test
    fun `URL is given the whole budget even when the prompt has a long file name`() {
        val longName = "f".repeat(70) + ".zip"
        val request = downloadRequest(
            prompt = "Ready to download installer/archive '$longName' for analysis from:\n" +
                "https://example.com/dl/app.zip",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("https://example.com/dl/app.zip", detail)
    }

    // -----------------------------------------------------------
    // Trailing / wrapping quote stripping on extracted names
    // -----------------------------------------------------------

    @Test
    fun `wrapped quotes are stripped from the extracted file name`() {
        val request = downloadRequest(prompt = "Confirm download of 'name.zip' from the release page")
        assertEquals("name.zip", buildDownloadConfirmDetail(request, "yes"))
    }

    @Test
    fun `trailing quote leaked by bare asset extraction is stripped`() {
        // Regression: the bare-asset regex used to swallow the closing quote
        // of tar.gz style names, producing "....tar.gz'" in the bubble.
        val request = downloadRequest(
            prompt = "Confirm download of 'zedra-x86_64-pc-windows-msvc.tar.gz' for analysis",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("zedra-x86_64-pc-windows-msvc.tar.gz", detail)
        assertFalse(detail!!.contains('\''))
    }

    // -----------------------------------------------------------
    // Prompt extraction: legacy fallback cases
    // -----------------------------------------------------------

    @Test
    fun `generic option label falls back to the URL details from the prompt`() {
        val request = downloadRequest()
        val detail = buildDownloadConfirmDetail(request, "Skip downloading")
        assertEquals(
            "https://github.com/sharkdp/fd/releases/download/…/fd-v10.3.0-x86_64-pc-windows-msvc.zip",
            detail,
        )
    }

    @Test
    fun `Download and proceed label yields the concrete URL details from the prompt`() {
        // Regression: the generic "Download and proceed" label used to shadow the
        // actual asset name, making the two confirmation bubbles indistinguishable.
        val request = AskUserRequest(
            questions = listOf(
                QuestionnaireQuestion(
                    id = "download_confirm",
                    label = "Download",
                    prompt = "Ready to download installer/archive 'wake-0.3.1-windows-x86_64.zip' (1.2 MB) for analysis from:\nhttps://github.com/wakeapp/wake/releases/download/v0.3.1/wake-0.3.1-windows-x86_64.zip",
                    options = listOf(
                        QuestionnaireOption(label = "Download and proceed", description = "Download the asset for analysis"),
                        QuestionnaireOption(label = "Skip downloading", description = "Skip downloading"),
                    ),
                ),
            ),
        )
        val detail = buildDownloadConfirmDetail(request, "Download: user selected: 1. Download and proceed")
        assertEquals(
            "https://github.com/wakeapp/wake/releases/download/v0.3.1/wake-0.3.1-windows-x86_64.zip",
            detail,
        )
    }

    @Test
    fun `unmatched answer extracts the URL details from the prompt`() {
        val request = downloadRequest()
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals(
            "https://github.com/sharkdp/fd/releases/download/…/fd-v10.3.0-x86_64-pc-windows-msvc.zip",
            detail,
        )
    }

    @Test
    fun `extracts a URL when the prompt has no asset extension`() {
        val request = downloadRequest(
            prompt = "Ready to download installer/archive from:\nhttps://example.com/releases/latest/download",
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("https://example.com/releases/latest/download", detail)
    }

    @Test
    fun `extracts a 7z file name from the prompt`() {
        val request = downloadRequest(prompt = "Confirm download of 'portable-tool.7z' from the release page")
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("portable-tool.7z", detail)
    }

    @Test
    fun `uses the first download question when multiple questions exist`() {
        val request = AskUserRequest(
            questions = listOf(
                QuestionnaireQuestion(id = "download_confirm", prompt = "Ready to download 'first.zip' from:\nhttps://a.com/first.zip"),
                QuestionnaireQuestion(id = "download_confirm", prompt = "Ready to download 'second.exe' from:\nhttps://b.com/second.exe"),
            ),
        )
        val detail = buildDownloadConfirmDetail(request, "yes")
        assertEquals("https://a.com/first.zip", detail)
    }

    @Test
    fun `returns null when neither a URL nor an asset file name is extractable`() {
        val request = AskUserRequest(
            questions = listOf(
                QuestionnaireQuestion(id = "download_confirm", prompt = "Confirm download of the portable build"),
            ),
        )
        assertNull(buildDownloadConfirmDetail(request, "yes"))
    }

    // -----------------------------------------------------------
    // Asset-like option label path: verbatim, budgeted to one line
    // -----------------------------------------------------------

    @Test
    fun `asset-like option label wins over the prompt extraction`() {
        val request = downloadRequest(
            prompt = "Ready to download installer/archive 'fd-10.3.0-x86_64-pc-windows-msvc.zip' (1.2 MB) for analysis",
            options = listOf(
                QuestionnaireOption(label = "fd-setup.msi", description = "Fetch the MSI installer instead"),
                QuestionnaireOption(label = "Skip downloading", description = "Skip downloading; agent will proceed with alternative information"),
            ),
        )
        val detail = buildDownloadConfirmDetail(request, "Download: user selected: 1. fd-setup.msi")
        assertEquals("fd-setup.msi", detail)
    }

    @Test
    fun `a URL label counts as a concrete asset and is kept`() {
        val request = AskUserRequest(
            questions = listOf(
                QuestionnaireQuestion(
                    id = "download_confirm",
                    prompt = "Ready to download the installer archive?",
                    options = listOf(QuestionnaireOption(label = "https://example.com/dist/app-1.0.exe")),
                ),
            ),
        )
        val detail = buildDownloadConfirmDetail(request, "https://example.com/dist/app-1.0.exe")
        assertEquals("https://example.com/dist/app-1.0.exe", detail)
    }

    @Test
    fun `overlong detail is truncated with ellipsis`() {
        // The label must look like an asset so it is used verbatim; only then can
        // the 88-char truncation path be exercised.
        val longLabel = "D".repeat(200) + ".zip"
        val request = AskUserRequest(
            questions = listOf(
                QuestionnaireQuestion(
                    id = "download_confirm",
                    prompt = "Download?",
                    options = listOf(QuestionnaireOption(label = longLabel)),
                ),
            ),
        )
        val detail = buildDownloadConfirmDetail(request, longLabel)
        assertEquals(88, detail!!.length)
        assertTrue(detail.endsWith("…"))
        assertEquals("D".repeat(87) + "…", detail)
    }

    @Test
    fun `detail collapses newlines and extra whitespace`() {
        val request = AskUserRequest(
            questions = listOf(
                QuestionnaireQuestion(
                    id = "download_confirm",
                    prompt = "Download?",
                    options = listOf(QuestionnaireOption(label = "Download the  archive.zip\nand verify")),
                ),
            ),
        )
        val detail = buildDownloadConfirmDetail(request, "Download the  archive.zip\nand verify")
        assertEquals("Download the archive.zip and verify", detail)
    }

    // -----------------------------------------------------------
    // Gates: download prompt detection and empty inputs
    // -----------------------------------------------------------

    @Test
    fun `non download prompt returns null`() {
        val request = AskUserRequest(
            questions = listOf(QuestionnaireQuestion(id = "q1", prompt = "Which architecture do you prefer?")),
        )
        assertNull(buildDownloadConfirmDetail(request, "x64"))
    }

    @Test
    fun `null request returns null`() {
        assertNull(buildDownloadConfirmDetail(null, "yes"))
    }

    @Test
    fun `blank download prompt with nothing to extract returns null`() {
        val request = AskUserRequest(
            questions = listOf(QuestionnaireQuestion(id = "download_confirm", prompt = "   \n ")),
        )
        assertNull(buildDownloadConfirmDetail(request, "yes"))
    }

    @Test
    fun `resolveAskUserBubbleText mapping is unaffected by the detail builder`() {
        assertEquals("Download confirmed", resolveAskUserBubbleText("yes", isDownloadPrompt = true))
        assertEquals("Download skipped", resolveAskUserBubbleText("no", isDownloadPrompt = true))
        assertEquals("custom", resolveAskUserBubbleText("custom", isDownloadPrompt = true))
        assertEquals("yes", resolveAskUserBubbleText("yes", isDownloadPrompt = false))
    }
}

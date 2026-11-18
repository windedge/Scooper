package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.util.LocalizedText
import scooper.util.Strings
import java.util.Locale

/**
 * TDD for review-fix batch B: CreateState.error and AppSideEffect.Toast carry
 * structured LocalizedText instead of pre-resolved strings, so a language
 * switch re-renders state errors and toasts (Settings.kt rule).
 *
 * Four bare-English toast constructions must become catalog msgids:
 * the image cap, the direct-install name failure, the save success line and
 * the Settings save confirmation. The test swaps Strings to the zh_CN catalog
 * around the assertion and restores English afterwards; the gradle test JVM
 * is pinned to en_US.
 */
class StateErrorToastLocalizationTest {

    // ---------------------------------------------------------------
    // CreateState.error is structured LocalizedText
    // ---------------------------------------------------------------

    @Test
    fun `error field is a structured LocalizedText`() {
        // Compile-level pin: the state field type must be LocalizedText?.
        assertNull(CreateState().error)
        assertEquals(
            LocalizedText(msgid = "Please enter a GitHub repository URL (github.com/owner/repo)."),
            CreateState(error = CreateViewModel.invalidUrlHint()).error,
        )
    }

    @Test
    fun `invalidUrlHint stores the msgid not a rendered string`() {
        val hint = CreateViewModel.invalidUrlHint()
        assertEquals("Please enter a GitHub repository URL (github.com/owner/repo).", hint.msgid)
        assertNull(hint.raw, "static hint must be msgid-backed, not a raw snapshot")
    }

    @Test
    fun `no-assets error stores the msgid`() {
        assertEquals(
            LocalizedText(msgid = "No Windows assets found"),
            noAssetsErrorText(),
        )
    }

    @Test
    fun `readiness reason passes through as frozen LocalizedText`() {
        // Dynamic sentence: rendered once and frozen into LocalizedText(raw=...)
        // (documented exception, see CreateViewModel.requireAiReady).
        val reason = LocalizedText(msgid = "API key is missing. Fill it in Settings > AI, or set {{env}} environment variable.")
        val frozen = frozenReadinessReason(reason)
        assertEquals(reason.render(), frozen.raw)
        assertNull(frozen.msgid, "frozen reason keeps its already-rendered text in raw")
    }

    @Test
    fun `frozen readiness reason renders the same text as the source reason`() {
        val reason = LocalizedText(
            msgid = "API key is missing. Fill it in Settings > AI, or set {{env}} environment variable.",
            args = listOf("env" to "SCOOP_AI_API_KEY"),
        )
        assertEquals(reason.render(), frozenReadinessReason(reason).render())
    }

    // ---------------------------------------------------------------
    // the four bare-English toasts become msgids
    // ---------------------------------------------------------------

    @Test
    fun `image cap toast text is the msgid template`() {
        val toast = imageCapToast(dropped = 2)
        assertEquals(
            "At most {{max}} images allowed, {{dropped}} dropped",
            toast.text.msgid,
        )
        assertEquals("3", toast.text.args.toMap()["max"])
        assertEquals("2", toast.text.args.toMap()["dropped"])
    }

    @Test
    fun `direct install name failure toast is the msgid template`() {
        assertEquals(
            "Cannot determine the app name; save it to a bucket or enter an URL first",
            directInstallNameFailureToast().text.msgid,
        )
    }

    @Test
    fun `save success toast is the msgid template with args`() {
        val toast = saveSuccessToast(appName = "fd", bucket = "main")
        assertEquals(
            "Saved {{app}}.json to bucket \"{{bucket}}\"",
            toast.text.msgid,
        )
        assertEquals("fd", toast.text.args.toMap()["app"])
        assertEquals("main", toast.text.args.toMap()["bucket"])
    }

    @Test
    fun `settings save toast is the msgid template`() {
        assertEquals("AI settings saved", settingsSavedToast().text.msgid)
    }

    // ---------------------------------------------------------------
    // the new msgids are translated under zh_CN
    // ---------------------------------------------------------------

    @Test
    fun `new toast msgids are translated in the zh_CN catalog`() {
        val english = Strings.current
        try {
            Strings.update(Locale.SIMPLIFIED_CHINESE)
            val msgids = listOf(
                "At most {{max}} images allowed, {{dropped}} dropped",
                "Cannot determine the app name; save it to a bucket or enter an URL first",
                "Saved {{app}}.json to bucket \"{{bucket}}\"",
                "AI settings saved",
            )
            msgids.forEach { msgid ->
                assertNotEquals(
                    msgid,
                    Strings.current.tr(msgid),
                    "zh_CN catalog must translate: $msgid",
                )
            }
        } finally {
            Strings.update(Locale.ENGLISH)
            assertEquals(english.locale.toLanguageTag(), Strings.current.locale.toLanguageTag())
        }
    }

    @Test
    fun `toast text renders through tr at display time`() {
        val toast = saveSuccessToast(appName = "fd", bucket = "main")
        val rendered = toast.text.render()
        assertTrue(
            rendered.contains("fd") && rendered.contains("main"),
            "rendered toast must interpolate the args: $rendered",
        )
        assertEquals(tr("Saved {{app}}.json to bucket \"{{bucket}}\"", "app" to "fd", "bucket" to "main"), rendered)
    }

    // ---------------------------------------------------------------
    // helpers pinning the production shapes under test
    // ---------------------------------------------------------------

    private fun tr(msgid: String, vararg args: Pair<String, String>): String =
        scooper.util.tr(msgid, *args)
}

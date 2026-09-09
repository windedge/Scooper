package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.App
import scooper.taskqueue.TaskQueue

/**
 * TDD tests for reinstall semantics of [ScoopClient.update]:
 * - force = true must add `--force` so scoop re-applies a manifest even when
 *   the installed version equals the bucket version (otherwise update is a
 *   no-op exit 0 and the fixed manifest never lands).
 * - global keeps the `sudo scoop update -g` shape, local keeps `scoop update`.
 */
class ScoopClientUpdateForceTest {

    private val client = ScoopClient(ScoopLogStream(), TaskQueue())

    private fun app() = App(name = "myapp-scooper-test", latestVersion = "0")

    // ---------------------------------------------------------------
    // Command assembly: force flag
    // ---------------------------------------------------------------

    @Test
    fun `update args contain --force when force is true`() {
        val args = client.updateArgs(app(), global = false, force = true)
        assertEquals(listOf("scoop", "update", "--force", "myapp-scooper-test"), args)
        assertTrue("--force" in args)
    }

    @Test
    fun `update args omit --force by default`() {
        val args = client.updateArgs(app(), global = false, force = false)
        assertEquals(listOf("scoop", "update", "myapp-scooper-test"), args)
        assertFalse("--force" in args)
    }

    @Test
    fun `update args keep sudo and -g shape for global force`() {
        val args = client.updateArgs(app(), global = true, force = true)
        assertEquals(listOf("sudo", "scoop", "update", "-g", "--force", "myapp-scooper-test"), args)
    }

    // ---------------------------------------------------------------
    // Interface contract: force defaults to false for existing callers
    // ---------------------------------------------------------------

    @Test
    fun `scoop service updateApp defaults to non-forced update`() {
        // ScoopService.updateApp has no force param; the interface default keeps
        // its call site compiling unchanged and non-forced.
        val params = ScoopCli::update.parameters
        val forceParam = params.firstOrNull { it.name == "force" }
        assertTrue(forceParam != null, "ScoopCli.update must expose a force parameter")
        assertTrue(forceParam!!.isOptional, "force must default so existing callers stay unchanged")
    }
}

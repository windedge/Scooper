package scooper.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket

/**
 * TDD for the Discover "Chat with AI" handoff wiring: which Discover scopes
 * expose the handler, and that orphan (bucketless) apps never invoke it.
 */
class DiscoverModifyHandlerTest {

    private fun app(bucket: Bucket?) = App(
        name = "ripgrep",
        latestVersion = "14.1.0",
        status = AppStatus.UNINSTALL,
        bucket = bucket,
    )

    @Test
    fun `discover scope keeps the handler`() {
        val handler = discoverModifyHandler(scope = "") { _, _ -> }
        assertNotNull(handler)
    }

    @Test
    fun `installed and updates scopes hide the handler`() {
        assertNull(discoverModifyHandler(scope = "installed") { _, _ -> })
        assertNull(discoverModifyHandler(scope = "updates") { _, _ -> })
    }

    @Test
    fun `handler passes bucket and app name to the handoff`() {
        var received: Pair<String, String>? = null
        val handler = discoverModifyHandler(scope = "") { bucket, name -> received = bucket to name }

        handler!!.invoke(app(Bucket(name = "main", url = null)))

        assertEquals("main" to "ripgrep", received)
    }

    @Test
    fun `handler never invokes the handoff for a bucketless orphan app`() {
        var invoked = false
        val handler = discoverModifyHandler(scope = "") { _, _ -> invoked = true }

        handler!!.invoke(app(bucket = null))

        assertTrue(!invoked, "orphan app without a bucket must not trigger the Create handoff")
    }
}

package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the deployment state layer: derive, label, action.
 */
internal class DeploymentStateTest {

    // ---------------------------------------------------------------
    // Manifest / name resolution failures
    // ---------------------------------------------------------------

    @Test
    fun `blank manifest yields NoManifest with hidden label`() {
        val state = deriveDeploymentState(DeploymentInputs(manifestJson = "  "))
        assertEquals(DeploymentState.NoManifest, state)
        assertEquals("", deploymentLabel(state))
    }

    @Test
    fun `null manifest yields NoManifest`() {
        val state = deriveDeploymentState(DeploymentInputs(manifestJson = null))
        assertEquals(DeploymentState.NoManifest, state)
    }

    @Test
    fun `invalid json yields InvalidManifest with disabled Install label`() {
        val state = deriveDeploymentState(DeploymentInputs(manifestJson = "{not json"))
        assertEquals(DeploymentState.InvalidManifest, state)
        assertEquals("Install", deploymentLabel(state))
        assertEquals(DeploymentAction.None, deploymentAction(state, saveBucket = null))
    }

    @Test
    fun `manifest without version yields InvalidManifest`() {
        val state = deriveDeploymentState(DeploymentInputs(manifestJson = """{"name":"app"}"""))
        assertEquals(DeploymentState.InvalidManifest, state)
    }

    @Test
    fun `blank app name yields UnresolvedName with Install label`() {
        val state = deriveDeploymentState(
            DeploymentInputs(manifestJson = v("1.0"), appName = "  "),
        )
        assertEquals(DeploymentState.UnresolvedName, state)
        assertEquals("Install", deploymentLabel(state))
    }

    // ---------------------------------------------------------------
    // Not installed
    // ---------------------------------------------------------------

    @Test
    fun `not installed and unsaved yields NotInstalled with InstallDirect`() {
        val state = deriveDeploymentState(DeploymentInputs(manifestJson = v("1.0"), appName = "app"))
        assertEquals(DeploymentState.NotInstalled, state)
        assertEquals("Install", deploymentLabel(state))
        assertEquals(DeploymentAction.InstallDirect, deploymentAction(state, saveBucket = null))
    }

    @Test
    fun `not installed but saved yields InstallFromBucket`() {
        val state = deriveDeploymentState(DeploymentInputs(manifestJson = v("1.0"), appName = "app"))
        assertEquals(DeploymentAction.InstallFromBucket, deploymentAction(state, saveBucket = "main"))
    }

    // ---------------------------------------------------------------
    // Installed, same version
    // ---------------------------------------------------------------

    @Test
    fun `same version same bucket yields Same relation UpdateForce`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.0"),
                appName = "app",
                saveBucket = "main",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Same, state.relation)
        assertEquals("Reinstall", deploymentLabel(state))
        assertEquals(DeploymentAction.UpdateForce, deploymentAction(state, saveBucket = "main"))
    }

    @Test
    fun `same version different bucket yields UninstallThenInstall`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.0"),
                appName = "app",
                saveBucket = "extras",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Same, state.relation)
        assertEquals(DeploymentAction.UninstallThenInstall, deploymentAction(state, saveBucket = "extras"))
    }

    @Test
    fun `same version direct file install yields UninstallThenInstall`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.0"),
                appName = "app",
                installed = InstalledRecord("1.0", InstallOrigin.DirectFile, null),
            ),
        )
        assertEquals(InstallOrigin.DirectFile, state.origin)
        assertEquals(DeploymentAction.UninstallThenInstall, deploymentAction(state, saveBucket = null))
    }

    // ---------------------------------------------------------------
    // Installed, manifest newer
    // ---------------------------------------------------------------

    @Test
    fun `manifest newer same bucket yields Update label and UpdateForce`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.1"),
                appName = "app",
                saveBucket = "main",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Newer, state.relation)
        assertEquals("Update", deploymentLabel(state))
        assertEquals(DeploymentAction.UpdateForce, deploymentAction(state, saveBucket = "main"))
    }

    @Test
    fun `manifest newer different bucket yields UninstallThenInstall`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.1"),
                appName = "app",
                saveBucket = "extras",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Newer, state.relation)
        assertEquals(DeploymentAction.UninstallThenInstall, deploymentAction(state, saveBucket = "extras"))
    }

    // ---------------------------------------------------------------
    // Installed, manifest older / nightly
    // ---------------------------------------------------------------

    @Test
    fun `manifest older yields Older relation Reinstall label`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("0.9"),
                appName = "app",
                saveBucket = "main",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Older, state.relation)
        assertEquals("Reinstall", deploymentLabel(state))
        assertEquals(DeploymentAction.UpdateForce, deploymentAction(state, saveBucket = "main"))
    }

    @Test
    fun `nightly manifest version yields Unknown relation and Reinstall`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("nightly"),
                appName = "app",
                saveBucket = "main",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Unknown, state.relation)
        assertEquals("Reinstall", deploymentLabel(state))
    }

    // ---------------------------------------------------------------
    // Key regression: string equality, not natural-order equality
    // ---------------------------------------------------------------

    @Test
    fun `2024_01 vs 2024_1 are different so manifest is treated as Update`() {
        // Natural comparison says equal (2024.01 == 2024.1) but Scoop compares
        // versions as strings, so these must differ.
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("2024.01"),
                appName = "app",
                saveBucket = "main",
                installed = InstalledRecord("2024.1", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(VersionRelation.Newer, state.relation)
        assertEquals("Update", deploymentLabel(state))
    }

    // ---------------------------------------------------------------
    // Session interplay
    // ---------------------------------------------------------------

    @Test
    fun `same version installed this session hides the label and opens only`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.0"),
                appName = "app",
                saveBucket = "main",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
                inThisSession = true,
            ),
        )
        assertEquals(VersionRelation.Same, state.relation)
        assertEquals("", deploymentLabel(state))
        assertEquals(DeploymentAction.OpenOnly, deploymentAction(state, saveBucket = "main"))
    }

    @Test
    fun `unsaved manifest over a bucket install keeps UpdateForce with Reinstall label`() {
        // No new bucket exists, so reinstalling the installed content via update
        // is still the safest route; the label just reflects a re-install.
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("1.0"),
                appName = "app",
                saveBucket = null,
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals("Reinstall", deploymentLabel(state))
        assertEquals(DeploymentAction.UpdateForce, deploymentAction(state, saveBucket = null))
    }

    @Test
    fun `installed state exposes version and origin fields`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("2.5"),
                appName = "app",
                installed = InstalledRecord("2.4", InstallOrigin.Bucket, "extras"),
            ),
        ) as DeploymentState.Installed
        assertEquals("2.4", state.installedVersion)
        assertEquals("2.5", state.manifestVersion)
        assertEquals(InstallOrigin.Bucket, state.origin)
        assertEquals("extras", state.originBucket)
        assertEquals(false, state.inThisSession)
    }

    // ---------------------------------------------------------------
    // Bucket source for the deploy plan
    // ---------------------------------------------------------------

    @Test
    fun `deploy bucket prefers the recorded origin bucket over the save bucket`() {
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("2.0"),
                appName = "app",
                saveBucket = "extras",
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals("main", deployBucket(state, saveBucket = "extras"))
    }

    @Test
    fun `deploy bucket keeps the update alive after the save result was cleared`() {
        // The assist round and the dismissed result card both clear the save result;
        // the install record is what still names the bucket to update from.
        val state = deriveDeploymentState(
            DeploymentInputs(
                manifestJson = v("2.0"),
                appName = "app",
                saveBucket = null,
                installed = InstalledRecord("1.0", InstallOrigin.Bucket, "main"),
            ),
        )
        assertEquals(DeploymentAction.UpdateForce, deploymentAction(state, saveBucket = null))
        assertEquals("main", deployBucket(state, saveBucket = null))
    }

    @Test
    fun `deploy bucket falls back to the save bucket and is null without either`() {
        val installed = DeploymentState.Installed(
            installedVersion = "1.0",
            manifestVersion = "2.0",
            relation = VersionRelation.Newer,
            origin = InstallOrigin.DirectFile,
            originBucket = null,
            inThisSession = false,
        )
        assertEquals("extras", deployBucket(installed, saveBucket = "extras"))
        assertEquals(null, deployBucket(installed, saveBucket = null))
        assertEquals(null, deployBucket(DeploymentState.NotInstalled, saveBucket = null))
    }

    // ---------------------------------------------------------------
    // Deploy button gate
    // ---------------------------------------------------------------

    @Test
    fun `deploy button stays enabled for a plan that still has work to do`() {
        // Regression: the button must not latch off after an install in this
        // session, otherwise a version or bucket change can be saved but never deployed.
        assertTrue(
            deployButtonEnabled(
                manifestJson = v("1.2.0"),
                action = DeploymentAction.UpdateForce,
                running = false,
                testing = false,
                installing = false,
            ),
        )
        assertTrue(
            deployButtonEnabled(
                manifestJson = v("1.2.0"),
                action = DeploymentAction.UninstallThenInstall,
                running = false,
                testing = false,
                installing = false,
            ),
        )
    }

    @Test
    fun `deploy button is disabled without a plan, a valid manifest or an idle session`() {
        assertFalse(
            deployButtonEnabled(
                manifestJson = v("1.2.0"),
                action = DeploymentAction.None,
                running = false,
                testing = false,
                installing = false,
            ),
        )
        assertFalse(
            deployButtonEnabled(
                manifestJson = "{not json",
                action = DeploymentAction.InstallDirect,
                running = false,
                testing = false,
                installing = false,
            ),
        )
        for ((running, testing, installing) in listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
        )) {
            assertFalse(
                deployButtonEnabled(
                    manifestJson = v("1.2.0"),
                    action = DeploymentAction.InstallDirect,
                    running = running,
                    testing = testing,
                    installing = installing,
                ),
                "no operation may leave the deploy button enabled",
            )
        }
    }

    private companion object {
        fun v(version: String): String = """{"version":"$version"}"""
    }

    private val DeploymentState.relation: VersionRelation?
        get() = (this as? DeploymentState.Installed)?.relation

    private val DeploymentState.origin: InstallOrigin?
        get() = (this as? DeploymentState.Installed)?.origin
}

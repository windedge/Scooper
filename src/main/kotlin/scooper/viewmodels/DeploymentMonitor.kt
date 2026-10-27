package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import scooper.service.ScoopClient
import scooper.util.logger

/**
 * Deployment side of the Create workspace: the single place that reads the disk
 * to derive [DeploymentState] for the deploy button. Given the session's saved
 * state (the save result, engine suggestion, direct-install name and the active
 * manifest draft) it resolves the app name, reads the installed snapshot, the
 * install record and the bucket manifest copy, and derives the state the
 * [deriveDeploymentState] pure function renders.
 *
 * Owns no state: every call is a fresh read. The epoch guard and the intent
 * serialization stay in [CreateViewModel]; this class is the read/derive seam.
 */
internal class DeploymentMonitor(
    private val scoopClient: ScoopClient,
) {
    private val logger by logger()

    /**
     * Read the disk and derive the deployment state for the session. The bucket
     * copy of the manifest wins over the session draft because that file is what
     * Scoop will update from; the draft only stands in when the app was never
     * saved to a bucket. [fallbackManifestJson] may be null when the session has
     * no draft at all.
     */
    suspend fun derive(
        saveResult: SaveResult?,
        suggestedAppName: String?,
        directInstallName: String?,
        fallbackManifestJson: String?,
        inThisSession: Boolean,
    ): DeploymentState {
        val inputs = readDeploymentInputs(
            appName = deploymentAppName(saveResult, suggestedAppName, directInstallName),
            fallbackManifestJson = fallbackManifestJson,
            saveBucket = saveResult?.bucket,
            inThisSession = inThisSession,
        )
        return deriveDeploymentState(inputs)
    }

    /**
     * Disk side of the derivation. The bucket copy of the manifest
     * wins over the session draft because that file is what Scoop will update from;
     * the draft only stands in when the app was never saved to a bucket.
     */
    private suspend fun readDeploymentInputs(
        appName: String?,
        fallbackManifestJson: String?,
        saveBucket: String?,
        inThisSession: Boolean,
    ): DeploymentInputs = withContext(Dispatchers.IO) {
        val installedInfo = appName?.let { scoopClient.installedSnapshot()[it.lowercase()] }
        val record = installedInfo?.let { scoopClient.readInstallRecord(it.appDir.resolve("current").toPath()) }
        val installed = installedInfo?.let {
            InstalledRecord(
                version = it.version,
                origin = record?.origin ?: InstallOrigin.Unknown,
                originBucket = record?.bucket,
            )
        }
        val bucketManifest = listOfNotNull(saveBucket, record?.bucket)
            .distinct()
            .firstNotNullOfOrNull { bucketManifestText(it, appName) }
        DeploymentInputs(
            manifestJson = bucketManifest ?: fallbackManifestJson,
            appName = appName,
            saveBucket = saveBucket,
            installed = installed,
            inThisSession = inThisSession,
        )
    }

    /** Manifest text of [appName] in [bucket], or null when the bucket has no copy. */
    internal fun bucketManifestText(bucket: String, appName: String?): String? {
        if (appName.isNullOrBlank()) return null
        val bucketDir = scoopClient.bucketsBaseDir.resolve(bucket).resolve("bucket")
        val file = listOf(appName, appName.lowercase())
            .distinct()
            .map { bucketDir.resolve("$it.json") }
            .firstOrNull { it.isFile }
            ?: return null
        return try {
            ScoopClient.readManifestText(file)
        } catch (e: Exception) {
            logger.warn("Deployment refresh: failed to read $file: ${e.message}")
            null
        }
    }
}

package scooper.service

import scooper.data.App
import scooper.data.Bucket
import java.io.File

/**
 * Narrow bucket-root surface: everything the @ reference index and its
 * read_manifest_reference tool backend need. Implemented by [ScoopClient].
 */
interface BucketIndexEnv {
    val bucketsBaseDir: File
}

/**
 * Narrow Scoop-environment surface used by the shared manifest-save core in
 * CreateViewModel (manual dialog flow and the AI save_to_bucket tool).
 * Implemented by [ScoopClient]; tests fake it with a temp-dir layout.
 */
interface BucketSaveEnv : BucketIndexEnv {
    val bucketNames: List<String>

    /** Snapshot of installed apps, used when indexing the saved manifest. */
    fun installedSnapshot(): Map<String, InstalledAppInfo>

    /** Build app entries from the given manifest file names in one bucket. */
    fun buildAppsFromManifestNames(
        bucketDir: File,
        manifestNames: Set<String>,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
    ): List<App>
}

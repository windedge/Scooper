package scooper.service

import scooper.taskqueue.TaskQueue
import java.io.File

/** Throwaway Scoop root for tests. Junctions need no elevation on Windows, but JUnit
 *  recurses into them when deleting the temp dir, so [removeJunctions] must run first. */
class ScoopTempRoot(val root: File) {

    /** Junction links created for this test; unlinked by [removeJunctions]. */
    private val junctions = mutableListOf<File>()

    fun client(globalRoot: Boolean = false): ScoopClient = ScoopClient(
        logStream = ScoopLogStream(),
        taskQueue = TaskQueue(),
        rootDirOverride = root,
        globalRootDirOverride = if (globalRoot) File(root, "global") else null,
    )

    /** Minimal manifest: the version plus a bucket marker in the description. */
    fun writeManifest(bucket: String, name: String, version: String) =
        writeManifestJson(bucket, name, """{"version":"$version","description":"$bucket copy"}""")

    /** Full-control manifest write, for tests asserting on bin/architecture fields. */
    fun writeManifestJson(bucket: String, name: String, json: String) {
        val dir = File(root, "buckets/$bucket/bucket").apply { mkdirs() }
        File(dir, "$name.json").writeText(json)
    }

    /**
     * Simulate an installed app: version dir plus a `current` junction, with the
     * install record (default: the bucket, if any) inside the version dir.
     */
    fun markInstalled(
        name: String,
        version: String,
        recordBucket: String?,
        recordJson: String? = recordBucket?.let { """{"bucket":"$it"}""" },
        global: Boolean = false,
    ) {
        val versionDir = junction(name, version, global)
        if (recordJson != null) {
            File(versionDir, "scoop-install.json").writeText(recordJson)
        }
    }

    /** Version dir plus the `current` junction; returns the version dir. */
    fun junction(name: String, version: String, global: Boolean = false): File {
        val appsRoot = if (global) File(root, "global/apps") else File(root, "apps")
        val versionDir = File(appsRoot, "$name/$version").apply { mkdirs() }
        val link = File(appsRoot, "$name/current")
        link.parentFile?.mkdirs()
        val process = ProcessBuilder(
            "cmd",
            "/c",
            "mklink",
            "/J",
            link.absolutePath,
            versionDir.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        val exit = process.waitFor()
        require(exit == 0) { "could not create junction $link (exit $exit)" }
        junctions += link
        return versionDir
    }

    /** Register a junction created outside [junction] (e.g. by a shim script). */
    fun trackJunction(link: File) {
        junctions += link
    }

    /** rmdir unlinks the junction only, never the target directory. */
    fun removeJunctions() {
        for (junction in junctions) {
            if (!junction.exists()) continue
            ProcessBuilder("cmd", "/c", "rmdir", junction.absolutePath).start().waitFor()
        }
        junctions.clear()
    }
}

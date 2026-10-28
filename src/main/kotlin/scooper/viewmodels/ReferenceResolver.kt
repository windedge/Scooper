package scooper.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import scooper.service.BucketIndexEnv
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local bucket index behind the @ completion menu and the engine's
 * `read_manifest_reference` tool (architecture A: the host owns the local bucket
 * lookup, the model only asks for @bucket/app). Scanning happens at most once per
 * session; every failure degrades to an empty index or a success:false tool reply
 * so a broken or empty Scoop root never breaks a generation run.
 */
internal class ReferenceResolver(private val env: BucketIndexEnv) {
    /** One-shot latch: the index is scanned at most once per session. */
    private val indexLoaded = AtomicBoolean(false)

    /**
     * Index of the first call for the menu; null once the latch is closed, so the
     * caller can skip a no-op reduce. An empty root yields an empty list.
     */
    suspend fun ensureIndex(): List<ManifestRefSuggestion>? {
        if (!indexLoaded.compareAndSet(false, true)) return null
        return withContext(Dispatchers.IO) { scanManifestRefSuggestions(env) }
    }

    /**
     * Tool backend for `read_manifest_reference`: never throws (cancellation
     * excepted), unreadable paths and bad JSON come back as success:false.
     */
    suspend fun readRef(bucket: String, name: String): String =
        withContext(Dispatchers.IO) { readManifestRefReply(env, bucket, name) }
}

/**
 * Locate buckets/<bucket>/bucket/<name>.json in the local scoop root and embed the
 * manifest's raw JSON as a JSON value, so the model receives the manifest itself and
 * not a quoted string. Any failure degrades to a diagnosable success:false reply so
 * the session survives.
 */
internal fun readManifestRefReply(env: BucketIndexEnv, bucket: String, name: String): String {
    val bucketDir = env.bucketsBaseDir.resolve(bucket)
    if (!bucketDir.isDirectory) {
        return readManifestRefFailure("bucket '$bucket' not found")
    }
    val manifestFile = bucketDir.resolve("bucket/$name.json")
    if (!manifestFile.isFile) {
        return readManifestRefFailure("app '$name' not found in bucket '$bucket'")
    }
    return try {
        val manifest = Json.parseToJsonElement(manifestFile.readText())
        buildJsonObject {
            put("success", true)
            put("bucket", bucket)
            put("name", name)
            put("path", manifestFile.absolutePath)
            put("manifest", manifest)
        }.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        readManifestRefFailure("failed to read manifest for '$name' in bucket '$bucket': ${e.message}")
    }
}

private fun readManifestRefFailure(error: String): String = buildJsonObject {
    put("success", false)
    put("error", error)
}.toString()

/**
 * Builds the @ completion menu index by listing every manifest json directly under
 * buckets/<bucket>/bucket/, the same layout [readManifestRefReply] reads from, so
 * every suggestion is actually loadable by the tool. Best effort: unreadable dirs
 * are skipped; only the directory listing itself failing yields an empty list.
 */
internal fun scanManifestRefSuggestions(env: BucketIndexEnv): List<ManifestRefSuggestion> {
    val suggestions = mutableListOf<ManifestRefSuggestion>()
    val bucketDirs = try {
        env.bucketsBaseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
    for (bucketDir in bucketDirs) {
        val entries = try {
            bucketDir.resolve("bucket").listFiles() ?: continue
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            continue
        }
        for (entry in entries) {
            val fileName = entry.name
            if (!fileName.endsWith(".json") || !entry.isFile) continue
            suggestions += ManifestRefSuggestion(bucketDir.name, fileName.removeSuffix(".json"))
        }
    }
    return suggestions
}

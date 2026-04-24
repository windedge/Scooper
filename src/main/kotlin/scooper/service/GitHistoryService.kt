package scooper.service

import org.slf4j.LoggerFactory
import scooper.data.AppVersion
import scooper.data.AppVersionSource
import scooper.util.execute
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Indexes Git commit times for bucket manifests and provides history version queries.
 *
 * Designed to run in background without blocking UI.
 */
class GitHistoryService {

    companion object {
        private const val CURRENT_INDEX_VERSION = 1
        private val logger = LoggerFactory.getLogger(GitHistoryService::class.java)

        fun indexStateFor(headCommit: String): String = "v$CURRENT_INDEX_VERSION:$headCommit"

        fun commitFromIndexState(indexState: String?): String? {
            if (indexState == null) return null
            return indexState.substringAfter(':', indexState).takeIf { it.isNotBlank() }
        }

        fun isCurrentIndexState(indexState: String?, headCommit: String): Boolean {
            return indexState == indexStateFor(headCommit)
        }
    }

    data class ManifestTimes(
        val createAt: LocalDateTime? = null,
        val updateAt: LocalDateTime? = null,
    )

    data class GitHistoryIndexResult(
        val headCommit: String,
        /** key = manifest relative path, e.g. "bucket/7zip.json" */
        val manifestTimes: Map<String, ManifestTimes>,
        val fullIndex: Boolean,
    )

    /** Check if the bucket directory is a valid Git repository. */
    fun isGitBucket(bucketDir: File): Boolean {
        return bucketDir.resolve(".git").isDirectory
    }

    /** Get the current HEAD commit hash for a bucket, or null if Git is unavailable. */
    fun getHeadCommit(bucketDir: File): String? {
        if (!isGitBucket(bucketDir)) return null
        return try {
            val result = execute(
                "git", "rev-parse", "HEAD",
                asShell = false,
                workingDir = bucketDir,
            )
            if (result.resultCode == 0) {
                result.output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
        } catch (e: Exception) {
            logger.warn("Failed to get HEAD commit for ${bucketDir.name}: ${e.message}")
            null
        }
    }

    /**
     * Index manifest times from Git history.
     *
     * @param bucketDir       The bucket directory (a Git repo).
     * @param knownManifestNames  Set of manifest filenames we care about (e.g. "7zip.json").
     * @param lastIndexedCommit   The commit we last indexed up to. If null, do a full index.
     * @return Index result, or null if HEAD unchanged or Git failure.
     */
    fun indexBucketManifestTimes(
        bucketDir: File,
        knownManifestNames: Set<String>,
        lastIndexedCommit: String?,
    ): GitHistoryIndexResult? {
        if (!isGitBucket(bucketDir)) return null

        val currentHead = getHeadCommit(bucketDir) ?: return null

        // HEAD unchanged → skip
        if (currentHead == lastIndexedCommit) return null

        // Determine if incremental or full
        val fullIndex = lastIndexedCommit == null || !commitExists(bucketDir, lastIndexedCommit)

        val range = if (fullIndex) {
            null // full log
        } else {
            "$lastIndexedCommit..$currentHead" // incremental
        }

        val times = parseGitLog(bucketDir, range, knownManifestNames) ?: return null

        return GitHistoryIndexResult(
            headCommit = currentHead,
            manifestTimes = times,
            fullIndex = fullIndex,
        )
    }

    /**
     * P2: Load historical versions for a manifest from Git history.
     */
    fun loadManifestVersions(
        bucketDir: File,
        manifestRelativePath: String,
        limit: Int = 50,
    ): List<AppVersion> {
        if (!isGitBucket(bucketDir)) return emptyList()

        return try {
            val result = execute(
                "git", "log", "--format=%H%x09%ct%x09%s", "-n", "$limit", "--", manifestRelativePath,
                asShell = false,
                workingDir = bucketDir,
            )
            if (result.resultCode != 0) return emptyList()

            result.output.mapNotNull { line ->
                val parts = line.trim().split('\t')
                if (parts.size < 3) return@mapNotNull null
                val commit = parts[0]
                val timestamp = parts[1].toLongOrNull()?.let {
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault())
                }
                val message = parts.subList(2, parts.size).joinToString("\t")
                AppVersion(
                    version = "", // Will be filled by reading manifest content
                    source = AppVersionSource.Git,
                    commit = commit,
                    commitTime = timestamp,
                    message = message,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to load manifest versions for $manifestRelativePath: ${e.message}")
            emptyList()
        }
    }

    /**
     * P2: Read the content of a manifest at a specific commit.
     */
    fun readManifestAtCommit(
        bucketDir: File,
        commit: String,
        manifestRelativePath: String,
    ): String? {
        if (!isGitBucket(bucketDir)) return null

        return try {
            val result = execute(
                "git", "show", "$commit:$manifestRelativePath",
                asShell = false,
                workingDir = bucketDir,
            )
            if (result.resultCode == 0) {
                result.output.joinToString("\n")
            } else null
        } catch (e: Exception) {
            logger.warn("Failed to read manifest at $commit for $manifestRelativePath: ${e.message}")
            null
        }
    }

    // ==================== Internal ====================

    /** Check if a commit hash exists in the current history. */
    private fun commitExists(bucketDir: File, commit: String): Boolean {
        return try {
            val result = execute(
                "git", "cat-file", "-t", commit,
                asShell = false,
                workingDir = bucketDir,
            )
            result.resultCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse `git log --format=%x1e%ct --name-status --no-renames [range] -- bucket/`
     *
     * Output format:
     * ```
     * \x1e<timestamp>
     * A       bucket/7zip.json
     * M       bucket/git.json
     *
     * \x1e<timestamp>
     * M       bucket/nodejs.json
     * ...
     * ```
     *
     * We scan from newest to oldest:
     * - First occurrence of a manifest → updateAt
     * - Status `A` (Added) → createAt
     * - For full index, we keep scanning until all manifests have both createAt and updateAt
     */
    private fun parseGitLog(
        bucketDir: File,
        range: String?,
        knownManifestNames: Set<String>,
    ): Map<String, ManifestTimes>? {
        val args = mutableListOf(
            "git", "-c", "core.quotepath=false",
            "log", "--format=%x1e%ct", "--name-status", "--no-renames",
        )
        if (range != null) {
            args.add(range)
        }
        args.add("--")
        args.add("bucket/")

        return try {
            val result = execute(
                *args.toTypedArray(),
                asShell = false,
                workingDir = bucketDir,
            )
            if (result.resultCode != 0) {
                logger.warn("git log failed for ${bucketDir.name}: ${result.output.joinToString("\n")}")
                return null
            }

            val output = result.output
            parseGitLogOutput(output, knownManifestNames)
        } catch (e: Exception) {
            logger.warn("Failed to parse git log for ${bucketDir.name}: ${e.message}")
            null
        }
    }

    internal fun parseGitLogOutput(
        lines: List<String>,
        knownManifestNames: Set<String>,
    ): Map<String, ManifestTimes> {
        val times = mutableMapOf<String, ManifestTimes>()
        var currentTimestamp: LocalDateTime? = null

        for (line in lines) {
            if (line.isBlank()) continue

            // Commit header: starts with \u001e followed by timestamp.
            // Do not trim before this check: trim() removes ASCII control char \x1e.
            if (line.startsWith('\u001e')) {
                val tsStr = line.substring(1).trim()
                currentTimestamp = tsStr.toLongOrNull()?.let {
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault())
                }
                continue
            }

            val trimmed = line.trim()
            // File status line: "A\tbucket/7zip.json" or "M\tbucket/7zip.json"
            val parts = trimmed.split('\t')
            if (parts.size < 2 || currentTimestamp == null) continue

            val status = parts[0].trim()
            val filePath = parts[1].trim()

            // Only process files in bucket/ directory that are known manifests
            val fileName = filePath.removePrefix("bucket/")
            if (fileName == filePath) continue // not under bucket/
            if (fileName !in knownManifestNames) continue

            val existing = times.getOrPut(fileName) { ManifestTimes() }

            // First occurrence (newest) → updateAt
            if (existing.updateAt == null) {
                times[fileName] = existing.copy(updateAt = currentTimestamp)
            }

            // Status A (Added) → createAt
            if (status == "A" && existing.createAt == null) {
                times[fileName] = (times[fileName] ?: existing).copy(createAt = currentTimestamp)
            }
        }

        return times
    }
}

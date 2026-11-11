package scooper.service

import com.scoopai.tools.cache.ScoopCacheManager
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.data.InstallOrigin
import scooper.data.ShortCut
import scooper.taskqueue.TaskQueue
import scooper.util.ProgressParser
import scooper.util.ScoopConfigManager
import scooper.util.dirSize
import scooper.util.execute
import scooper.util.executeSuspend
import scooper.util.findExecutable
import scooper.util.getString
import scooper.util.killAllSubProcesses
import scooper.util.OperationCancelledException
import scooper.util.scoopTestEnv
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.ZoneId

/** Snapshot of a single installed app at a point in time. */
data class InstalledAppInfo(
    val name: String,
    val version: String,
    val isGlobal: Boolean,
    val appDir: File,
)

/** Launch decision for an installed app: a start command from the install dir, or a Start Menu lnk. */
internal sealed interface LaunchTarget {
    /** cmd /c start command with its working directory. */
    data class StartCommand(val args: List<String>, val workDir: File) : LaunchTarget

    /** Start Menu shortcut file launched by absolute path. */
    data class Lnk(val file: File) : LaunchTarget
}

/**
 * Adapter for Scoop CLI and filesystem operations.
 * Provides low-level access to the local Scoop environment.
 */
class ScoopClient(
    val logStream: ScoopLogStream,
    private val taskQueue: TaskQueue,
    private val downloader: ManifestDownloader = ManifestDownloader(),
    private val rootDirOverride: File? = null,
    private val scoopConfigFile: File? = null,
    private val globalRootDirOverride: File? = null,
) : ScoopCli, BucketSaveEnv {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Scoop manifests in the wild commonly contain comments and trailing
         *  commas (PowerShell's ConvertFrom-Json tolerates both), so match that
         *  leniency when parsing manifest files. */
        val manifestJson = Json {
            allowComments = true
            allowTrailingComma = true
        }

        /** Scoop prefers scoop-install.json and falls back to the legacy install.json. */
        private val installRecordFileNames = listOf("scoop-install.json", "install.json")

        /** Read a manifest file, stripping a leading UTF-8 BOM. PowerShell-written
         *  manifests are UTF-8 with BOM, which kotlinx.serialization rejects. */
        fun readManifestText(file: File): String =
            file.readText().removePrefix("\uFEFF")
    }

    // ==================== Environment Paths ====================

    val configFile: File
        get() = File(System.getenv("USERPROFILE")).resolve(".config/scoop/config.json")

    val rootDir: File
        get() {
            rootDirOverride?.let { return it }
            val scoop = System.getenv("SCOOP")
            if (!scoop.isNullOrEmpty()) {
                val root = File(scoop)
                if (root.exists()) return root
            }
            return File(System.getenv("USERPROFILE")).resolve("scoop")
        }

    val globalRootDir: File
        get() {
            globalRootDirOverride?.let { return it }
            val scoop = System.getenv("SCOOP_GLOBAL")
            if (!scoop.isNullOrEmpty()) {
                val root = File(scoop)
                if (root.exists()) return root
            }
            return File(System.getenv("ALLUSERSPROFILE")).resolve("scoop")
        }

    override val bucketsBaseDir: File
        get() = rootDir.resolve("buckets")

    override val bucketNames: List<String>
        get() = bucketsBaseDir.list()?.asList() ?: listOf()

    val bucketDirs: List<File>
        get() = bucketNames.map { bucketsBaseDir.resolve(it) }

    val localInstalledAppDirs: List<File>
        get() = rootDir.resolve("apps")
            .listFiles { file -> file.isDirectory }
            ?.toList() ?: listOf()

    val globalInstalledAppDirs: List<File>
        get() = globalRootDir.resolve("apps")
            .listFiles { file -> file.isDirectory }
            ?.toList() ?: listOf()

    val cacheDir: File
        get() = rootDir.resolve("cache")

    /** Persist directory of [name] under the given scoop [root]. */
    fun persistDir(root: File, name: String): File = root.resolve("persist/$name")

    /**
     * Read installed app dirs once and return name-lowercase to info map.
     * Call this before batch manifest parsing to avoid repeated filesystem scans.
     */
    override fun installedSnapshot(): Map<String, InstalledAppInfo> {
        val result = mutableMapOf<String, InstalledAppInfo>()
        for ((dirs, isGlobal) in listOf(localInstalledAppDirs to false, globalInstalledAppDirs to true)) {
            for (appDir in dirs) {
                val current = appDir.resolve("current")
                if (!current.exists()) continue
                val version = try {
                    current.toPath().toRealPath().fileName.toString()
                } catch (_: java.nio.file.NoSuchFileException) {
                    continue // broken symlink
                }
                result[appDir.name.lowercase()] = InstalledAppInfo(appDir.name, version, isGlobal, appDir)
            }
        }
        return result
    }

    // ==================== Filesystem Queries ====================

    fun getBucketRepo(bucketDir: File): String? {
        if (findExecutable("git.exe") == null) return null
        val result = execute("git", "remote", "-v", asShell = false, workingDir = bucketDir)
        val output = result.output.joinToString("\n")
        val regex = """origin\s+(.*)\s+\(fetch\)""".toRegex(RegexOption.MULTILINE)
        return regex.find(output)?.groupValues?.get(1)
    }

    fun getRepoUrl(bucketDir: File): String? {
        val configFile = bucketDir.resolve(".git/config")
        if (!configFile.exists()) return null
        val repoInfo = configFile.readText()
        val regex = """\[remote\s+"origin"]\s*\n(\s*\n*)+url\s*=\s*(.+)""".toRegex()
        return regex.find(repoInfo)?.groupValues?.get(2)
    }

    /** Build the full app list from all bucket manifests, then deduplicate. */
    fun buildAllApps(installed: Map<String, InstalledAppInfo>): List<App> {
        // Only cross-bucket duplicate names need the install record, so the
        // per-app file read stays proportional to the rare cases.
        val originBuckets = resolveOriginBuckets(installed)
        val allApps = bucketDirs.flatMap { bucketDir ->
            val bucket = Bucket(name = bucketDir.name, url = "")
            bucketDir.resolve("bucket").listFiles()
                ?.filter { it.isFile && it.extension == "json" && !it.name.startsWith(".") }
                ?.mapNotNull { file -> buildAppFromManifest(file, bucket, installed, originBuckets, assumeOrigin = false) }
                ?: emptyList()
        }
        val deduped = deduplicateApps(allApps, originBuckets)
        val knownNames = deduped.map { it.name.lowercase() }.toSet()
        return deduped + findOrphanInstalledApps(knownNames, installed)
    }

    /** Origin bucket per installed app whose manifest name exists in more than one
     *  bucket, from the install record; unique names never get a record read. A name
     *  with no usable record maps to no entry, keeping dedup alphabetical-first. */
    private fun resolveOriginBuckets(installed: Map<String, InstalledAppInfo>): Map<String, String> {
        val duplicates = duplicateManifestNames()
        if (duplicates.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in duplicates) {
            val info = installed[key] ?: continue
            val record = readInstallRecord(info.appDir.resolve("current").toPath())
            if (record.origin == InstallOrigin.Bucket && !record.bucket.isNullOrBlank()) {
                result[key] = record.bucket
            }
        }
        return result
    }

    /** Lowercase manifest names existing in more than one bucket; sole owner of this
     *  fact so origin resolution and the install-state guard cannot drift. */
    fun duplicateManifestNames(installedLower: Set<String>? = null): Set<String> {
        val names = bucketDirs.flatMap { bucketDirManifestNames(it) }
            .map { it.removeSuffix(".json").lowercase() }
        return names.groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .filter { installedLower == null || it in installedLower }
            .toSet()
    }

    /** Shortcut: snapshot first, then build all. */
    fun loadAllApps(): List<App> = buildAllApps(installedSnapshot())

    /** Find installed apps whose manifest is not in any bucket.
     *  Reads `current/manifest.json` from each installed app directory. */
    private fun findOrphanInstalledApps(
        knownNames: Set<String>,
        installed: Map<String, InstalledAppInfo>,
    ): List<App> = buildList {
        for ((name, info) in installed) {
            if (name in knownNames) continue
            // current exists is guaranteed by installedSnapshot()
            val manifest = info.appDir.resolve("current/manifest.json")
            if (!manifest.exists()) continue
            val json = tryParseManifest(manifest) ?: continue
            add(buildAppFromJson(json, info.name, info.version, info.isGlobal, null))
        }
    }

    /** Build an App from a manifest JSON, with name/version overrides and no file-attribute dates. */
    private fun buildAppFromJson(
        json: JsonObject,
        name: String,
        version: String,
        global: Boolean,
        bucket: Bucket?,
    ): App = App(
        name = name,
        latestVersion = json.getString("version").ifEmpty { version },
        version = version,
        global = global,
        status = AppStatus.INSTALLED,
        description = json.getString("description"),
        homepage = json.getString("homepage"),
        url = resolveManifestUrl(json),
        license = json.getString("license"),
        bucket = bucket,
        shortcuts = parseShortcuts(json),
    )

    /**
     * Build the orphan Discover record for a freshly direct-installed app from
     * its on-disk manifest at apps/<name>/current/manifest.json, so the record
     * matches what a full sync would produce. The version directory name
     * drives [App.version]; null when the install layout is not on disk (then
     * callers fall back to a name-and-version-only record).
     */
    fun installedAppRecord(name: String): App? {
        val current = rootDir.resolve("apps/${name.lowercase()}/current")
        // Recent Scoop writes scoop-manifest.json; older versions manifest.json.
        val manifestFile = listOf("manifest.json", "scoop-manifest.json")
            .map { current.resolve(it) }
            .firstOrNull { it.exists() } ?: return null
        val json = tryParseManifest(manifestFile) ?: return null
        // Scoop installs <root>/apps/<name>/<version> with current as a junction,
        // so the real path names the version; plain directories fall back to the
        // manifest version field.
        val version = try {
            current.toPath().toRealPath().fileName.toString()
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }?.takeUnless { it.equals("current", ignoreCase = true) }
            ?: json.getString("version").takeUnless(String::isBlank)
            ?: "0"
        return buildAppFromJson(json, name, version, false, null)
    }

    /** Deduplicate apps by name. The install-record origin bucket wins, then
     *  installed rows, otherwise the first bucket in order (Scoop's behavior). */
    private fun deduplicateApps(
        apps: List<App>,
        originBuckets: Map<String, String>,
    ): List<App> {
        fun rank(app: App): Int = when {
            originBuckets[app.name.lowercase()] == app.bucket?.name -> 0
            app.installed -> 1
            else -> 2
        }
        val result = LinkedHashMap<String, App>()
        for (app in apps) {
            result.merge(app.name, app) { incumbent, candidate ->
                if (rank(candidate) < rank(incumbent)) candidate else incumbent
            }
        }
        return result.values.toList()
    }

    override fun buildAppsFromManifestNames(
        bucketDir: File,
        manifestNames: Set<String>,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
    ): List<App> = buildAppsFromManifestNames(bucketDir, manifestNames, bucket, installed, assumeOrigin = false)

    /** Build apps only from [manifestNames]; [assumeOrigin] (save / post-install path)
     *  skips install-record strictness, otherwise the origin still decides the row. */
    override fun buildAppsFromManifestNames(
        bucketDir: File,
        manifestNames: Set<String>,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
        assumeOrigin: Boolean,
    ): List<App> {
        val originBuckets = if (assumeOrigin) emptyMap() else resolveOriginBuckets(installed)
        return manifestNames.mapNotNull { fileName ->
            val file = bucketDir.resolve("bucket/$fileName")
            if (!file.exists()) return@mapNotNull null
            buildAppFromManifest(file, bucket, installed, originBuckets, assumeOrigin)
        }
    }

    private fun tryParseManifest(file: File): JsonObject? {
        val element = try {
            manifestJson.parseToJsonElement(readManifestText(file))
        } catch (e: Exception) {
            // Broken manifests exist in the wild (single-quoted values, etc.) -
            // they break scoop itself too, so just skip with a low-noise log
            logger.warn("parsing manifest: ${file.absolutePath}, error: ${e.message}")
            return null
        }
        // Some files in bucket/ are plain JSON but not manifests
        // (e.g. a VS Code snippets template committed by mistake)
        return element as? JsonObject
    }

    private fun parseShortcuts(json: JsonObject): List<ShortCut> {
        val array = json["shortcuts"] as? JsonArray ?: return emptyList()
        // Some manifests use a single non-nested pair ["name","target"] - wrap it
        val normalized = if (array.firstOrNull() is JsonArray) array else buildJsonArray { add(array) }
        return normalized.mapNotNull { ele ->
            val pair = ele as? JsonArray ?: return@mapNotNull null
            val name = pair.getOrNull(0) as? JsonPrimitive ?: return@mapNotNull null
            val target = pair.getOrNull(1) as? JsonPrimitive ?: return@mapNotNull null
            ShortCut(name.content, target.content)
        }
    }

    /** Build one App from a manifest file. When the app is installed in another bucket
     *  (origin differs), the row stays UNINSTALL so Updates never mixes versions. */
    private fun buildAppFromManifest(
        file: File,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
        originBuckets: Map<String, String>,
        assumeOrigin: Boolean,
    ): App? {
        val json = tryParseManifest(file) ?: return null

        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val createAt = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault())
        val updateAt = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault())

        val name = file.nameWithoutExtension
        val info = installed[name.lowercase()]
        val originBucket = originBuckets[name.lowercase()]

        if (info != null && (assumeOrigin || originBucket == null || originBucket == bucket.name)) {
            return buildAppFromJson(json, name, info.version, info.isGlobal, bucket)
                .copy(createAt = createAt, updateAt = updateAt)
        }
        val uninstalled = buildAppFromJson(json, name, json.getString("version"), false, bucket)
            .copy(createAt = createAt, updateAt = updateAt, status = AppStatus.UNINSTALL)
        return if (info != null) {
            // The other bucket's row must not carry the installed version, or
            // Updates compares versions across buckets.
            uninstalled.copy(version = uninstalled.latestVersion)
        } else {
            uninstalled
        }
    }

    fun computeCacheSize(): Long {
        return cacheDir.dirSize()
    }

    /** Collect manifest file names (e.g. "7zip.json") for a given bucket directory. */
    fun bucketDirManifestNames(bucketDir: File): Set<String> {
        return bucketDir.resolve("bucket").listFiles()
            ?.filter { !it.isDirectory && it.extension == "json" && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    // ==================== CLI Commands ====================

    override suspend fun refresh(): CommandResult {
        return executeAndLog(mutableListOf("scoop", "update"))
    }

    override suspend fun install(app: App, global: Boolean): CommandResult {
        preDownloadIfNeeded(app)
        val commandArgs = if (global) mutableListOf(
            "sudo", "scoop", "install", "-g", "${app.bucket!!.name}/${app.name}"
        ) else {
            mutableListOf("scoop", "install", "${app.bucket!!.name}/${app.name}")
        }
        return executeAndLog(commandArgs)
    }

    override suspend fun uninstall(app: App, global: Boolean, purge: Boolean): CommandResult {
        return executeAndLog(uninstallArgs(app, global, purge))
    }

    /** Build the uninstall command args; internal so tests can assert assembly. */
    internal fun uninstallArgs(app: App, global: Boolean, purge: Boolean): List<String> {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "uninstall", "-g")
        } else {
            mutableListOf("scoop", "uninstall")
        }
        if (purge) commandArgs.add("-p")
        commandArgs.add(app.name)
        return commandArgs
    }

    override suspend fun update(app: App, global: Boolean, force: Boolean): CommandResult {
        preDownloadIfNeeded(app)
        val bucketName = app.bucket?.name

        // Fix broken manifest reference left by legacy installVersion (temp file).
        // Detect via the install record: a recorded url points at a manifest that is
        // gone, so reinstall from the bucket.
        if (bucketName != null &&
            isLocalManifestInstall(app.name, global, rootDir.toPath(), globalRootDir.toPath())
        ) {
            val uninstallArgs = if (global) {
                mutableListOf("sudo", "scoop", "uninstall", "-g", app.name)
            } else {
                mutableListOf("scoop", "uninstall", app.name)
            }
            val uninstallResult = executeAndLog(uninstallArgs)
            if (uninstallResult.exitCode != 0) return uninstallResult
            return executeAndLog(localManifestRepairArgs(bucketName, app.name, global))
        }

        return executeAndLog(updateArgs(app, global, force))
    }

    /**
     * Install args for the local-manifest repair branch of update: reinstall
     * from bucket without --force (scoop install does not accept it, getopt
     * only allows giksua; reinstall semantics come from the preceding uninstall).
     */
    internal fun localManifestRepairArgs(bucketName: String, name: String, global: Boolean): List<String> {
        return if (global) {
            mutableListOf("sudo", "scoop", "install", "-g", "$bucketName/$name")
        } else {
            mutableListOf("scoop", "install", "$bucketName/$name")
        }
    }

    /** Build the update command args; internal so tests can assert assembly. */
    internal fun updateArgs(app: App, global: Boolean, force: Boolean): List<String> {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "update", "-g")
        } else {
            mutableListOf("scoop", "update")
        }
        if (force) commandArgs.add("--force")
        // Explicit <bucket>/<name>: scoop resolves a bare name through the
        // bucket recorded in install.json, so an update after saving to another
        // bucket would silently re-read the stale manifest (exit 0, no error).
        val bucketName = app.bucket?.name
        commandArgs.add(if (bucketName.isNullOrBlank()) app.name else "$bucketName/${app.name}")
        return commandArgs
    }

    override suspend fun download(app: App): CommandResult {
        val config = ScoopConfigManager.readScoopConfig()
        return if (config.aria2Enabled) {
            // aria2 mode: use scoop download command, parse progress from output
            executeAndLog(mutableListOf("scoop", "download", app.uniqueName))
        } else {
            // Non-aria2 mode: use JVM HttpClient for precise progress
            downloadWithJvm(app)
        }
    }

    override suspend fun addBucket(bucket: String, url: String?): CommandResult {
        val commandArgs = mutableListOf("scoop", "bucket", "add", bucket)
        if (url != null) commandArgs.add(url)
        return executeAndLog(commandArgs)
    }

    override suspend fun removeBucket(bucket: String): CommandResult {
        return executeAndLog(mutableListOf("scoop", "bucket", "rm", bucket))
    }

    override suspend fun cleanup(vararg apps: String, global: Boolean): CommandResult {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "cleanup", "-g", *apps)
        } else {
            mutableListOf("scoop", "cleanup", *apps)
        }
        return executeAndLog(commandArgs)
    }

    override suspend fun removeCache(vararg apps: String): CommandResult {
        val targets = if (apps.isEmpty()) arrayOf("-a") else apps
        val commandArgs = mutableListOf("scoop", "cache", "rm", *targets)
        logger.info("remove cache, commandArgs = $commandArgs")
        return executeAndLog(commandArgs)
    }

    /** Single choke point for "stop everything": flag the running task cancelled, then kill its process tree. */
    override fun stop() {
        logger.warn("stopping all processes...")
        taskQueue.cancelRunningTask()
        killAllSubProcesses()
        logger.warn("all processes stopped")
    }

    /** True when an app dir named [name] exists in the local (or global) apps dir. */
    internal fun isInstalledInScope(name: String, global: Boolean): Boolean {
        val installedDirs = if (global) globalInstalledAppDirs else localInstalledAppDirs
        return installedDirs.any { it.name.equals(name, ignoreCase = true) }
    }

    /** Uninstall command args for [name] in the given scope; no -p so persist data survives. */
    internal fun uninstallArgsForScope(name: String, global: Boolean): List<String> {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "uninstall", "-g")
        } else {
            mutableListOf("scoop", "uninstall")
        }
        commandArgs.add(name)
        return commandArgs
    }

    override suspend fun installVersion(app: App, manifestFile: File, global: Boolean): CommandResult {
        if (isInstalledInScope(app.name, global)) {
            logStream.emit("Uninstalling current ${app.name} before installing version from manifest...")
            val uninstallResult = executeAndLog(uninstallArgsForScope(app.name, global))
            if (uninstallResult.exitCode != 0) return uninstallResult
        }
        return installLocalManifest(app, manifestFile, global)
    }

    /**
     * Install a local manifest as [app.name]. Prefer the bucket takeover: temporarily
     * rewrite the same-name bucket manifest so scoop registers a bucket install, then
     * restore the original after install. This avoids scoop recording a file path that
     * later update can't find. Fall back to installing from the file path directly.
     */
    private suspend fun installLocalManifest(
        app: App,
        manifestFile: File,
        global: Boolean,
    ): CommandResult {
        val bucketName = app.bucket?.name
        val bucketManifest = if (bucketName != null) {
            bucketsBaseDir.resolve(bucketName).resolve("bucket/${app.name}.json").takeIf { it.exists() }
        } else null

        if (bucketName != null && bucketManifest != null) {
            var originalContent: String? = null
            try {
                originalContent = bucketManifest.readText()
                bucketManifest.writeText(manifestFile.readText())
                return executeAndLog(localManifestRepairArgs(bucketName, app.name, global))
            } finally {
                if (originalContent != null) {
                    bucketManifest.writeText(originalContent)
                }
            }
        }

        return executeAndLog(installFromManifestArgs(manifestFile.absolutePath, global))
    }

    /**
     * Direct-install the current manifest from a local file. When a same-name app is
     * already installed in the [reinstallGlobal] scope, uninstall it first (persist
     * preserved), then prewarm the scoop cache and install the manifest file.
     */
    override suspend fun installFromManifest(
        app: App,
        manifestFile: File,
        global: Boolean,
        reinstallGlobal: Boolean,
    ): CommandResult {
        if (isInstalledInScope(app.name, reinstallGlobal)) {
            logStream.emit("Uninstalling current ${app.name} before installing from manifest...")
            val uninstallResult = executeAndLog(uninstallArgsForScope(app.name, reinstallGlobal))
            if (uninstallResult.exitCode != 0) return uninstallResult
        }

        val prewarmed = prewarmTestInstallCache(app.name, manifestFile)
        logger.info("Prewarmed $prewarmed cache file(s) for direct install of ${app.name}")

        return installLocalManifest(app, manifestFile, global)
    }

    /** Build the direct-install command args; internal so tests can assert assembly. */
    internal fun installFromManifestArgs(manifestPath: String, global: Boolean): List<String> {
        return if (global) {
            mutableListOf("sudo", "scoop", "install", "-g", manifestPath)
        } else {
            mutableListOf("scoop", "install", manifestPath)
        }
    }

    /** Recursive delete that reports failure instead of throwing. */
    internal fun deleteRecursivelyQuietly(target: File): Boolean = try {
        target.deleteRecursively()
    } catch (e: Exception) {
        logger.warn("Failed to delete ${target.absolutePath}: ${e.message}")
        false
    }

    /**
     * Pre-warm the Scoop cache for a test install by linking/copying already-cached
     * downloads (scoop-ai download-phase files or finalized entries) to the
     * `{testAppName}#{version}#{hash}{ext}` names scoop expects for a local manifest.
     *
     * @return number of cache entries successfully pre-warmed
     */
    fun prewarmTestInstallCache(
        testAppName: String,
        manifestFile: File,
        cacheDir: File = this.cacheDir,
    ): Int {
        val json = try {
            manifestJson.parseToJsonElement(readManifestText(manifestFile)).jsonObject
        } catch (e: Exception) {
            logger.warn("Failed to parse manifest for cache prewarm: ${manifestFile.absolutePath}, error: ${e.message}")
            return 0
        }

        val version = json.getString("version").takeIf { it.isNotBlank() } ?: run {
            logger.warn("Manifest has no version, skip cache prewarm: ${manifestFile.absolutePath}")
            return 0
        }

        val urls = collectManifestUrls(json)
        if (urls.isEmpty()) {
            logger.warn("Manifest has no download URL, skip cache prewarm: ${manifestFile.absolutePath}")
            return 0
        }

        val app = App(name = testAppName, latestVersion = version)
        var warmed = 0
        for (url in urls) {
            if (prewarmCacheEntry(app, version, url, cacheDir)) warmed++
        }
        return warmed
    }

    // ==================== Internal Methods ====================

    /** Prewarm the formal cache entry so scoop's install/update skips the network
     *  download; scoop's cache lookup is downloader-agnostic, so this runs with
     *  aria2 enabled too (aria2 hits the same {app}#ver#hash entries). */
    internal suspend fun preDownloadIfNeeded(app: App) {
        val manifestFile = findManifest(app) ?: run {
            logger.warn("Manifest not found for ${app.uniqueName}, skip pre-download")
            return
        }

        val json = try {
            manifestJson.parseToJsonElement(readManifestText(manifestFile)).jsonObject
        } catch (e: Exception) {
            logger.warn("Failed to parse manifest: ${e.message}, skip pre-download")
            return
        }

        val info = downloader.parseDownloadInfo(json)
        if (info == null) {
            logger.warn("No download URL found in manifest for ${app.uniqueName}, skip pre-download")
            return
        }

        val version = json.getString("version")
        val ok = downloadManifestItemsToCache(app, version, info, logPrefix = "Pre-download")
        if (!ok) {
            logStream.emit("Pre-download failed for ${app.name}, will fallback to scoop")
        }
    }

    private suspend fun downloadWithJvm(app: App): CommandResult {
        val manifestFile = findManifest(app)
        if (manifestFile == null) {
            logStream.emit("Manifest not found for ${app.uniqueName}")
            return CommandResult(1)
        }

        val json = try {
            manifestJson.parseToJsonElement(readManifestText(manifestFile)).jsonObject
        } catch (e: Exception) {
            logStream.emit("Failed to parse manifest: ${e.message}")
            return CommandResult(1)
        }

        val info = downloader.parseDownloadInfo(json)
        if (info == null) {
            logStream.emit("No download URL found in manifest for ${app.uniqueName}")
            return CommandResult(1)
        }

        val version = json.getString("version")
        val ok = downloadManifestItemsToCache(app, version, info, logPrefix = "Download")
        return CommandResult(if (ok) 0 else 1)
    }

    private suspend fun downloadManifestItemsToCache(
        app: App,
        version: String,
        info: DownloadInfo,
        logPrefix: String,
    ): Boolean {
        if (info.items.isEmpty()) return false

        val totalItems = info.items.size
        var prewarmed = 0

        for ((index, item) in info.items.withIndex()) {
            if (taskQueue.isRunningTaskCancelled()) {
                throw OperationCancelledException("Pre-download cancelled for ${app.name}")
            }
            val cacheName = cacheFileName(app, version, item.url)
            val destFile = cacheDir.resolve(cacheName)
            logStream.emit("$logPrefix url[${index + 1}/$totalItems]: ${item.url}")
            logStream.emit("$logPrefix cache target[${index + 1}/$totalItems]: ${destFile.absolutePath}")

            if (destFile.exists()) {
                logStream.emit("File already cached: ${destFile.name}")
                val overall = (((index + 1).toFloat() / totalItems) * 100).toInt().coerceAtMost(100)
                taskQueue.updateProgress(overall)
                continue
            }

            // Prewarm from an existing cache entry (engine download-phase file or
            // another app's finalized entry for the same URL) so the formal install
            // can skip the network download entirely. Best-effort: on any failure
            // fall through to the normal network download below.
            if (prewarmCacheEntry(app, version, item.url, cacheDir)) {
                prewarmed++
                logStream.emit("Prewarmed from existing cache, skip download: ${destFile.name}")
                val overall = (((index + 1).toFloat() / totalItems) * 100).toInt().coerceAtMost(100)
                taskQueue.updateProgress(overall)
                continue
            }

            logStream.emit("Downloading ${app.name}... (${index + 1}/$totalItems)")
            val result = downloader.download(
                url = item.url,
                destFile = destFile,
                hash = item.hash,
                onProgress = { percent ->
                    val overall = (((index + percent / 100f) / totalItems) * 100).toInt().coerceIn(0, 100)
                    taskQueue.updateProgress(overall)
                },
                shouldCancel = { taskQueue.isRunningTaskCancelled() },
            )

            if (result != null) {
                logStream.emit("Downloaded to cache: ${result.absolutePath}")
                logStream.emit("Cache exists after download: ${result.exists()}")
            } else {
                logStream.emit("Failed downloading item[${index + 1}/$totalItems] for ${app.name}")
                return false
            }
        }

        if (prewarmed > 0) {
            logStream.emit("$logPrefix prewarmed $prewarmed/$totalItems cache file(s) for ${app.name}")
        }

        taskQueue.updateProgress(100)
        return true
    }

    /** Find the manifest file for the given app. */
    private fun findManifest(app: App): File? {
        val bucketDir = app.bucket?.name?.let { bucketsBaseDir.resolve(it) }
        if (bucketDir != null && bucketDir.exists()) {
            val manifestFile = bucketDir.resolve("bucket/${app.name}.json")
            if (manifestFile.exists()) return manifestFile
        }
        // Fallback: search all buckets
        for (dir in bucketDirs) {
            val manifestFile = dir.resolve("bucket/${app.name}.json")
            if (manifestFile.exists()) return manifestFile
        }
        return null
    }

    /**
     * Aligns with scoop's cache_path($app, $version, $url).
     */
    private fun cacheFileName(app: App, version: String, url: String): String {
        val urlHash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(7)
        return "${app.name}#$version#$urlHash${scoopCacheExtension(url)}"
    }

    /** Test hook: cache file name for a name/version/url triple. */
    internal fun cacheFileNameForTest(name: String, version: String, url: String): String {
        val urlHash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(7)
        return "$name#$version#$urlHash${scoopCacheExtension(url)}"
    }

    /**
     * Aligns with PowerShell: [System.IO.Path]::GetExtension($url)
     * Does not strip query/fragment, to stay consistent with scoop.
     */
    private fun scoopCacheExtension(url: String): String {
        val ext = File(url.replace('\\', '/')).extension
        return if (ext.isNotEmpty()) ".$ext" else ""
    }

    /** Collect every download URL from a manifest: top-level `url` plus each architecture block. */
    private fun collectManifestUrls(json: JsonObject): List<String> {
        val urls = linkedSetOf<String>()

        fun add(element: JsonElement?) {
            when (element) {
                is JsonArray -> element.forEach { add(it) }
                is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let(urls::add)
                else -> Unit
            }
        }

        add(json["url"])

        val arch = json["architecture"] as? JsonObject
        if (arch != null) {
            for (key in listOf("64bit", "32bit", "arm64")) {
                (arch[key] as? JsonObject)?.let { add(it["url"]) }
            }
        }

        return urls.toList()
    }

    /** Pre-warm one cache entry; returns true when the target now exists. */
    private fun prewarmCacheEntry(app: App, version: String, url: String, cacheDir: File): Boolean {
        val target = File(cacheDir, cacheFileName(app, version, url))
        if (target.exists()) {
            logger.info("Cache already warm for ${app.name}: ${target.name}")
            return false
        }

        val source = findCacheSource(url, cacheDir)
        if (source == null) {
            logger.info("No cached source for $url, skip prewarm")
            return false
        }

        return try {
            try {
                Files.createLink(target.toPath(), source.toPath())
            } catch (e: Exception) {
                Files.copy(source.toPath(), target.toPath())
            }
            logger.info("Prewarmed cache: ${source.name} -> ${target.name}")
            true
        } catch (e: Exception) {
            logger.warn("Failed to prewarm cache for $url: ${e.message}")
            false
        }
    }

    /** Find an existing cache file for [url]: download-phase name first, then a finalized scan. */
    private fun findCacheSource(url: String, cacheDir: File): File? {
        // a. scoop-ai download-phase file (normalized-url hash)
        val downloadName = ScoopCacheManager.downloadCacheName(url)
        val downloadFile = File(cacheDir, downloadName)
        if (downloadFile.isFile && downloadFile.length() > 0) return downloadFile

        // b. already-finalized entry matching `*#{urlHash}{ext}`
        if (!cacheDir.isDirectory) return null
        val suffix = "#${ScoopCacheManager.urlHash(url)}${ScoopCacheManager.urlExtension(url)}"
        return cacheDir.listFiles { f ->
            // Exclude aria2 control files: an interrupted download leaves `{name}.aria2` which
            // also matches the suffix via contains(), and linking it would produce a broken cache.
            f.isFile && f.name.contains(suffix) && !f.name.endsWith(".aria2")
        }?.firstOrNull()
    }

    /** Resolve the first download URL from manifest, checking architecture sub-objects.
     *  Also falls back to checkver.github for GitHub repo detection.
     */
    private fun resolveManifestUrl(json: JsonObject): String? {
        // Top-level url
        val topLevel = json.getString("url")
        if (topLevel.isNotBlank()) return topLevel

        // Architecture sub-object
        val arch = detectArch()
        val archObj = json["architecture"] as? JsonObject
        if (archObj != null) {
            val archBlock = archObj[arch] as? JsonObject ?: archObj["64bit"] as? JsonObject
            if (archBlock != null) {
                val urlElement = archBlock["url"]
                val url = when (urlElement) {
                    is JsonArray -> (urlElement.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    else -> (urlElement as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                }
                if (!url.isNullOrBlank()) return url
            }
        }

        // Fallback: checkver.github (e.g. "https://github.com/owner/repo")
        val checkverGithub = (json["checkver"] as? JsonObject)?.let { cv ->
            cv["github"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }
        if (!checkverGithub.isNullOrBlank()) return checkverGithub

        return null
    }

    private fun detectArch(): String {
        val arch = System.getProperty("os.arch", "").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("64") -> "64bit"
            else -> "32bit"
        }
    }

    /** Get the manifest file for an app. */
    fun getManifestFile(app: App): File? = findManifest(app)

    /** Read the manifest JSON content for an app. */
    fun getManifestContent(app: App): String? = findManifest(app)?.readText()

    /** Open a shortcut of an installed app (Discover). Delegates to the unified launcher;
     *  ShortCut.title carries the manifest target path, used as the preferred shortcut. */
    fun openShortcut(app: App, shortcutIndex: Int = 0) {
        val shortcut = app.shortcuts?.getOrNull(shortcutIndex) ?: return
        launchInstalledApp(app.name, preferredShortcut = shortcut.title, global = app.global)
    }

    /** Start Menu shortcut file of an installed app under the given per-user app data dir. */
    internal fun startMenuShortcutFile(name: String, appDataDir: File): File =
        appDataDir.resolve("Microsoft/Windows/Start Menu/Programs/Scoop Apps/$name.lnk")

    /**
     * Resolve the Start Menu .lnk of an installed app. Scoop names shortcuts after the
     * manifest display name ("Sanad Agent.lnk"), not the app name, so try the manifest
     * names first, then the app name, then a normalized scan of the folder.
     */
    internal fun resolveStartMenuShortcut(
        name: String,
        shortcutNames: List<String>,
        appDataDir: File,
    ): File? {
        (shortcutNames + name).distinct()
            .map { startMenuShortcutFile(it, appDataDir) }
            .firstOrNull { it.isFile }
            ?.let { return it }

        val folder = startMenuShortcutFile(name, appDataDir).parentFile ?: return null
        val normalized = normalizeShortcutKey(name)
        return folder.listFiles { file -> file.isFile && file.name.endsWith(".lnk", ignoreCase = true) }
            ?.firstOrNull { normalizeShortcutKey(it.nameWithoutExtension) == normalized }
    }

    /** Shortcut display names declared by a manifest, top level or per architecture. */
    internal fun shortcutNamesFromManifest(manifestJson: String?): List<String> =
        manifestShortcutPairs(manifestJson).map { it.second }.distinct()

    /** Manifest shortcut pairs (target path, display name), top level or per architecture. */
    internal fun manifestShortcutPairs(manifestJson: String?): List<Pair<String, String>> {
        if (manifestJson.isNullOrBlank()) return emptyList()
        val root = runCatching {
            ScoopClient.manifestJson.parseToJsonElement(manifestJson) as? JsonObject
        }.getOrNull() ?: return emptyList()

        fun pairsOf(container: JsonElement?): List<Pair<String, String>> {
            val entries = (container as? JsonObject)?.get("shortcuts") as? JsonArray ?: return emptyList()
            return entries.mapNotNull { entry ->
                val pair = entry as? JsonArray ?: return@mapNotNull null
                val target = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val display = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                target to display
            }
        }

        val archPairs = (root["architecture"] as? JsonObject)?.values.orEmpty().flatMap { pairsOf(it) }
        return pairsOf(root) + archPairs
    }

    /** Manifest file names Scoop installs into apps/<name>/current/. */
    private val installedManifestFiles = listOf("manifest.json", "scoop-manifest.json")

    /**
     * Launch an installed app (Create flow). Thin delegate to the unified launcher.
     */
    fun openApp(name: String): CommandResult = launchInstalledApp(name)

    /**
     * Single launch mechanism for installed apps. Target priority: the preferred
     * shortcut, then the first manifest shortcut, then the first .exe bin, then
     * the Start Menu .lnk. See [resolveLaunchTarget].
     */
    internal fun launchInstalledApp(name: String, preferredShortcut: String? = null, global: Boolean = false): CommandResult {
        val root = if (global) globalRootDir else (installedAppRoot(name) ?: rootDir)
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
        val target = resolveLaunchTarget(
            name = name,
            installDir = root.resolve("apps/$name/current"),
            manifestJson = installedAppManifest(name),
            preferredShortcut = preferredShortcut,
            appDataDir = appData,
        )
        logger.info("Opening app {}: {}", name, target)
        return when (target) {
            is LaunchTarget.StartCommand -> {
                ProcessBuilder(target.args).directory(target.workDir).start()
                CommandResult(0)
            }
            is LaunchTarget.Lnk -> {
                ProcessBuilder("cmd", "/c", "start", "", target.file.absolutePath).start()
                CommandResult(0)
            }
            null -> CommandResult(1, "No launchable target found for app: $name")
        }
    }

    /**
     * Resolve what to launch for an installed app, null when nothing qualifies.
     * Priority: the preferred shortcut (matched by target path or display name
     * against any manifest shortcut pair), then the first manifest shortcut,
     * then the first .exe bin, then the Start Menu .lnk.
     */
    internal fun resolveLaunchTarget(
        name: String,
        installDir: File,
        manifestJson: String?,
        preferredShortcut: String?,
        appDataDir: File?,
    ): LaunchTarget? {
        val shortcutPairs = manifestShortcutPairs(manifestJson)
        val preferred = preferredShortcut?.let { pref ->
            shortcutPairs.firstOrNull { (target, display) ->
                sameShortcutPath(target, pref) || display.equals(pref, ignoreCase = true)
            }
        }
        return when {
            preferred != null -> startCommand(installDir, preferred.first)
            shortcutPairs.isNotEmpty() -> startCommand(installDir, shortcutPairs.first().first)
            else -> openAppCommand(installDir, manifestBinExePaths(manifestJson))
        } ?: appDataDir?.let { dir ->
            resolveStartMenuShortcut(name, shortcutPairs.map { it.second }, dir)?.let(LaunchTarget::Lnk)
        }
    }

    /** Compare manifest target paths ignoring separator direction and case (Windows paths). */
    private fun sameShortcutPath(a: String, b: String): Boolean =
        a.replace('\\', '/').equals(b.replace('\\', '/'), ignoreCase = true)

    /** Root dir that actually contains apps/<name>/, local first then global. */
    private fun installedAppRoot(name: String): File? =
        listOf(rootDir, globalRootDir).firstOrNull { it.resolve("apps/$name/current").exists() }

    /** exe bin paths from a manifest: top level string/list and per architecture. */
    internal fun manifestBinExePaths(manifestJson: String?): List<String> {
        if (manifestJson.isNullOrBlank()) return emptyList()
        val root = runCatching {
            ScoopClient.manifestJson.parseToJsonElement(manifestJson) as? JsonObject
        }.getOrNull() ?: return emptyList()

        fun exePathsOf(container: JsonElement?): List<String> =
            (container as? JsonObject)?.get("bin")
                ?.let { bin ->
                    when (bin) {
                        is JsonPrimitive -> listOfNotNull(bin.contentOrNull)
                        is JsonArray -> bin.mapNotNull { entry ->
                            when (entry) {
                                is JsonPrimitive -> entry.contentOrNull
                                is JsonArray -> (entry.firstOrNull() as? JsonPrimitive)?.contentOrNull
                                else -> null
                            }
                        }
                        else -> null
                    }
                }
                ?.filter { it.isNotBlank() }
                .orEmpty()

        val archPaths = (root["architecture"] as? JsonObject)?.values.orEmpty().flatMap { exePathsOf(it) }
        return (exePathsOf(root) + archPaths).filter { it.endsWith(".exe", ignoreCase = true) }.distinct()
    }

    /** Start command for a manifest target path, run with the install dir as working dir. */
    internal fun startCommand(installDir: File, target: String): LaunchTarget.StartCommand =
        LaunchTarget.StartCommand(listOf("cmd", "/c", "start", "", target), installDir)

    /** Start command for the first exe bin, null when none qualifies. */
    internal fun openAppCommand(installDir: File, binPaths: List<String>): LaunchTarget.StartCommand? =
        binPaths.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
            ?.let { startCommand(installDir, it) }

    /** Manifest text Scoop stores in the installed app directory, null when absent. */
    private fun installedAppManifest(name: String): String? =
        listOf(rootDir, globalRootDir)
            .map { root -> installedManifestFiles.map { root.resolve("apps/$name/current/$it") } }
            .flatten()
            .firstOrNull { it.isFile }
            ?.readText()

    private fun normalizeShortcutKey(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun executeAndLog(args: List<String>): CommandResult {
        val result = executeAndLogCapture(args)
        return CommandResult(result.exitCode, result.errorMessage, result.skippedMessage)
    }

    private suspend fun executeAndLogCapture(args: List<String>): TestInstallResult {
        val outputLines = mutableListOf<String>()
        val result = executeSuspend(
            args,
            env = scoopTestEnv(),
            consumer = { line ->
                outputLines.add(line)
                logStream.emit(line)
                logger.info(line)
                ProgressParser.parseProgress(line)?.let { taskQueue.updateProgress(it) }
            },
            onFinish = {},
        )
        return evaluateInstallOutput(result.resultCode, outputLines)
    }

    /** Classify install output: Scoop exits 0 even when shortcut/icon creation fails
     *  ("failed: Couldn't find ..."), so those lines must force exitCode 1. When scoop
     *  itself exits nonzero but no line matches, emit a fallback conclusion line so
     *  upstream UI/agent still get an explicit failure reason instead of a bare log tail.
     *  The fallback must not overwrite the real exit code, otherwise the caller
     *  would lose the actual scoop failure code for diagnostics/retry decisions.
     *  Scoop also exits 0 after doing nothing at all (held app, detected running
     *  process); those cases stay successful but carry a [TestInstallResult.skippedMessage]
     *  so callers can tell "installed" from "silently skipped". An error line always
     *  wins over a skip hint. */
    internal fun evaluateInstallOutput(resultCode: Int, outputLines: List<String>): TestInstallResult {
        var errorMessage = outputLines.find {
            " ERROR " in it ||
                it.trimStart().startsWith("ERROR ") ||
                it.contains("failed: Couldn't find")
        }
        if (resultCode != 0 && errorMessage == null) {
            errorMessage = "scoop exited with code $resultCode (no error line matched)"
        }
        val exitCode = if (errorMessage != null && resultCode == 0) 1 else resultCode
        val skippedMessage = if (exitCode == 0) outputLines.firstNotNullOfOrNull(::skipReasonFor) else null
        return TestInstallResult(
            exitCode = exitCode,
            errorMessage = errorMessage,
            output = outputLines.joinToString("\n"),
            skippedMessage = skippedMessage,
        )
    }

    /** User-facing reason for a scoop run that exited 0 without doing the work.
     *  Sources: scoop-update.ps1 prints `WARN  '<app>' is held to version x.y.z` for a
     *  held app and `Running process detected, skip updating.` after test_running_process. */
    private fun skipReasonFor(line: String): String? {
        val trimmed = line.trim()
        return when {
            "is held to version" in trimmed -> {
                val detail = trimmed.removePrefix("WARN").trim().ifEmpty { trimmed }
                "Scoop skipped the update: $detail. Run 'scoop unhold <app>' to allow updates."
            }
            "Running process detected, skip updating." in trimmed ->
                "Scoop skipped the update because the app is still running. Close it and try again."
            else -> null
        }
    }

    // ==================== Install records ====================

    /**
     * Where Scoop recorded the install source for an installed app, plus the bucket
     * name when one is recorded. Read on demand: adding this to [installedSnapshot]
     * would turn one directory scan into one file read per installed app.
     */
    internal data class InstallRecordInfo(val origin: InstallOrigin, val bucket: String?)

    /**
     * Read the install record Scoop wrote for an app's current dir. A recorded url wins
     * over a recorded bucket because scoop-update prefers the url when picking a
     * reinstall target. Missing or unparsable records yield [InstallOrigin.Unknown].
     */
    internal fun readInstallRecord(appDir: Path): InstallRecordInfo {
        val recordFile = installRecordFileNames
            .map { appDir.resolve(it) }
            .firstOrNull { Files.isRegularFile(it) }
            ?: return InstallRecordInfo(InstallOrigin.Unknown, null)

        val record = try {
            val json = manifestJson.parseToJsonElement(readManifestText(recordFile.toFile())) as? JsonObject
            val url = json?.get("url").stringOrNull()
            val bucket = json?.get("bucket").stringOrNull()
            InstallRecordInfo(
                origin = when {
                    !url.isNullOrBlank() -> InstallOrigin.DirectFile
                    !bucket.isNullOrBlank() -> InstallOrigin.Bucket
                    else -> InstallOrigin.Unknown
                },
                bucket = bucket?.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            logger.warn("Failed to read install record $recordFile: ${e.message}")
            InstallRecordInfo(InstallOrigin.Unknown, null)
        }
        return record
    }

    /** String content of a JSON field, null when absent or not a primitive. */
    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    /**
     * True when the installed copy came from a local manifest file (direct install).
     * Such a record pins a url to a manifest that may no longer exist, so scoop update
     * cannot re-read it. [global] selects the scope, falling back to the other scope
     * when the app is not installed under the requested one.
     */
    internal fun isLocalManifestInstall(
        name: String,
        global: Boolean,
        rootDir: Path,
        globalRootDir: Path,
    ): Boolean {
        val primary = if (global) globalRootDir else rootDir
        val fallback = if (global) rootDir else globalRootDir
        val appDir = listOf(primary, fallback)
            .map { it.resolve("apps/$name/current") }
            .firstOrNull { Files.isDirectory(it) }
            ?: return false
        return readInstallRecord(appDir).origin == InstallOrigin.DirectFile
    }
}

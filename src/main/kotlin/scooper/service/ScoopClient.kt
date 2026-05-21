package scooper.service

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
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
import java.io.File
import java.nio.file.Files
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

/**
 * Adapter for Scoop CLI and filesystem operations.
 * Provides low-level access to the local Scoop environment.
 */
class ScoopClient(
    val logStream: ScoopLogStream,
    private val taskQueue: TaskQueue,
) : ScoopCli {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val manifestDownloader = ManifestDownloader()

    // ==================== Environment Paths ====================

    val configFile: File
        get() = File(System.getenv("USERPROFILE")).resolve(".config/scoop/config.json")

    val rootDir: File
        get() {
            val scoop = System.getenv("SCOOP")
            if (!scoop.isNullOrEmpty()) {
                val root = File(scoop)
                if (root.exists()) return root
            }
            return File(System.getenv("USERPROFILE")).resolve("scoop")
        }

    val globalRootDir: File
        get() {
            val scoop = System.getenv("SCOOP_GLOBAL")
            if (!scoop.isNullOrEmpty()) {
                val root = File(scoop)
                if (root.exists()) return root
            }
            return File(System.getenv("ALLUSERSPROFILE")).resolve("scoop")
        }

    val bucketsBaseDir: File
        get() = rootDir.resolve("buckets")

    val bucketNames: List<String>
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

    /**
     * Read installed app dirs once and return name-lowercase to info map.
     * Call this before batch manifest parsing to avoid repeated filesystem scans.
     */
    fun installedSnapshot(): Map<String, InstalledAppInfo> {
        val result = mutableMapOf<String, InstalledAppInfo>()
        for ((dirs, isGlobal) in listOf(localInstalledAppDirs to false, globalInstalledAppDirs to true)) {
            for (appDir in dirs) {
                val current = appDir.resolve("current")
                if (!current.exists()) continue
                val version = current.toPath().toRealPath().fileName.toString()
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
        val repoInfo = bucketDir.resolve(".git/config").readText()
        val regex = """\[remote\s+"origin"]\s*\n(\s*\n*)+url\s*=\s*(.+)""".toRegex()
        return regex.find(repoInfo)?.groupValues?.get(2)
    }

    /** Build the full app list from all bucket manifests, then deduplicate. */
    fun buildAllApps(installed: Map<String, InstalledAppInfo>): List<App> {
        val allApps = bucketDirs.flatMap { bucketDir ->
            val bucket = Bucket(name = bucketDir.name, url = "")
            bucketDir.resolve("bucket").listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.mapNotNull { file -> buildAppFromManifest(file, bucket, installed) }
                ?: emptyList()
        }
        val deduped = deduplicateApps(allApps)
        val knownNames = deduped.map { it.name.lowercase() }.toSet()
        return deduped + findOrphanInstalledApps(knownNames, installed)
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

    /** Deduplicate apps by name, keeping the first match per Scoop's behavior.
     *  Installed apps take priority; otherwise the first bucket in order wins. */
    private fun deduplicateApps(apps: List<App>): List<App> {
        val result = LinkedHashMap<String, App>()
        // First pass: prefer installed apps (use their bucket's manifest)
        for (app in apps) {
            if (app.installed) {
                result.putIfAbsent(app.name, app)
            }
        }
        // Second pass: fill remaining with first bucket match
        for (app in apps) {
            result.putIfAbsent(app.name, app)
        }
        return result.values.toList()
    }

    /** Build apps only from the specified manifest file names in a single bucket. */
    fun buildAppsFromManifestNames(
        bucketDir: File,
        manifestNames: Set<String>,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
    ): List<App> = manifestNames.mapNotNull { fileName ->
        val file = bucketDir.resolve("bucket/$fileName")
        if (!file.exists()) return@mapNotNull null
        buildAppFromManifest(file, bucket, installed)
    }

    private fun tryParseManifest(file: File): JsonObject? = try {
        Json.parseToJsonElement(file.readText()).jsonObject
    } catch (e: Exception) {
        logger.error("parsing manifest: ${file.absolutePath}, error: ${e.message}")
        null
    }

    private fun parseShortcuts(json: JsonObject): List<ShortCut> =
        json["shortcuts"]?.jsonArray?.let { array ->
            val normalized = if (array[0] is JsonArray) array else buildJsonArray { add(array) }
            normalized.map { ele ->
                ShortCut(ele.jsonArray[0].jsonPrimitive.content, ele.jsonArray[1].jsonPrimitive.content)
            }
        } ?: emptyList()

    private fun buildAppFromManifest(
        file: File,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
    ): App? {
        val json = tryParseManifest(file) ?: return null

        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val createAt = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault())
        val updateAt = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault())

        val name = file.nameWithoutExtension
        val info = installed[name.lowercase()]

        if (info != null) {
            return buildAppFromJson(json, name, info.version, info.isGlobal, bucket)
                .copy(createAt = createAt, updateAt = updateAt)
        }
        return buildAppFromJson(json, name, json.getString("version"), false, bucket)
            .copy(createAt = createAt, updateAt = updateAt, status = AppStatus.UNINSTALL)
    }

    fun computeCacheSize(): Long {
        return cacheDir.dirSize()
    }

    /** Collect manifest file names (e.g. "7zip.json") for a given bucket directory. */
    fun bucketDirManifestNames(bucketDir: File): Set<String> {
        return bucketDir.resolve("bucket").listFiles()
            ?.filter { !it.isDirectory && it.extension == "json" }
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

    override suspend fun uninstall(app: App, global: Boolean): CommandResult {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "uninstall", "-g", app.name)
        } else {
            mutableListOf("scoop", "uninstall", app.name)
        }
        return executeAndLog(commandArgs)
    }

    override suspend fun update(app: App, global: Boolean): CommandResult {
        preDownloadIfNeeded(app)
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "update", "-g", app.name)
        } else {
            mutableListOf("scoop", "update", app.name)
        }
        return executeAndLog(commandArgs)
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

    override fun stop() {
        logger.warn("stopping all processes...")
        killAllSubProcesses()
        logger.warn("all processes stopped")
    }

    override suspend fun installVersion(app: App, manifestFile: File, global: Boolean): CommandResult {
        val currentlyInstalledInTargetScope = if (global) {
            globalInstalledAppDirs.any { it.name.equals(app.name, ignoreCase = true) }
        } else {
            localInstalledAppDirs.any { it.name.equals(app.name, ignoreCase = true) }
        }

        if (currentlyInstalledInTargetScope) {
            val uninstallArgs = if (global) {
                mutableListOf("sudo", "scoop", "uninstall", "-g", app.name)
            } else {
                mutableListOf("scoop", "uninstall", app.name)
            }
            logStream.emit("Uninstalling current ${app.name} before installing version from manifest...")
            val uninstallResult = executeAndLog(uninstallArgs)
            if (uninstallResult.exitCode != 0) return uninstallResult
        }

        val installArgs = if (global) {
            mutableListOf("sudo", "scoop", "install", "-g", manifestFile.absolutePath)
        } else {
            mutableListOf("scoop", "install", manifestFile.absolutePath)
        }
        return executeAndLog(installArgs)
    }

    // ==================== Internal Methods ====================

    /** Pre-download files to cache via JVM HttpClient when aria2 is not enabled. */
    private suspend fun preDownloadIfNeeded(app: App) {
        val config = ScoopConfigManager.readScoopConfig()
        if (config.aria2Enabled) return

        val manifestFile = findManifest(app) ?: run {
            logger.warn("Manifest not found for ${app.uniqueName}, skip pre-download")
            return
        }

        val json = try {
            Json.parseToJsonElement(manifestFile.readText()).jsonObject
        } catch (e: Exception) {
            logger.warn("Failed to parse manifest: ${e.message}, skip pre-download")
            return
        }

        val info = manifestDownloader.parseDownloadInfo(json)
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
            Json.parseToJsonElement(manifestFile.readText()).jsonObject
        } catch (e: Exception) {
            logStream.emit("Failed to parse manifest: ${e.message}")
            return CommandResult(1)
        }

        val info = manifestDownloader.parseDownloadInfo(json)
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

        for ((index, item) in info.items.withIndex()) {
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

            logStream.emit("Downloading ${app.name}... (${index + 1}/$totalItems)")
            val result = manifestDownloader.download(
                url = item.url,
                destFile = destFile,
                hash = item.hash,
            ) { percent ->
                val overall = (((index + percent / 100f) / totalItems) * 100).toInt().coerceIn(0, 100)
                taskQueue.updateProgress(overall)
            }

            if (result != null) {
                logStream.emit("Downloaded to cache: ${result.absolutePath}")
                logStream.emit("Cache exists after download: ${result.exists()}")
            } else {
                logStream.emit("Failed downloading item[${index + 1}/$totalItems] for ${app.name}")
                return false
            }
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

    /**
     * Aligns with PowerShell: [System.IO.Path]::GetExtension($url)
     * Does not strip query/fragment, to stay consistent with scoop.
     */
    private fun scoopCacheExtension(url: String): String {
        val normalized = url.replace('\\', '/')
        val ext = File(normalized).extension
        return if (ext.isNotEmpty()) ".${ext}" else ""
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
        val archObj = json["architecture"]?.jsonObject
        if (archObj != null) {
            val archBlock = archObj[arch]?.jsonObject ?: archObj["64bit"]?.jsonObject
            if (archBlock != null) {
                val urlElement = archBlock["url"]
                val url = when (urlElement) {
                    is JsonArray -> urlElement.firstOrNull()?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    else -> urlElement?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
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

    /** Open a shortcut of an installed app. */
    fun openShortcut(app: App, shortcutIndex: Int = 0) {
        val shortcuts = app.shortcuts ?: return
        if (shortcutIndex !in shortcuts.indices) return

        val root = if (app.global) globalRootDir else rootDir
        val appDir = root.resolve("apps/${app.name}/current")
        if (!appDir.exists()) return

        val shortcut = shortcuts[shortcutIndex]
        // shortcut.title is the exe relative path (e.g. "Fiddler.exe"), shortcut.path is the display name
        val target = appDir.resolve(shortcut.title.replace('\\', '/'))
        val dir = target.parentFile

        logger.info("Opening shortcut: ${target.absolutePath}")
        ProcessBuilder("cmd", "/c", "start", "", target.absolutePath)
            .directory(dir)
            .start()
    }

    private suspend fun executeAndLog(args: List<String>): CommandResult {
        val outputLines = mutableListOf<String>()
        val result = executeSuspend(
            args,
            consumer = { line ->
                outputLines.add(line)
                logStream.emit(line)
                logger.info(line)
                ProgressParser.parseProgress(line)?.let { taskQueue.updateProgress(it) }
            },
            onFinish = {},
        )
        val errorMessage = outputLines.find { " ERROR " in it || it.trimStart().startsWith("ERROR ") }
        val exitCode = if (errorMessage != null && result.resultCode == 0) 1 else result.resultCode
        return CommandResult(exitCode, errorMessage)
    }
}

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

/**
 * Service layer for Scoop CLI and filesystem operations.
 * Injected via Koin DI, supports testing and lifecycle management.
 */
class ScoopService(
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

    /** Parse all bucket manifest files to build the complete app list. */
    val apps: List<App>
        get() {
            val localInstallApps = localInstalledAppDirs.map { it.name.lowercase() }
            val globalInstalledApps = globalInstalledAppDirs.map { it.name.lowercase() }

            val allApps = mutableListOf<App>()
            for (bucketDir in bucketDirs) {
                val bucket = Bucket(name = bucketDir.name, url = "")
                val apps = bucketDir.resolve("bucket").listFiles()
                    ?.filter { !it.isDirectory && it.extension == "json" }
                    ?.mapNotNull { file -> buildAppFromManifest(file, bucket, localInstallApps, globalInstalledApps) }
                    ?: listOf()
                allApps.addAll(apps)
            }
            return allApps
        }

    private fun buildAppFromManifest(
        file: File,
        bucket: Bucket,
        localInstallApps: List<String>,
        globalInstalledApps: List<String>,
    ): App? {
        val json = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            logger.error("parsing manifest: ${file.absolutePath}, error: ${e.message}")
            null
        } ?: return null

        val shortcuts: List<ShortCut> = json["shortcuts"]?.jsonArray?.let { array ->
            val normalized = if (array[0] is JsonArray) array else buildJsonArray { add(array) }
            normalized.map { ele ->
                ShortCut(ele.jsonArray[0].jsonPrimitive.content, ele.jsonArray[1].jsonPrimitive.content)
            }
        } ?: emptyList()

        val base = App(
            name = file.nameWithoutExtension,
            latestVersion = json.getString("version"),
            version = json.getString("version"),
            homepage = json.getString("homepage"),
            description = json.getString("description"),
            url = json.getString("url"),
            license = json.getString("license"),
            bucket = bucket,
            shortcuts = shortcuts,
        )

        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val createAt = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault())
        val updateAt = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault())

        val isGlobal = globalInstalledApps.contains(base.name.lowercase())
        val isInstalled = isGlobal || localInstallApps.contains(base.name.lowercase())

        return if (isInstalled) {
            val installedVersion = (globalInstalledAppDirs + localInstalledAppDirs)
                .find { it.name.equals(base.name, ignoreCase = true) }!!
                .resolve("current")
                .let { if (!it.exists()) null else it.toPath().toRealPath().fileName.toString() }
            base.copy(
                createAt = createAt,
                updateAt = updateAt,
                global = isGlobal,
                status = if (installedVersion == null) AppStatus.FAILED else AppStatus.INSTALLED,
                version = installedVersion,
            )
        } else {
            base.copy(createAt = createAt, updateAt = updateAt)
        }
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

    override suspend fun refresh(onFinish: suspend (exitValue: Int) -> Unit) {
        executeAndLog(mutableListOf("scoop", "update"), onFinish = onFinish)
    }

    override suspend fun install(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
        preDownloadIfNeeded(app)
        val commandArgs = if (global) mutableListOf(
            "sudo", "scoop", "install", "-g", "${app.bucket!!.name}/${app.name}"
        ) else {
            mutableListOf("scoop", "install", "${app.bucket!!.name}/${app.name}")
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override suspend fun uninstall(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "uninstall", "-g", app.name)
        } else {
            mutableListOf("scoop", "uninstall", app.name)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override suspend fun update(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
        preDownloadIfNeeded(app)
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "update", "-g", app.name)
        } else {
            mutableListOf("scoop", "update", app.name)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override suspend fun download(app: App, onFinish: suspend (exitValue: Int) -> Unit) {
        val config = ScoopConfigManager.readScoopConfig()
        if (config.aria2Enabled) {
            // aria2 mode: use scoop download command, parse progress from output
            executeAndLog(mutableListOf("scoop", "download", app.uniqueName), onFinish = onFinish)
        } else {
            // Non-aria2 mode: use JVM HttpClient for precise progress
            downloadWithJvm(app, onFinish)
        }
    }

    override suspend fun addBucket(bucket: String, url: String?, onFinish: suspend (exitValue: Int) -> Unit) {
        val commandArgs = mutableListOf("scoop", "bucket", "add", bucket)
        if (url != null) commandArgs.add(url)
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override suspend fun removeBucket(bucket: String, onFinish: suspend (exitValue: Int) -> Unit) {
        executeAndLog(mutableListOf("scoop", "bucket", "rm", bucket), onFinish = onFinish)
    }

    override suspend fun cleanup(vararg apps: String, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "cleanup", "-g", *apps)
        } else {
            mutableListOf("scoop", "cleanup", *apps)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override suspend fun removeCache(vararg apps: String, onFinish: suspend (exitValue: Int) -> Unit) {
        val targets = if (apps.isEmpty()) arrayOf("-a") else apps
        val commandArgs = mutableListOf("scoop", "cache", "rm", *targets)
        logger.info("remove cache, commandArgs = $commandArgs")
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override fun stop() {
        logger.warn("stopping all processes...")
        killAllSubProcesses()
        logger.warn("all processes stopped")
    }

    override suspend fun installVersion(app: App, manifestFile: File, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
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
            val uninstallExit = executeAndLog(uninstallArgs)
            if (uninstallExit != 0) {
                onFinish(uninstallExit)
                return
            }
        }

        val installArgs = if (global) {
            mutableListOf("sudo", "scoop", "install", "-g", manifestFile.absolutePath)
        } else {
            mutableListOf("scoop", "install", manifestFile.absolutePath)
        }
        executeAndLog(installArgs, onFinish = onFinish)
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

    private suspend fun downloadWithJvm(app: App, onFinish: suspend (exitValue: Int) -> Unit) {
        val manifestFile = findManifest(app)
        if (manifestFile == null) {
            logStream.emit("Manifest not found for ${app.uniqueName}")
            onFinish(1)
            return
        }

        val json = try {
            Json.parseToJsonElement(manifestFile.readText()).jsonObject
        } catch (e: Exception) {
            logStream.emit("Failed to parse manifest: ${e.message}")
            onFinish(1)
            return
        }

        val info = manifestDownloader.parseDownloadInfo(json)
        if (info == null) {
            logStream.emit("No download URL found in manifest for ${app.uniqueName}")
            onFinish(1)
            return
        }

        val version = json.getString("version")
        val ok = downloadManifestItemsToCache(app, version, info, logPrefix = "Download")
        onFinish(if (ok) 0 else 1)
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
        val normalized = url.replace('/', '\\')
        val ext = File(normalized).extension
        return if (ext.isNotEmpty()) ".${ext}" else ""
    }

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

    private suspend fun executeAndLog(args: List<String>): Int {
        val result = executeSuspend(
            args,
            consumer = { line ->
                logStream.emit(line)
                logger.info(line)
                ProgressParser.parseProgress(line)?.let { taskQueue.updateProgress(it) }
            },
            onFinish = {},
        )
        return result.resultCode
    }

    private suspend fun executeAndLog(args: List<String>, onFinish: suspend (exitValue: Int) -> Unit) {
        onFinish(executeAndLog(args))
    }
}

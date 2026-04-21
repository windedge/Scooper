package scooper.service

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.data.ShortCut
import scooper.util.dirSize
import scooper.util.execute
import scooper.util.executeSuspend
import scooper.util.findExecutable
import scooper.util.getString
import scooper.util.killAllSubProcesses
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Service layer for Scoop CLI and filesystem operations.
 * Injected via Koin DI, supports testing and lifecycle management.
 */
class ScoopService(
    val logStream: ScoopLogStream,
) : ScoopCli {
    private val logger = LoggerFactory.getLogger(javaClass)

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

    /** 解析所有 bucket 目录下的 manifest 文件，构建完整的应用列表。 */
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

    // ==================== CLI Commands ====================

    override suspend fun refresh(onFinish: suspend (exitValue: Int) -> Unit) {
        executeAndLog(mutableListOf("scoop", "update"), onFinish = onFinish)
    }

    override suspend fun install(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
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
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "update", "-g", app.name)
        } else {
            mutableListOf("scoop", "update", app.name)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    override suspend fun download(app: App, onFinish: suspend (exitValue: Int) -> Unit) {
        executeAndLog(mutableListOf("scoop", "download", app.uniqueName), onFinish = onFinish)
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

    // ==================== Internal Methods ====================

    private suspend fun executeAndLog(args: List<String>, onFinish: suspend (exitValue: Int) -> Unit) {
        executeSuspend(args, consumer = { logStream.emit(it); logger.info(it) }, onFinish = onFinish)
    }
}

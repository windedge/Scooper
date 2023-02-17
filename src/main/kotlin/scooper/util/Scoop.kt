package scooper.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.Bucket
import scooper.data.ScoopConfig
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.ZoneId

@Suppress("MemberVisibilityCanBePrivate")
object Scoop {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val _logStream = MutableSharedFlow<String>() // private mutable shared flow
    val logStream = _logStream.asSharedFlow()

    val configFile: File
        get() {
            return File(System.getenv("USERPROFILE")).resolve(".config/scoop/config.json")
        }

    val rootDir: File
        get() {
            val scoop = System.getenv("SCOOP")
            if (!scoop.isNullOrEmpty()) {
                val root = File(scoop)
                if (root.exists()) {
                    return root
                }
            }
            return File(System.getenv("USERPROFILE")).resolve("scoop")
        }

    val globalRootDir: File
        get() {
            val scoop = System.getenv("SCOOP_GLOBAL")
            if (!scoop.isNullOrEmpty()) {
                val root = File(scoop)
                if (root.exists()) {
                    return root
                }
            }
            return File(System.getenv("ALLUSERSPROFILE")).resolve("scoop")
        }

    val bucketsBaseDir: File
        get() {
            return rootDir.resolve("buckets")
        }

    val bucketNames: List<String>
        get() {
            return bucketsBaseDir.list()?.asList() ?: listOf()
        }

    val bucketDirs: List<File>
        get() {
            val buckets = mutableListOf<File>()
            for (bucketName in bucketNames) {
                buckets.add((bucketsBaseDir.resolve(bucketName)))
            }
            return buckets
        }

    fun getBucketRepo(bucketDir: File): String? {
        if (findExecutable("git.exe") == null) {
            return null
        }

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

    val localInstalledAppDirs: List<File>
        get() {
            return rootDir
                .resolve("apps").listFiles { file -> file.isDirectory }
                ?.toList() ?: listOf()
        }

    val globalInstalledAppDirs: List<File>
        get() {
            return globalRootDir
                .resolve("apps").listFiles { file -> file.isDirectory }
                ?.toList() ?: listOf()
        }

    val apps: List<App>
        get() {
            val localInstallApps = localInstalledAppDirs.map { it.name.lowercase() }
            val globalInstalledApps = globalInstalledAppDirs.map { it.name.lowercase() }

            val allApps = mutableListOf<App>()
            for (bucketDir in bucketDirs) {
                val bucket = Bucket(name = bucketDir.name, url = "")
                val apps = bucketDir.resolve("bucket").listFiles()
                    ?.filter {
                        !it.isDirectory and (it.extension == "json")
                    }?.mapNotNull { file ->
                        parseManifest(file)?.apply {
                            this.version = this.latestVersion
                            this.bucket = bucket
                            val attrs = Files.readAttributes(
                                file.toPath(),
                                BasicFileAttributes::class.java
                            )
                            this.createAt = LocalDateTime.ofInstant(
                                attrs.creationTime().toInstant(),
                                ZoneId.systemDefault()
                            )
                            this.updateAt = LocalDateTime.ofInstant(
                                attrs.lastModifiedTime().toInstant(),
                                ZoneId.systemDefault()
                            )

                            if (globalInstalledApps.contains(name.lowercase())) {
                                this.global = true
                            }

                            if (globalInstalledApps.contains(name.lowercase())
                                || localInstallApps.contains(name.lowercase())
                            ) {
                                val version = (globalInstalledAppDirs + localInstalledAppDirs)
                                    .find { it.name.equals(name, ignoreCase = true) }!!
                                    .resolve("current").let {
                                        if (!it.exists()) {
                                            null
                                        } else {
                                            it.toPath().toRealPath().fileName.toString()
                                        }
                                    }
                                this.version = version
                                if (version == null) {
                                    this.status = "failed"
                                } else {
                                    this.status = "installed"
                                }
                            }
                        }
                    }
                    ?: listOf()
                allApps.addAll(apps)
            }
            return allApps
        }

    val cacheDir: File
        get() {
            return rootDir.resolve("cache")
        }

    fun computeCacheSize(): Long {
        return cacheDir.dirSize()
    }

    private fun parseManifest(manifest: File): App? {
        // logger.info("parsing manifest: ${manifest.absolutePath}")
        val json = try {
            Json.parseToJsonElement(manifest.readText()).jsonObject
        } catch (e: Exception) {
            logger.error("parsing manifest: ${manifest.absolutePath}, error: ${e.message}")
            null
        }

        return json?.run {
            App(
                name = manifest.nameWithoutExtension,
                latestVersion = getString("version"),
                homepage = getString("homepage"),
                description = getString("description"),
                url = getString("url"),
                license = getString("license")
            )
        }
    }

    fun refresh(onFinish: suspend (exitValue: Int) -> Unit = {}) {
        val commandArgs = mutableListOf("scoop", "update")
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun install(app: App, global: Boolean = false, onFinish: suspend (exitValue: Int) -> Unit = {}) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "install", "-g", String.format("%s/%s", app.bucket!!.name, app.name))
        } else {
            mutableListOf("scoop", "install", String.format("%s/%s", app.bucket!!.name, app.name))
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun uninstall(app: App, global: Boolean = false, onFinish: suspend (exitValue: Int) -> Unit = {}) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "uninstall", "-g", app.name)
        } else {
            mutableListOf("scoop", "uninstall", app.name)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun update(app: App, global: Boolean = false, onFinish: suspend (exitValue: Int) -> Unit = {}) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "update", "-g", app.name)
        } else {
            mutableListOf("scoop", "update", app.name)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun download(app: App, onFinish: suspend (exitValue: Int) -> Unit = {}) {
        val commandArgs = mutableListOf("scoop", "download", app.uniqueName)
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun addBucket(bucket: String, url: String? = null, onFinish: suspend (exitValue: Int) -> Unit) {
        val commandArgs = mutableListOf("scoop", "bucket", "add", bucket)
        if (url != null) {
            commandArgs.add(url)
        }
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun removeBucket(bucket: String, onFinish: suspend (exitValue: Int) -> Unit) {
        val commandArgs = mutableListOf("scoop", "bucket", "rm", bucket)
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun cleanup(vararg apps: String, global: Boolean = false, onFinish: suspend (exitValue: Int) -> Unit) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "cleanup", "-g", *apps)
        } else {
            mutableListOf("scoop", "cleanup", *apps)
        }

        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun removeCache(vararg apps: String, onFinish: suspend (exitValue: Int) -> Unit) {
        val targets = if (apps.isEmpty()) arrayOf("-a") else apps
        val commandArgs = mutableListOf("scoop", "cache", "rm", *targets)
        logger.info("remove cache, commandArgs = $commandArgs")
        executeAndLog(commandArgs, onFinish = onFinish)
    }

    fun stop() {
        logger.warn("stopping all processes...")
        killAllSubProcesses()
        logger.warn("stopping all processes...")
    }

    fun readScoopConfig(file: File = configFile): ScoopConfig {
        val format = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }
        val jsonText = file.readText()
        return format.decodeFromString(jsonText)
    }

    fun writeScoopConfig(
        config: ScoopConfig,
        file: File = configFile,
        output: OutputStream = configFile.outputStream()
    ) {
        val (result, format) = mergeConfigToJson(config, file.readText())
        output.use {
            it.write(format.encodeToString(result).toByteArray())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun mergeConfigToJson(
        config: ScoopConfig,
        originalJsonStr: String,
    ): Pair<JsonObject, Json> {
        val format = Json {
            // encodeDefaults = true
            isLenient = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        val json = format.encodeToJsonElement(config).jsonObject
        val jsonFromConfig = Json.parseToJsonElement(originalJsonStr).jsonObject

        val keysToRemove = ScoopConfig.serializer().descriptor.elementNames.toSet() - json.jsonObject.keys
        val jsonToWrite = buildJsonObject {
            jsonFromConfig.filter { it.key !in keysToRemove }.forEach { put(it.key, it.value) }
            json.forEach { put(it.key, it.value) }
        }
        return jsonToWrite to format
    }

    private fun executeAndLog(args: List<String>, onFinish: suspend (exitValue: Int) -> Unit) {
        execute(args, consumer = { _logStream.emit(it); logger.info(it) }, onFinish = onFinish)
    }
}
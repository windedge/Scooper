/**
 * 基准测试：对比 Git log 批量查询 vs 文件系统属性读取的性能
 *
 * 测试内容：
 * 1. git log --format="%ct" --name-only --no-renames -- bucket/        （updateAt）
 * 2. git log --reverse --diff-filter=A --format="%ct" --name-only --no-renames -- bucket/  （createAt）
 * 3. Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) 逐文件读取
 *
 * 运行：./gradlew test --tests scooper.GitHistoryBenchmarkTest
 */
package scooper

import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.system.measureTimeMillis

class GitHistoryBenchmarkTest {

    private val bucketDir = File("C:/Users/xujl/scoop/buckets/main")
    private val manifestDir = bucketDir.resolve("bucket")

    @Test
    fun `benchmark - git log --numstat (compact) vs full git log vs file attributes`() {
        println("=== Scooper Git History Benchmark ===")
        println("Bucket: ${bucketDir.absolutePath}")
        println()

        val manifestFiles = manifestDir.listFiles()
            ?.filter { !it.isDirectory && it.extension == "json" }
            ?: emptyList()
        println("Manifest count: ${manifestFiles.size}")
        println()

        // ========== Test 1: git log for updateAt ==========
        val updateAtTimes = mutableMapOf<String, LocalDateTime>()
        val updateAtMillis = measureTimeMillis {
            val process = ProcessBuilder("git", "log", "--format=%ct", "--name-only", "--no-renames", "--", "bucket/")
                .directory(bucketDir)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var currentTimestamp: Long = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.isEmpty()) continue
                val asLong = l.toLongOrNull()
                if (asLong != null) {
                    currentTimestamp = asLong
                } else if (currentTimestamp != 0L && l.startsWith("bucket/") && l.endsWith(".json")) {
                    val fileName = l.removePrefix("bucket/")
                    if (!updateAtTimes.containsKey(fileName)) {
                        updateAtTimes[fileName] = LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(currentTimestamp), ZoneId.systemDefault()
                        )
                    }
                }
            }
            process.waitFor()
        }
        println("[1] git log (updateAt) — ${updateAtTimes.size} manifests resolved in ${updateAtMillis}ms")

        // ========== Test 2: git log --reverse for createAt ==========
        val createAtTimes = mutableMapOf<String, LocalDateTime>()
        val createAtMillis = measureTimeMillis {
            val process = ProcessBuilder(
                "git", "log", "--reverse", "--diff-filter=A",
                "--format=%ct", "--name-only", "--no-renames", "--", "bucket/"
            )
                .directory(bucketDir)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var currentTimestamp: Long = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.isEmpty()) continue
                val asLong = l.toLongOrNull()
                if (asLong != null) {
                    currentTimestamp = asLong
                } else if (currentTimestamp != 0L && l.startsWith("bucket/") && l.endsWith(".json")) {
                    val fileName = l.removePrefix("bucket/")
                    if (!createAtTimes.containsKey(fileName)) {
                        createAtTimes[fileName] = LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(currentTimestamp), ZoneId.systemDefault()
                        )
                    }
                }
            }
            process.waitFor()
        }
        println("[2] git log --reverse (createAt) — ${createAtTimes.size} manifests resolved in ${createAtMillis}ms")

        // ========== Test 3: file system attributes (逐文件) ==========
        val fileTimes = mutableMapOf<String, Pair<LocalDateTime, LocalDateTime>>()
        val fileAttrMillis = measureTimeMillis {
            for (file in manifestFiles) {
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                val createAt = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault())
                val updateAt = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault())
                fileTimes[file.name] = Pair(createAt, updateAt)
            }
        }
        println("[3] Files.readAttributes (逐文件) — ${fileTimes.size} manifests in ${fileAttrMillis}ms")

        // ========== Test 4: git log --name-status (单次调用同时获取 createAt + updateAt) ==========
        val nameStatusCreate = mutableMapOf<String, LocalDateTime>()
        val nameStatusUpdate = mutableMapOf<String, LocalDateTime>()
        val nameStatusMillis = measureTimeMillis {
            // 从新到旧，第一次出现就是 updateAt
            val process = ProcessBuilder(
                "git", "log", "--format=%ct", "--name-status", "--no-renames", "--", "bucket/"
            )
                .directory(bucketDir)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var currentTimestamp: Long = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.isEmpty()) continue
                val asLong = l.toLongOrNull()
                if (asLong != null) {
                    currentTimestamp = asLong
                } else if (currentTimestamp != 0L) {
                    // name-status format: "A\tbucket/xxx.json" or "M\tbucket/xxx.json"
                    val parts = l.split("\t", limit = 2)
                    if (parts.size == 2 && parts[1].startsWith("bucket/") && parts[1].endsWith(".json")) {
                        val status = parts[0]
                        val fileName = parts[1].removePrefix("bucket/")
                        // updateAt: 第一次出现（从新到旧）
                        if (!nameStatusUpdate.containsKey(fileName)) {
                            nameStatusUpdate[fileName] = LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(currentTimestamp), ZoneId.systemDefault()
                            )
                        }
                        // createAt: A(dd) 状态
                        if (status == "A" && !nameStatusCreate.containsKey(fileName)) {
                            nameStatusCreate[fileName] = LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(currentTimestamp), ZoneId.systemDefault()
                            )
                        }
                    }
                }
            }
            process.waitFor()
        }
        println("[4] git log --name-status (1 call) — updateAt: ${nameStatusUpdate.size}, createAt: ${nameStatusCreate.size} in ${nameStatusMillis}ms")

        // ========== Test 5: git log --diff-filter=A --name-only 逆向单次调用 ==========
        val reverseCreateTimes = mutableMapOf<String, LocalDateTime>()
        val reverseCreateMillis = measureTimeMillis {
            // 从旧到新，第一次出现就是 createAt
            val process = ProcessBuilder(
                "git", "log", "--reverse", "--diff-filter=A",
                "--format=%ct", "--name-only", "--no-renames", "--", "bucket/"
            )
                .directory(bucketDir)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var currentTimestamp: Long = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.isEmpty()) continue
                val asLong = l.toLongOrNull()
                if (asLong != null) {
                    currentTimestamp = asLong
                } else if (currentTimestamp != 0L && l.startsWith("bucket/") && l.endsWith(".json")) {
                    val fileName = l.removePrefix("bucket/")
                    if (!reverseCreateTimes.containsKey(fileName)) {
                        reverseCreateTimes[fileName] = LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(currentTimestamp), ZoneId.systemDefault()
                        )
                    }
                }
            }
            process.waitFor()
        }
        println("[5] git log --reverse --diff-filter=A (createAt only) — ${reverseCreateTimes.size} in ${reverseCreateMillis}ms")

        // ========== 汇总 ==========
        val gitTotal = updateAtMillis + createAtMillis
        val gitOneCallTotal = nameStatusMillis
        val gitOptimizedTotal = nameStatusMillis + reverseCreateMillis // name-status + reverse createAt
        println()
        println("=== Summary ===")
        println("[Original] Git log (2 calls, name-only):      ${gitTotal}ms")
        println("[Optimized] Git log --name-status (1 call):    ${gitOneCallTotal}ms")
        println("[Alt] name-status + reverse createAt (2 calls): ${gitOptimizedTotal}ms")
        println("File attributes (${manifestFiles.size} calls):                ${fileAttrMillis}ms")
        println()
        println("Git --name-status covers createAt A-status: ${nameStatusCreate.size} / ${manifestFiles.size}")
        println("Reverse --diff-filter=A covers createAt:    ${reverseCreateTimes.size} / ${manifestFiles.size}")
        println()

        // ========== 抽样对比 ==========
        println("=== Sample comparison (first 10 manifests) ===")
        println("%-30s  %-12s  %-12s  %-12s  %-12s  %-12s".format(
            "Name", "Git create", "Git update", "NS create", "File create", "File update"))
        println("-".repeat(100))
        manifestFiles.take(10).forEach { file ->
            println("%-30s  %-12s  %-12s  %-12s  %-12s  %-12s".format(
                file.name.take(30),
                createAtTimes[file.name]?.toLocalDate()?.toString() ?: "N/A",
                updateAtTimes[file.name]?.toLocalDate()?.toString() ?: "N/A",
                nameStatusCreate[file.name]?.toLocalDate()?.toString() ?: "N/A",
                fileTimes[file.name]?.first?.toLocalDate()?.toString() ?: "N/A",
                fileTimes[file.name]?.second?.toLocalDate()?.toString() ?: "N/A",
            ))
        }
    }
}

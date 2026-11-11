package scooper.service

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import scooper.data.App
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import java.io.File

/** Fake [BucketSaveEnv] over a temp-dir bucket layout: baseDir/<bucket>/bucket/<name>.json.
 *  Every indexed manifest becomes a 1.2.3 record attributed to that bucket.
 */
class FakeBucketSaveEnv(
    override val bucketsBaseDir: File,
    override val bucketNames: List<String>,
) : BucketSaveEnv {
    override fun installedSnapshot(): Map<String, InstalledAppInfo> = emptyMap()

    override fun buildAppsFromManifestNames(
        bucketDir: File,
        manifestNames: Set<String>,
        bucket: Bucket,
        installed: Map<String, InstalledAppInfo>,
    ): List<App> = manifestNames.mapNotNull { fileName ->
        val file = bucketDir.resolve("bucket/$fileName")
        if (!file.exists()) return@mapNotNull null
        App(name = fileName.removeSuffix(".json"), latestVersion = "1.2.3", bucket = Bucket(bucket.name))
    }
}

/**
 * Test-only factory: an [AppsRepository] backed by a throwaway sqlite file,
 * with the same schema [scooper.repository.AppsRepositoryTest] sets up
 * (Exposed tables plus the FTS5 virtual table getApps searches through).
 */
fun createInMemoryAppsRepository(scoopClient: ScoopClient): AppsRepository {
    val dbFile = File.createTempFile("scooper-test-", ".db")
    dbFile.deleteOnExit()
    Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(Apps, Buckets)
        exec("CREATE VIRTUAL TABLE IF NOT EXISTS apps_fts USING fts5(name, description, content='apps', content_rowid='id')")
    }
    return AppsRepository(
        scoopClient = scoopClient,
        gitHistoryService = GitHistoryService(),
    )
}

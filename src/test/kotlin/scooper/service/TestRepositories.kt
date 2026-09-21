package scooper.service

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import scooper.repository.AppsRepository
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import java.io.File

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

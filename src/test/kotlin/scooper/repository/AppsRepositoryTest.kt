package scooper.repository

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import scooper.service.ScoopClient
import scooper.service.GitHistoryService
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File


internal class AppsRepositoryTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            val databasePath = kotlin.io.path.createTempDirectory("scooper-test").resolve("scooper-test.db").toFile()
            Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")

            transaction {
                // Same as production InitDB: createMissingTablesAndColumns generates
                // MySQL-only ALTER statements (MODIFY COLUMN) not supported by SQLite
                SchemaUtils.create(Apps, Buckets)
                // loadAll() rebuilds the FTS index, which requires the virtual table
                exec("CREATE VIRTUAL TABLE IF NOT EXISTS apps_fts USING fts5(name, description, content='apps', content_rowid='id')")
            }
        }
    }

    @Test
    fun loadApps() {
        val appsRepository = AppsRepository(ScoopClient(ScoopLogStream(), TaskQueue()), GitHistoryService())
        appsRepository.loadAll()

        transaction {
            val appsCount = Apps.selectAll().count()
            assert(appsCount > 0)
        }

    }
}
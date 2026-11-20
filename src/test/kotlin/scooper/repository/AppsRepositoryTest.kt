package scooper.repository

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import scooper.service.GitHistoryService
import scooper.service.ScoopTempRoot
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
    fun loadApps(@TempDir tempDir: File) {
        val root = ScoopTempRoot(tempDir)
        root.writeManifest("main", "ripgrep", "14.1.0")
        root.writeManifest("main", "fd", "10.2.0")
        val appsRepository = AppsRepository(root.client(), GitHistoryService())
        appsRepository.loadAll()

        transaction {
            assertEquals(2L, Apps.selectAll().count())
            assertEquals(setOf("fd", "ripgrep"), Apps.selectAll().map { it[Apps.name] }.toSet())
        }

    }
}
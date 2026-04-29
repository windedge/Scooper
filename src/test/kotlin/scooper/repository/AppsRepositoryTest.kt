package scooper.repository

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import scooper.service.GitHistoryService
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.taskqueue.TaskQueue
import java.io.File


internal class AppsRepositoryTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            val databasePath = File("d:\\tmp\\", "scooper-test.db")
            Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")

            transaction {
                SchemaUtils.createMissingTablesAndColumns(Apps, Buckets)
            }
        }
    }

    @Test
    fun loadApps() {
        val appsRepository = AppsRepository(ScoopService(ScoopLogStream(), TaskQueue()), GitHistoryService())
        appsRepository.loadAll()

        transaction {
            val appsCount = Apps.selectAll().count()
            assert(appsCount > 0)
        }

    }
}
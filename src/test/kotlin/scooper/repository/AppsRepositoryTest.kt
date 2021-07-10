package scooper.repository

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
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
        AppsRepository.loadAll()

        transaction {
            val appsCount = Apps.selectAll().count()
            assert(appsCount > 0)
        }

    }
}
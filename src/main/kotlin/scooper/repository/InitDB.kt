package scooper.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import scooper.repository.db.Configs
import java.io.File


suspend fun initDb(appsRepository: AppsRepository) = withContext(Dispatchers.IO) {
    val databasePath = File(System.getenv("USERPROFILE")).resolve(".scooper.db")
    Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC", setupConnection = { connection ->
        connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
    })

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Apps, Buckets, Configs)
    }

    val appCount = transaction { Apps.selectAll().count() }
    if (appCount == 0L) {
        appsRepository.loadAll()
    }
}
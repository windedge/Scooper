package scooper.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import scooper.repository.db.Configs
import java.io.File


suspend fun initDb(appsRepository: AppsRepository, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
    onProgress(0f)

    val databasePath = File(System.getenv("USERPROFILE")).resolve(".scooper.db")
    Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC", setupConnection = { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate("PRAGMA foreign_keys = ON")
            // Allow short write-lock contention instead of failing immediately with SQLITE_BUSY.
            statement.executeUpdate("PRAGMA busy_timeout = 10000")
            // Readers and a writer can coexist better; SQLite still serializes writers.
            statement.execute("PRAGMA journal_mode = WAL")
        }
    })
    onProgress(0.1f)

    transaction {
        // SQLite does not support ALTER TABLE ... MODIFY COLUMN / ADD PRIMARY KEY.
        // Use create (creates missing tables) + safe ADD COLUMN statements.
        SchemaUtils.create(Apps, Buckets, Configs)
        SchemaUtils.addMissingColumnsStatements(Apps, Buckets, Configs, withLogs = true).forEach { stmt ->
            if (stmt.contains("MODIFY COLUMN", ignoreCase = true) || stmt.contains("ADD PRIMARY KEY", ignoreCase = true)) {
                // SQLite does not support these; skip silently.
                return@forEach
            }
            exec(stmt)
        }
        createFtsTable()
    }
    onProgress(0.2f)

    val appCount = transaction { Apps.selectAll().count() }
    if (appCount == 0L) {
        appsRepository.loadBuckets()
        onProgress(0.3f)
        appsRepository.loadApps()
        onProgress(0.9f)
    }

    // Create FTS triggers and rebuild index after all data is loaded.
    // This avoids per-row trigger overhead during initial bulk INSERT.
    transaction {
        createFtsTriggers()
        rebuildFts()
    }
    onProgress(1f)
}

/** Create the FTS5 virtual table (no triggers, safe to call before bulk data load). */
private fun Transaction.createFtsTable() {
    exec("CREATE VIRTUAL TABLE IF NOT EXISTS apps_fts USING fts5(name, description, content='apps', content_rowid='id')")
}

/** Create triggers that keep FTS index in sync with the apps table. */
private fun Transaction.createFtsTriggers() {
    exec("""
        CREATE TRIGGER IF NOT EXISTS apps_fts_ai AFTER INSERT ON apps BEGIN
            INSERT INTO apps_fts(rowid, name, description) VALUES (new.id, new.name, new.description);
        END
    """)

    exec("""
        CREATE TRIGGER IF NOT EXISTS apps_fts_ad AFTER DELETE ON apps BEGIN
            INSERT INTO apps_fts(apps_fts, rowid, name, description) VALUES ('delete', old.id, old.name, old.description);
        END
    """)

    exec("""
        CREATE TRIGGER IF NOT EXISTS apps_fts_au AFTER UPDATE ON apps BEGIN
            INSERT INTO apps_fts(apps_fts, rowid, name, description) VALUES ('delete', old.id, old.name, old.description);
            INSERT INTO apps_fts(rowid, name, description) VALUES (new.id, new.name, new.description);
        END
    """)
}

/** Rebuild the FTS index from the apps table. */
private fun Transaction.rebuildFts() {
    exec("INSERT INTO apps_fts(apps_fts) VALUES ('rebuild')")
}
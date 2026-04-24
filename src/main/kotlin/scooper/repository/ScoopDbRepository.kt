package scooper.repository

import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.AppVersion
import scooper.data.AppVersionSource
import java.io.File
import java.sql.DriverManager

/**
 * Read-only repository for Scoop's built-in `scoop.db` SQLite database.
 * Provides historical version queries for P2.
 */
class ScoopDbRepository(
    private val scoopDbPath: File,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ScoopDbRepository::class.java)
    }

    fun isAvailable(): Boolean = scoopDbPath.exists() && scoopDbPath.isFile

    /** Query historical versions for an app. */
    fun getVersions(app: App): List<AppVersion> {
        if (!isAvailable()) return emptyList()
        if (app.bucket == null) return emptyList()

        return try {
            useConnection { conn ->
                conn.prepareStatement(
                    "SELECT version FROM app WHERE name = ? AND bucket = ? ORDER BY version DESC"
                ).use { stmt ->
                    stmt.setString(1, app.name)
                    stmt.setString(2, app.bucket!!.name)
                    stmt.executeQuery().use { rs ->
                        val versions = mutableListOf<AppVersion>()
                        while (rs.next()) {
                            versions.add(
                                AppVersion(
                                    version = rs.getString("version"),
                                    source = AppVersionSource.ScoopDb,
                                )
                            )
                        }
                        versions
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to query versions for ${app.uniqueName} from scoop.db: ${e.message}")
            emptyList()
        }
    }

    /** Read the manifest content for a specific version. */
    fun getManifest(app: App, version: String): String? {
        if (!isAvailable()) return null
        if (app.bucket == null) return null

        return try {
            useConnection { conn ->
                conn.prepareStatement(
                    "SELECT manifest FROM app WHERE name = ? AND bucket = ? AND version = ?"
                ).use { stmt ->
                    stmt.setString(1, app.name)
                    stmt.setString(2, app.bucket!!.name)
                    stmt.setString(3, version)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("manifest") else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to read manifest for ${app.uniqueName}@$version from scoop.db: ${e.message}")
            null
        }
    }

    private fun <T> useConnection(block: (java.sql.Connection) -> T): T {
        val url = "jdbc:sqlite:$scoopDbPath"
        return DriverManager.getConnection(url).use { conn ->
            conn.createStatement().executeUpdate("PRAGMA query_only = ON")
            block(conn)
        }
    }
}

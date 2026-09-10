package scooper.repository

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.db.Configs
import java.io.File

/**
 * TDD tests for [ConfigRepository] aiLastBucket / aiCommitToGit persistence:
 * the save dialog remembers the last used bucket and commit checkbox
 * across restarts via the configs table.
 */
internal class ConfigRepositoryTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            val databasePath = kotlin.io.path.createTempDirectory("scooper-config-test")
                .resolve("scooper-config-test.db").toFile()
            Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(Configs)
            }
        }
    }

    private fun freshConfig(): UIConfig {
        val repository = ConfigRepository()
        // Reset to defaults so tests stay independent of execution order.
        repository.setConfig(UIConfig())
        return repository.getConfig()
    }

    // ---------------------------------------------------------------
    // round-trip: setConfig -> getConfig keeps aiLastBucket
    // ---------------------------------------------------------------

    @Test
    fun `aiLastBucket round-trips through setConfig and getConfig`() {
        val repository = ConfigRepository()
        freshConfig()

        repository.setConfig(UIConfig(aiLastBucket = "extras"))
        assertEquals("extras", repository.getConfig().aiLastBucket)
    }

    @Test
    fun `aiCommitToGit round-trips through setConfig and getConfig`() {
        val repository = ConfigRepository()
        freshConfig()

        repository.setConfig(UIConfig(aiCommitToGit = false))
        assertFalse(repository.getConfig().aiCommitToGit)
    }

    // ---------------------------------------------------------------
    // changing aiLastBucket leaves every other field untouched
    // ---------------------------------------------------------------

    @Test
    fun `updating aiLastBucket does not change other fields`() {
        val repository = ConfigRepository()
        val before = freshConfig()

        repository.setConfig(before.copy(aiLastBucket = "games"))
        assertEquals(before.copy(aiLastBucket = "games"), repository.getConfig())
    }
}

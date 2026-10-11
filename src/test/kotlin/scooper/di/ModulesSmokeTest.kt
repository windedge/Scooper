package scooper.di

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import scooper.repository.db.Apps
import scooper.repository.db.Buckets
import scooper.repository.db.Configs
import scooper.service.ScoopAiBridge
import scooper.viewmodels.CreateTabController
import scooper.viewmodels.SettingsViewModel
import java.io.File
import kotlin.test.assertNotNull

/**
 * Koin wiring smoke test: boots the real production module graph and resolves
 * every definition plus the two UI entry points. Guards against definition
 * regressions (a deleted dependency such as the Settings bridge checkpoint
 * service) that unit tests miss because they construct viewmodels directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModulesSmokeTest {

    private lateinit var koin: Koin

    @BeforeAll
    fun boot() {
        // Same throwaway sqlite setup as AppsRepositoryTest / ConfigRepositoryTest:
        // SettingsViewModel resolves the real ConfigRepository, which touches the
        // Configs table in its container init block.
        val dbFile = File.createTempFile("scooper-modules-smoke-", ".db")
        dbFile.deleteOnExit()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Apps, Buckets, Configs)
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS apps_fts USING fts5(name, description, content='apps', content_rowid='id')")
        }
        koin = koinApplication { modules(system, viewModels) }.koin
    }

    @AfterAll
    fun shutdown() {
        // Both entry points are AutoCloseable and own background coroutine scopes.
        runCatching { koin.get<SettingsViewModel>().close() }
        runCatching { koin.get<CreateTabController>().close() }
        koin.close()
    }

    @Test
    fun `settings view model resolves through the production module graph`() {
        assertNotNull(koin.get<SettingsViewModel>())
    }

    @Test
    fun `create tab controller resolves through the production module graph`() {
        assertNotNull(koin.get<CreateTabController>())
    }

    @Test
    fun `settings bridge resolves with an inline checkpoint service`() {
        // Regression: ScoopAiBridge's checkpoint must be built inline because no
        // shared InMemoryAiCheckpointService singleton definition exists anymore.
        assertNotNull(koin.get<ScoopAiBridge>())
    }

    @Test
    fun `every definition in the loaded modules resolves`() {
        // Explicit inventory of the production graph: keeps the smoke honest even
        // if a definition is added without an entry-point test.
        val expectedTypes = listOf(
            scooper.service.ScoopLogStream::class,
            scooper.service.ScoopClient::class,
            scooper.taskqueue.TaskQueue::class,
            scooper.repository.ConfigRepository::class,
            scooper.service.GitHistoryService::class,
            scooper.service.GitHubService::class,
            scooper.service.ScoopSearchService::class,
            scooper.repository.AppsRepository::class,
            scooper.repository.CleanupRepository::class,
            scooper.service.ScoopService::class,
            scooper.service.AiConnectionTester::class,
            ScoopAiBridge::class,
            scooper.viewmodels.AppsViewModel::class,
            SettingsViewModel::class,
            scooper.viewmodels.ScoopSearchViewModel::class,
            scooper.viewmodels.CleanupViewModel::class,
            CreateTabController::class,
        )
        expectedTypes.forEach { clazz ->
            assertNotNull(koin.get(clazz), "Failed to resolve definition for ${clazz.qualifiedName}")
        }
    }
}

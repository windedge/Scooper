package scooper.viewmodels

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import scooper.repository.CleanupRepository
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import kotlin.test.assertTrue

class CleanupViewModelTest {

    companion object {
        @JvmStatic
        private val cleanupRepository = CleanupRepository(ScoopClient(ScoopLogStream(), TaskQueue()))

        @JvmStatic
        @BeforeAll
        fun setUpKoin() {
            startKoin {
                modules(org.koin.dsl.module {
                    single { cleanupRepository }
                    single { CleanupViewModel(get()) }
                })
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDownKoin() {
            stopKoin()
        }
    }

    private val cleanupViewModel: CleanupViewModel = GlobalContext.get().get()

    @Test
    fun notNull() {
        assertNotNull(cleanupViewModel)
    }

    @Test
    fun cacheSize() {
        val cacheSize = cleanupRepository.computeCacheSize()
        assertTrue(cacheSize >= 0)
    }

    @Test
    fun scanOldVersions() {
        // Result depends on the local scoop environment; just make sure it does not throw
        val oldVersions = cleanupRepository.scanOldVersions()
        assertNotNull(oldVersions)
    }
}

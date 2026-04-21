package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import scooper.repository.CleanupRepository
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.util.readableSize
import kotlin.test.assertTrue

class CleanupViewModelTest {

    private val scoopService = ScoopService(ScoopLogStream())
    private val cleanupRepository = CleanupRepository(scoopService)
    private val koinApp = startKoin {
        modules(org.koin.dsl.module {
            single { cleanupRepository }
            single { CleanupViewModel(get()) }
        })
    }
    private val cleanupViewModel = koinApp.koin.get<CleanupViewModel>()

    @Test
    fun notNull() {
        assertNotNull(cleanupViewModel)
    }

    @Test
    fun cacheSize() {
        val cacheSize = cleanupRepository.computeCacheSize()
        assertNotNull { cacheSize.readableSize() }
    }

    @Test
    fun scanOldVersions() {
        val oldVersions = cleanupRepository.scanOldVersions()
        println("oldVersions = $oldVersions")
        assertTrue { oldVersions.isNotEmpty() }
    }
}

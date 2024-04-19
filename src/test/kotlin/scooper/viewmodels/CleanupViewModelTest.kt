package scooper.viewmodels

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import scooper.di.viewModels
import scooper.util.Scoop
import scooper.util.readableSize
import kotlin.test.assertTrue

class CleanupViewModelTest {

    private val koinApp = startKoin { modules(viewModels) }
    private val cleanupViewModel = koinApp.koin.get<CleanupViewModel>()

    @Test
    fun notNull() {
        assertNotNull(cleanupViewModel)
    }

    @Test
    fun cacheSize() {
        val cacheSize = Scoop.computeCacheSize()
        assertNotNull { cacheSize.readableSize() }
    }

    @Test
    fun computeOldVersions() {
        val oldVersions = cleanupViewModel.scanOldVersions()
        println("oldVersions = ${oldVersions}")
        assertTrue { oldVersions.isNotEmpty() }

        runBlocking {
            cleanupViewModel.computeOldVersions()
            cleanupViewModel.container.stateFlow.collectLatest {
                it.totalOldSize > 0
            }

        }
    }
}

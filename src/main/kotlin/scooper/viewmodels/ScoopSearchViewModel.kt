package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.service.ScoopSearchApp
import scooper.service.ScoopSearchService
import scooper.service.ScoopSearchSort
import scooper.util.logger

data class ScoopSearchState(
    val query: String = "",
    val results: List<ScoopSearchApp> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 0,
    val pageSize: Int = 20,
    val searching: Boolean = false,
    val initialLoaded: Boolean = false,
    val sort: ScoopSearchSort = ScoopSearchSort.BestMatch,
    val officialOnly: Boolean = false,
    val distinctOnly: Boolean = true,
    val showBucketName: Boolean = true,
    val errorMessage: String? = null,
)

class ScoopSearchViewModel(
    private val searchService: ScoopSearchService,
) : ContainerHost<ScoopSearchState, ScoopSearchSideEffect>, AutoCloseable {
    private val logger by logger()
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    override val container: Container<ScoopSearchState, ScoopSearchSideEffect> =
        coroutineScope.container(ScoopSearchState())

    fun onSearch(query: String) = intent {
        // Snapshot current options before any state change
        val options = SearchOptions(state.sort, state.officialOnly, state.distinctOnly)
        val pageSize = state.pageSize
        if (query.isBlank()) {
            reduce { state.copy(query = query, results = emptyList(), totalCount = 0, searching = false, initialLoaded = false, errorMessage = null) }
            return@intent
        }
        reduce { state.copy(query = query, searching = true, errorMessage = null) }
        doSearchFirstPage(query, pageSize, options)
    }

    fun setSort(sort: ScoopSearchSort) = intent {
        updateOptionsAndSearch(state.copy(sort = sort))
    }

    fun setOfficialOnly(officialOnly: Boolean) = intent {
        updateOptionsAndSearch(state.copy(officialOnly = officialOnly))
    }

    fun setDistinctOnly(distinctOnly: Boolean) = intent {
        updateOptionsAndSearch(state.copy(distinctOnly = distinctOnly))
    }

    fun setShowBucketName(showBucketName: Boolean) = intent {
        reduce { state.copy(showBucketName = showBucketName) }
    }

    fun loadMore() = intent {
        if (state.searching) return@intent
        if (state.results.size >= state.totalCount && state.initialLoaded) return@intent
        if (state.query.isBlank()) return@intent
        val nextPage = state.currentPage + 1
        val options = SearchOptions(state.sort, state.officialOnly, state.distinctOnly)
        reduce { state.copy(searching = true) }
        try {
            val result = searchService.search(state.query, nextPage, state.pageSize, options.sort, options.officialOnly, options.distinctOnly)
            reduce {
                state.copy(
                    results = state.results + result.value,
                    totalCount = result.count,
                    currentPage = nextPage,
                    searching = false,
                )
            }
        } catch (e: Exception) {
            logger.error("Search failed", e)
            reduce { state.copy(searching = false, errorMessage = formatError(e)) }
        }
    }

    /**
     * Apply option change, then re-search first page if there's an active query.
     */
    private suspend fun org.orbitmvi.orbit.syntax.simple.SimpleSyntax<ScoopSearchState, ScoopSearchSideEffect>.updateOptionsAndSearch(
        partialState: ScoopSearchState,
    ) {
        val query = state.query
        val pageSize = state.pageSize
        val options = SearchOptions(partialState.sort, partialState.officialOnly, partialState.distinctOnly)
        reduce { partialState }
        if (query.isBlank()) return
        reduce { state.copy(searching = true, errorMessage = null) }
        doSearchFirstPage(query, pageSize, options)
    }

    private suspend fun org.orbitmvi.orbit.syntax.simple.SimpleSyntax<ScoopSearchState, ScoopSearchSideEffect>.doSearchFirstPage(
        query: String,
        pageSize: Int,
        options: SearchOptions,
    ) {
        try {
            val result = searchService.search(query, 0, pageSize, options.sort, options.officialOnly, options.distinctOnly)
            reduce {
                state.copy(
                    results = result.value,
                    totalCount = result.count,
                    currentPage = 0,
                    searching = false,
                    initialLoaded = true,
                )
            }
        } catch (e: Exception) {
            logger.error("Search failed", e)
            reduce {
                state.copy(
                    searching = false,
                    results = emptyList(),
                    totalCount = 0,
                    currentPage = 0,
                    initialLoaded = true,
                    errorMessage = formatError(e),
                )
            }
        }
    }

    override fun close() {
        supervisorJob.cancel()
        searchService.close()
    }

    private fun formatError(e: Exception): String {
        return when (e) {
            is java.net.ConnectException -> "Unable to connect to search server. Please check your network."
            is java.net.UnknownHostException -> "Unable to reach search server. Please check your network."
            is java.net.SocketTimeoutException -> "Search server timed out. Please try again."
            is javax.net.ssl.SSLException -> "Connection to search server failed. Please try again."
            else -> e.message ?: "Search failed. Please try again."
        }
    }

    private data class SearchOptions(
        val sort: ScoopSearchSort,
        val officialOnly: Boolean,
        val distinctOnly: Boolean,
    )
}

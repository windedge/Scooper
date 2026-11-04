package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import scooper.repository.AppsRepository
import scooper.service.AiSettingsStore
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import java.util.UUID

/** Stable identity of one Create tab: the VM behind it and its display title. */
data class CreateTab(
    val tabId: String,
    val viewModel: CreateViewModel,
    /** Tab bar label: "New Chat" until a run names the session. */
    val title: String = DEFAULT_TAB_TITLE,
) {
    companion object {
        const val DEFAULT_TAB_TITLE = "New Chat"
    }
}

/**
 * Immutable snapshot for the tab bar. [composerPrefill] is non-null while a
 * Discover handoff text waits to be consumed by the composer.
 */
data class CreateTabUiState(
    val tabs: List<CreateTabInfo> = emptyList(),
    val activeTabId: String? = null,
    /** True while any tab's LLM generation is running, including background tabs. */
    val anyRunning: Boolean = false,
)

data class CreateTabInfo(
    val tabId: String,
    val title: String,
    /** True when the tab holds an unsaved manifest or a busy run. */
    val guardClose: Boolean,
    /** Non-null while a Discover handoff text waits to be consumed. */
    val composerPrefill: String? = null,
)

/**
 * Owns one CreateViewModel per tab. Every tab gets its own
 * [ScoopAiBridge] + [InMemoryAiCheckpointService] pair via the injected
 * factories, so concurrent tab runs no longer cancel each other on the
 * bridge's single job slot; app-wide singleton dependencies (scoop client,
 * repositories, services) are still shared.
 */
class CreateTabController(
    private val scoopClient: ScoopClient,
    private val appsRepository: AppsRepository,
    private val gitHistoryService: GitHistoryService,
    private val scoopService: scooper.service.ScoopService? = null,
    /** Creates a fresh per-tab checkpoint service (never shared). */
    private val checkpointFactory: () -> InMemoryAiCheckpointService,
    /** Wraps a per-tab checkpoint service into the tab's own bridge. */
    private val bridgeFactory: (InMemoryAiCheckpointService) -> ScoopAiBridge,
    /** App-wide settings seam shared by every tab's viewmodel. */
    private val aiSettingsStore: AiSettingsStore? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableUiState = MutableStateFlow(CreateTabUiState())
    private val uiStateFlow: StateFlow<CreateTabUiState> = mutableUiState.asStateFlow()

    private val tabs = mutableListOf<CreateTab>()
    private var activeId: String? = null
    private val tabWatchers = mutableMapOf<String, Job>()
    private val prefills = mutableMapOf<String, String>()
    private val handoffTitles = mutableMapOf<String, String>()

    init {
        addTabInternal()
        rebuild()
    }

    val uiState: StateFlow<CreateTabUiState> get() = uiStateFlow

    /** The tab's own bridge; each tab owns a dedicated instance. */
    internal fun bridgeFor(tabId: String): ScoopAiBridge {
        val tab = tabs.firstOrNull { it.tabId == tabId }
            ?: throw IllegalArgumentException("Unknown Create tab: $tabId")
        return tab.viewModel.scoopAiBridge
    }

    fun activeViewModel(): CreateViewModel =
        tabs.firstOrNull { it.tabId == activeId }?.viewModel ?: tabs.last().viewModel

    fun activeTabId(): String = activeId ?: tabs.last().tabId

    /** Opens a fresh blank session tab and makes it active. */
    fun createTab() {
        addTabInternal()
        rebuild()
    }

    /** Switches the active tab; unknown ids are ignored. */
    fun selectTab(tabId: String) {
        if (tabs.none { it.tabId == tabId }) return
        activeId = tabId
        rebuild()
    }

    /**
     * Close request from the tab bar. Closes right away; the close chain
     * (watcher cancel + VM close + bridge close) also cancels a busy run.
     */
    fun closeTabRequest(tabId: String) {
        closeTabInternal(tabId)
    }

    /**
     * Discover "Chat with AI" handoff (batch 1 seam): reuse the active tab
     * while it is still blank, otherwise open a new tab, prefill its composer
     * with `@bucket/app` plus a trailing space, and make it active. The space
     * terminates the @ token so the caret after it does not match
     * [activeManifestRefToken] and the completion menu stays closed.
     */
    fun openModifySession(bucket: String, name: String) {
        val ref = "@${bucket.trim().trimStart('@')}/${name.trim()} "
        val reusable = tabs.firstOrNull { it.viewModel.isBlankForHandoff }
        val target = if (reusable != null) {
            reusable
        } else {
            addTabInternal()
            tabs.last()
        }
        prefills[target.tabId] = ref
        handoffTitles[target.tabId] = name.trim()
        // Seed the referenced name into the VM so the tab title and the engine's
        // assist calls carry it; without it the finished-run fallback chain
        // would derive the name from a blank lastUrl.
        target.viewModel.seedSuggestedAppName(name)
        activeId = target.tabId
        rebuild()
    }

    /** Clears the pending prefill after the composer has picked it up. */
    fun consumeComposerPrefill(tabId: String) {
        if (prefills.remove(tabId) != null) rebuild()
    }

    override fun close() {
        tabs.forEach { tab ->
            tab.viewModel.close()
            tab.viewModel.scoopAiBridge.close()
        }
        tabs.clear()
        tabWatchers.values.forEach { it.cancel() }
        tabWatchers.clear()
        scope.cancel()
    }

    // ---------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------

    private fun addTabInternal() {
        // Fresh bridge + checkpoint per tab: the bridge owns a single generate
        // job slot, so sharing one across tabs made tab B's start cancel tab A's run.
        val checkpoint = checkpointFactory()
        val bridge = bridgeFactory(checkpoint)
        val vm = CreateViewModel(
            scoopAiBridge = bridge,
            // All tabs share the injected app-wide store; only the bridge (run
            // slot) and checkpoint are per-tab.
            aiSettingsStore = aiSettingsStore,
            checkpointService = checkpoint,
            scoopClient = scoopClient,
            appsRepository = appsRepository,
            gitHistoryService = gitHistoryService,
            scoopService = scoopService,
        )
        val tab = CreateTab(tabId = UUID.randomUUID().toString(), viewModel = vm)
        tabs.add(tab)
        activeId = tab.tabId
        watchTab(tab)
    }

    private fun watchTab(tab: CreateTab) {
        tabWatchers[tab.tabId] = scope.launch {
            launch {
                tab.viewModel.container.stateFlow.collect {
                    rebuild()
                }
            }
            launch {
                tab.viewModel.container.sideEffectFlow.collect { sideEffect ->
                    sharedSideEffects.emit(sideEffect)
                }
            }
        }
    }

    /** Shared side-effect stream merging every tab's VM. */
    val sideEffectFlow: SharedFlow<AppSideEffect> get() = sharedSideEffects

    private val sharedSideEffects = MutableSharedFlow<AppSideEffect>(extraBufferCapacity = 64)

    private fun closeTabInternal(tabId: String) {
        val index = tabs.indexOfFirst { it.tabId == tabId }
        if (index < 0) return
        tabs.getOrNull(index)?.let { tab ->
            tabWatchers.remove(tabId)?.cancel()
            tab.viewModel.close()
            tab.viewModel.scoopAiBridge.close()
        }
        tabs.removeAt(index)
        prefills.remove(tabId)
        handoffTitles.remove(tabId)
        if (tabs.isEmpty()) {
            // A Create page always shows at least one tab; the last close
            // resets to a single blank session instead of an empty bar.
            addTabInternal()
        } else if (activeId == tabId) {
            activeId = tabs.getOrNull(index)?.tabId
                ?: tabs.getOrNull(index - 1)?.tabId
        }
        rebuild()
    }

    private fun rebuild() {
        // Toast gate: every tab but the active one renders silently. Done in
        // rebuild because every active-tab change funnels through here.
        tabs.forEach { it.viewModel.isBackgroundTab = it.tabId != activeTabId() }
        val infos = tabs.map { tab ->
            CreateTabInfo(
                tabId = tab.tabId,
                title = tab.viewModel.tabTitleCandidate(handoffTitles[tab.tabId])
                    ?: CreateTab.DEFAULT_TAB_TITLE,
                guardClose = tab.viewModel.hasUnsavedWork || tab.viewModel.isBusy,
                composerPrefill = prefills[tab.tabId],
            )
        }
        mutableUiState.value = mutableUiState.value.copy(
            tabs = infos,
            activeTabId = activeId,
            anyRunning = tabs.any { it.viewModel.container.stateFlow.value.running },
        )
    }
}

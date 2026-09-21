@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue

/**
 * TDD tests for the Open button wiring: [CreateViewModel.onOpenApp] launches the
 * installed app through [ScoopClient.openApp], guarded by [CreateState.installedForUse]
 * and reports failures as an ERROR toast. The installed app name resolves through
 * [resolveOpenAppName]: the save result first, then the direct-install record name,
 * then the URL tail; an unresolvable name toasts instead of staying silent.
 * The VM is built from fake repositories/services following the
 * CreateViewModelDismissTestResultTest pattern; failure is exercised with a
 * guaranteed-missing shortcut name so no real app can be launched.
 */
class CreateViewModelOpenAppTest {

    // ---------------------------------------------------------------------
    // Guards
    // ---------------------------------------------------------------------

    @Test
    fun `onOpenApp does nothing when the manifest is not installed for use`() = runBlocking {
        val vm = createViewModel(installed = false)
        awaitStable(vm)
        val before = vm.container.stateFlow.value

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.onOpenApp().join() }
        }

        val after = vm.container.stateFlow.value
        assertEquals(before.installedForUse, after.installedForUse)
        assertEquals(before.saveResult, after.saveResult)
        assertEquals(before.installedShortcuts, after.installedShortcuts)
        assertEquals(before.installing, after.installing)
        vm.close()
    }

    @Test
    fun `onOpenApp does nothing when saveResult is missing`() = runBlocking {
        val vm = createViewModel(installed = true, withSaveResult = false)
        awaitStable(vm)
        val before = vm.container.stateFlow.value

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.onOpenApp().join() }
        }

        val after = vm.container.stateFlow.value
        assertTrue(after.installedForUse, "precondition: installed")
        assertEquals(before.saveResult, after.saveResult)
        assertEquals(before.installedShortcuts, after.installedShortcuts)
        assertEquals(before.installing, after.installing)
        vm.close()
    }

    // ---------------------------------------------------------------------
    // Failure path
    // ---------------------------------------------------------------------

    @Test
    fun `onOpenApp toasts an error when the shortcut is missing`() = runBlocking {
        // A name that cannot exist under the real Scoop Apps folder, so openApp
        // fails deterministically without launching anything.
        val missing = "scooper-open-app-missing-${System.nanoTime()}"
        val vm = createViewModel(installed = true, appName = missing)
        val toasts = collectToasts(vm) { vm.onOpenApp().join() }
        val errorToasts = toasts.filter { it.type == ToastType.ERROR }
        assertTrue(errorToasts.isNotEmpty(), "expected an ERROR toast, got: $toasts")
        assertTrue(
            errorToasts.any { it.text.contains(missing) },
            "error toast must mention the app name, got: $toasts",
        )
        vm.close()
    }

    // ---------------------------------------------------------------------
    // Installed name resolution
    // ---------------------------------------------------------------------

    @Test
    fun `resolveOpenAppName prefers the save result over the installed name`() {
        val state = CreateState(
            saveResult = SaveResult(
                success = true,
                appName = "saved-name",
                bucket = "main",
                targetPath = "C:/bucket/main/saved-name.json",
                overwritten = false,
                indexedOk = true,
            ),
            suggestedAppName = "other",
        )
        assertEquals("saved-name", resolveOpenAppName(state, lastUrl = "https://github.com/foo/bar"))
    }

    @Test
    fun `resolveOpenAppName prefers the recorded direct install name over the suggested name`() {
        // Direct installs never set saveResult; the recorded install name is
        // what was actually installed, so it outranks the suggestion.
        val state = CreateState(directInstallName = "recorded", suggestedAppName = "other")
        assertEquals("recorded", resolveOpenAppName(state, lastUrl = "https://github.com/foo/ignored"))
    }

    @Test
    fun `resolveOpenAppName falls back to the suggested name when the direct install name is blank`() {
        val state = CreateState(directInstallName = "  ", suggestedAppName = "directapp")
        assertEquals("directapp", resolveOpenAppName(state, lastUrl = "https://github.com/foo/ignored"))
    }

    @Test
    fun `resolveOpenAppName falls back to the url tail as a last resort`() {
        val state = CreateState(suggestedAppName = null, lastUrl = "https://github.com/user/ripgrep")
        assertEquals("ripgrep", resolveOpenAppName(state, lastUrl = "https://github.com/user/ripgrep"))
    }

    @Test
    fun `resolveOpenAppName returns null when nothing can be derived`() {
        val state = CreateState(suggestedAppName = null, lastUrl = "")
        assertEquals(null, resolveOpenAppName(state, lastUrl = ""))
    }

    // ---------------------------------------------------------------------
    // Silent-no-op regression: unresolvable name must toast, not vanish
    // ---------------------------------------------------------------------

    @Test
    fun `onOpenApp toasts an error when no installed name can be resolved`() = runBlocking {
        val vm = createViewModel(installed = true, withSaveResult = false)
        awaitStable(vm)
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce { state.copy(suggestedAppName = null, lastUrl = "") }
                    }
                }
            }
        }
        val toasts = collectToasts(vm) { vm.onOpenApp().join() }
        assertTrue(
            toasts.any { it.type == ToastType.ERROR },
            "expected an ERROR toast when the app name cannot be resolved, got: $toasts",
        )
        vm.close()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Collects Toast side effects emitted while [block] runs plus a drain window. */
    private suspend fun collectToasts(
        vm: CreateViewModel,
        block: suspend () -> Unit,
    ): List<AppSideEffect.Toast> {
        val collected = mutableListOf<AppSideEffect.Toast>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            vm.container.sideEffectFlow.collect { effect ->
                if (effect is AppSideEffect.Toast) collected.add(effect)
            }
        }
        // Give the collector a moment to subscribe before the intent runs.
        withContext(Dispatchers.Default) { delay(100) }
        try {
            block()
            // Toast emission is launched on the container scope, so joining the
            // intent is not enough; drain until the toast lands or timeout.
            val deadline = System.currentTimeMillis() + 5_000
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Default) { delay(20) }
            }
        } finally {
            job.cancel()
        }
        return collected
    }

    /** Waits until the VM init block (refreshAiConfig) has settled the state. */
    private suspend fun awaitStable(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            var last = vm.container.stateFlow.value
            var sameCount = 0
            val deadline = System.currentTimeMillis() + 5_000
            while (sameCount < 3 && System.currentTimeMillis() < deadline) {
                delay(50)
                val current = vm.container.stateFlow.value
                if (current == last) sameCount++ else sameCount = 0
                last = current
            }
        }
    }

    private fun createViewModel(
        installed: Boolean,
        withSaveResult: Boolean = true,
        appName: String = "ripgrep",
    ): CreateViewModel {
        val scoopClient = ScoopClient(ScoopLogStream(), TaskQueue())
        val vm = CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = AppsRepository(
                scoopClient = scoopClient,
                gitHistoryService = GitHistoryService(),
            ),
            gitHistoryService = GitHistoryService(),
        )
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                installedForUse = installed,
                                saveResult = if (withSaveResult) {
                                    SaveResult(
                                        success = true,
                                        appName = appName,
                                        bucket = "main",
                                        targetPath = "C:/bucket/main/$appName.json",
                                        overwritten = false,
                                        indexedOk = true,
                                    )
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
        return vm
    }

    private class FakeConfigRepository : ConfigRepository() {
        private var config = UIConfig()
        override fun getConfig(): UIConfig = config
        override fun setConfig(config: UIConfig) {
            this.config = config
        }
    }
}

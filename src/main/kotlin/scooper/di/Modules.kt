package scooper.di

import org.koin.dsl.bind
import org.koin.dsl.module
import scooper.repository.AppsRepository
import scooper.repository.CleanupRepository
import scooper.repository.ConfigRepository
import scooper.service.AiConnectionTester
import scooper.service.GitHistoryService
import scooper.service.GitHubService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopCli
import scooper.service.ScoopLogStream
import scooper.service.ScoopSearchService
import scooper.service.ScoopService
import scooper.service.ScoopClient
import scooper.taskqueue.TaskQueue
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.CreateTabController
import scooper.viewmodels.ScoopSearchViewModel
import scooper.viewmodels.SettingsViewModel

val system = module {
    single { ScoopLogStream() }
    single { ScoopClient(get(), get()) } bind ScoopCli::class
    single { TaskQueue() }
    single { ConfigRepository() }
    single { GitHistoryService() }
    single { GitHubService() }
    single { ScoopSearchService() }
    single { AppsRepository(get(), get()) }
    single { CleanupRepository(get()) }
    single { ScoopService(get(), get()) }
    single { AiConnectionTester() }
    // Kept as a singleton for Settings.AI; Create tabs build their own
    // bridges (and checkpoint services) per tab via CreateTabController.
    // Checkpoint service is built inline: no shared singleton definition exists.
    single { ScoopAiBridge(get(), InMemoryAiCheckpointService(), get()) }
}

val viewModels = module {
    single { AppsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SettingsViewModel(get(), get()) }
    single { ScoopSearchViewModel(get(), get<ScoopClient>(), get(), get(), get()) }
    single { CleanupViewModel(get()) }
    single {
        CreateTabController(
            scoopClient = get(),
            appsRepository = get(),
            gitHistoryService = get(),
            scoopService = get(),
            checkpointFactory = { InMemoryAiCheckpointService() },
            bridgeFactory = { cp -> ScoopAiBridge(get(), cp, get()) },
        )
    }
}

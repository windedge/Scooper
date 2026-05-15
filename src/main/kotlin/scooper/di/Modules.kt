package scooper.di

import org.koin.dsl.bind
import org.koin.dsl.module
import scooper.repository.AppsRepository
import scooper.repository.CleanupRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.GitHubService
import scooper.service.ScoopCli
import scooper.service.ScoopLogStream
import scooper.service.ScoopSearchService
import scooper.service.ScoopService
import scooper.service.ScoopClient
import scooper.taskqueue.TaskQueue
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
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
}

val viewModels = module {
    single { AppsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SettingsViewModel(get()) }
    single { ScoopSearchViewModel(get(), get<ScoopClient>(), get(), get(), get()) }
    single { CleanupViewModel(get()) }
}

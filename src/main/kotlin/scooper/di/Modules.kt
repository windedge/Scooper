package scooper.di

import org.koin.dsl.bind
import org.koin.dsl.module
import scooper.repository.AppsRepository
import scooper.repository.CleanupRepository
import scooper.repository.ConfigRepository
import scooper.service.ScoopCli
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.taskqueue.TaskQueue
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.SettingsViewModel

val system = module {
    single { ScoopLogStream() }
    single { ScoopService(get(), get()) } bind ScoopCli::class
    single { TaskQueue() }
    single { ConfigRepository() }
    single { AppsRepository(get()) }
    single { CleanupRepository(get()) }
}

val viewModels = module {
    single { AppsViewModel(get(), get(), get(), get(), get()) }
    single { SettingsViewModel(get()) }
    single { CleanupViewModel(get()) }
}

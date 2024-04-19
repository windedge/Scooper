package scooper.di

import org.koin.dsl.module
import scooper.taskqueue.TaskQueue
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.SettingsViewModel

val system = module {
    single { TaskQueue() }
}

val viewModels = module {
    single { AppsViewModel(get()) }
    single { SettingsViewModel() }
    single { CleanupViewModel() }
}
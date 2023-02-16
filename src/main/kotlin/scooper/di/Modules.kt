package scooper.di

import org.koin.dsl.module
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.SettingsViewModel

val viewModelsModule = module {
    single { AppsViewModel() }
    single { SettingsViewModel() }
    single { CleanupViewModel() }
}
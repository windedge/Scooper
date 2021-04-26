package scooper.di

import org.koin.dsl.module
import scooper.viewmodels.AppsViewModel

val viewModelsModule = module {
    single { AppsViewModel() }
}
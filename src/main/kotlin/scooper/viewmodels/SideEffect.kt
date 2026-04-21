package scooper.viewmodels

sealed class AppsSideEffect {
    data class Toast(val text: String) : AppsSideEffect()
    data class Log(val text: String) : AppsSideEffect()
}

sealed class SettingsSideEffect {
    data class Toast(val text: String) : SettingsSideEffect()
}

sealed class CleanupSideEffect {
    data class Toast(val text: String) : CleanupSideEffect()
}

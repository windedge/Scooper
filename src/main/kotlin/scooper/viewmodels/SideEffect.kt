package scooper.viewmodels

import scooper.ui.AppRoute

sealed class SideEffect {
    object Empty : SideEffect()
    object Loading : SideEffect()
    object Done : SideEffect()
    data class Toast(val text: String) : SideEffect()
    data class Log(val text: String) : SideEffect()
    data class Route(val route: AppRoute) : SideEffect()
}
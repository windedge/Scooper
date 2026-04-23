package scooper.ui

sealed class AppRoute {
    data class Apps(val scope: String) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Cleanup : AppRoute()
    sealed class Settings(val menuText: String) : AppRoute() {
        object General : Settings("General")
        object UI : Settings("UI")
        object About : Settings("About")
    }

    object Output : AppRoute()
}
package scooper.ui

sealed class AppRoute {
    data class Apps(val scope: String) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Cleanup : AppRoute()
    object ScoopSearch : AppRoute()
    sealed class Settings(val menuText: String) : AppRoute() {
        object General : Settings("general")
        object UI : Settings("ui")
        object About : Settings("about")
    }

    object Output : AppRoute()
}
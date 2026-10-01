package scooper.ui

sealed class AppRoute {
    data class Apps(val scope: String, val bucket: String? = null) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Cleanup : AppRoute()
    object ScoopSearch : AppRoute()
    object Create : AppRoute()
    sealed class Settings(val menuText: String) : AppRoute() {
        object General : Settings("General")
        object UI : Settings("UI")
        object AI : Settings("AI")
        object About : Settings("About")
    }

    object Output : AppRoute()
}
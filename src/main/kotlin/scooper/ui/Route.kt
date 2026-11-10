package scooper.ui

import scooper.util.Translatable
import scooper.util.tr

sealed class AppRoute {
    data class Apps(val scope: String, val bucket: String? = null) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Cleanup : AppRoute()
    object ScoopSearch : AppRoute()
    object Create : AppRoute()
    sealed class Settings(val menuText: String) : AppRoute(), Translatable {
        object General : Settings("general")
        object UI : Settings("ui")
        object AI : Settings("ai")
        object About : Settings("about")

        override fun displayName(): String = when (this) {
            General -> tr("General")
            UI -> tr("UI")
            AI -> tr("AI")
            About -> tr("About")
        }
    }

    object Output : AppRoute()
}
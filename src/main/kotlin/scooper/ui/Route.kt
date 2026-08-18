package scooper.ui

import scooper.util.Translatable
import scooper.util.tr

sealed class AppRoute {
    data class Apps(val scope: String) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Cleanup : AppRoute()
    object ScoopSearch : AppRoute()
    sealed class Settings(val menuText: String) : AppRoute(), Translatable {
        object General : Settings("general")
        object UI : Settings("ui")
        object About : Settings("about")

        override fun displayName(): String = when (this) {
            General -> tr("General")
            UI -> tr("UI")
            About -> tr("About")
        }
    }

    object Output : AppRoute()
}
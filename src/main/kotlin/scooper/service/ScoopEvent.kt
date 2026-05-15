package scooper.service

sealed interface ScoopEvent {
    data class AppInstalled(val appName: String) : ScoopEvent
    data class AppUninstalled(val appName: String) : ScoopEvent
    data class AppUpdated(val appName: String) : ScoopEvent
    data class AppDownloaded(val appName: String) : ScoopEvent

    data class BucketAdded(val bucketName: String) : ScoopEvent
    data class BucketRemoved(val bucketName: String) : ScoopEvent

    data object AppsReloaded : ScoopEvent
    data object BucketsReloaded : ScoopEvent
    data object Reloaded : ScoopEvent
}

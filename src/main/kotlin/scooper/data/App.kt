package scooper.data

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
data class ShortCut(
    val title: String,
    val path: String,
)

enum class AppStatus {
    INSTALLED,
    UNINSTALL,
    FAILED;

    /** Lowercase string for Exposed storage and JSON serialization */
    override fun toString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): AppStatus =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: UNINSTALL
    }
}

data class App(
    val name: String,
    val latestVersion: String,
    val version: String? = null,
    val global: Boolean = false,
    val status: AppStatus = AppStatus.UNINSTALL,
    val description: String? = null,
    val url: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null,
    val bucket: Bucket? = null,
    val shortcuts: List<ShortCut>? = null,
    val createAt: LocalDateTime? = null,
    val updateAt: LocalDateTime? = null,
) {
    val installed: Boolean
        get() = this.status == AppStatus.INSTALLED

    val updatable: Boolean
        get() = this.status == AppStatus.INSTALLED
                && this.version != null
                && this.version != this.latestVersion

    val uniqueName: String
        get() = if (this.bucket != null) "${this.bucket!!.name}/${this.name}" else this.name

    val hasShortcuts: Boolean
        get() = !shortcuts.isNullOrEmpty()
}

data class Bucket(
    val name: String,
    val url: String? = null
)

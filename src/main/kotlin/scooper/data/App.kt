package scooper.data

import java.time.LocalDateTime

data class App(
    var name: String,
    var latestVersion: String,
    var version: String? = null,
    var global: Boolean = false,
    var status: String = "uninstall",
    var description: String? = null,
    var url: String? = null,
    var homepage: String? = null,
    var license: String? = null,
    var licenseUrl: String? = null,
    var bucket: Bucket? = null,
) {

    lateinit var createAt: LocalDateTime
    lateinit var updateAt: LocalDateTime

    val updatable: Boolean get() = this.version != null && this.version != this.latestVersion
    val installed: Boolean get() = this.status == "installed"
}


data class Bucket(
    val name: String,
    val url: String? = null
)

data class Setting(
    val proxy: String = "",
    val ariaEnable: Boolean = false
)
package scooper.data

import java.time.LocalDateTime

data class App(
    var name: String,
    var version: String,
    var global: Boolean = false,
    var installed: Boolean = false,
    var description: String? = null,
    var url: String? = null,
    var homepage: String? = null,
    var license: String? = null,
    var licenseUrl: String? = null,

) {
    lateinit var createAt: LocalDateTime
    lateinit var updateAt: LocalDateTime

    lateinit var bucket: Bucket
}


data class Bucket(
    val name: String,
    val url: String? = null
)
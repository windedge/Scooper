package scooper.repository.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime
import scooper.data.AppStatus
import scooper.data.Theme

object Apps : IntIdTable("apps") {
    val name = varchar("name", 1000)
    val version = varchar("version", 1000).nullable()
    val latestVersion = varchar("latest_version", 1000)
    val bucketId = reference("bucket_id", Buckets, onDelete = ReferenceOption.SET_NULL).nullable()
    val global = bool("global").default(false)
    val status = customEnumeration(
        "status", "VARCHAR(32)",
        { value -> AppStatus.fromString(value as String) },
        { value -> (value as AppStatus).name.lowercase() }
    ).default(AppStatus.UNINSTALL)
    val description = varchar("description", 5000).nullable()
    val url = text("url").nullable()
    val homepage = text("homepage").nullable()
    val license = varchar("license", 1000).nullable()
    val licenseUrl = text("license_url").nullable()
    val createAt = datetime("create_at")
    val updateAt = datetime("update_at")

    init {
        index(isUnique = true, name, bucketId)
    }
}

object Buckets : IntIdTable("buckets") {
    val name = varchar("name", 1000)
    val url = text("url").nullable()
}

object Configs : IntIdTable(name = "configs") {
    val refreshOnStartup = bool("refresh_on_startup").default(false)
    val theme = customEnumeration(
        "theme", "VARCHAR(32)",
        { value -> Theme.entries.find { it.name.equals(value as String, ignoreCase = true) } ?: Theme.Auto },
        { value -> (value as Theme).name }
    ).default(Theme.Auto)
}

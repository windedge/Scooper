package scooper.repository.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime
import scooper.data.AppStatus
import scooper.data.PaginationMode
import scooper.data.Theme
import scooper.data.ViewMode

object Apps : IntIdTable("apps") {
    val name = varchar("name", 1000)
    val version = varchar("version", 1000).nullable()
    val latestVersion = varchar("latest_version", 1000)
    val bucketId = reference("bucket_id", Buckets, onDelete = ReferenceOption.SET_NULL).nullable()
    val global = bool("global").default(false)
    val status = varchar("status", 20).default(AppStatus.UNINSTALL.name.lowercase())
    val description = varchar("description", 5000).nullable()
    val url = text("url").nullable()
    val homepage = text("homepage").nullable()
    val license = varchar("license", 1000).nullable()
    val licenseUrl = text("license_url").nullable()
    val shortcuts = text("shortcuts").nullable()
    val createAt = datetime("create_at")
    val updateAt = datetime("update_at")

    init {
        index(isUnique = true, name, bucketId)
    }
}

object Buckets : IntIdTable("buckets") {
    val name = varchar("name", 1000)
    val url = text("url").nullable()
    val lastIndexedCommit = varchar("last_indexed_commit", 80).nullable()
}

object Configs : IntIdTable(name = "configs") {
    val refreshOnStartup = bool("refresh_on_startup").default(false)
    val theme = enumeration("theme", Theme::class).default(Theme.Auto)
    val fontSizeScale = float("font_size_scale").default(1.0f)
    val viewMode = enumeration("view_mode", ViewMode::class).default(ViewMode.List)
    val paginationMode = enumeration("pagination_mode", PaginationMode::class).default(PaginationMode.Waterfall)
    val pageSize = integer("page_size").default(25)
    val windowX = integer("window_x").nullable()
    val windowY = integer("window_y").nullable()
    val windowWidth = integer("window_width").default(960)
    val windowHeight = integer("window_height").default(600)
    val isMaximized = bool("is_maximized").default(false)
}

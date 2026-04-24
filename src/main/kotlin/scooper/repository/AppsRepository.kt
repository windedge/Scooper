package scooper.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.repository.db.AppEntity
import scooper.repository.db.Apps
import scooper.repository.db.BucketEntity
import scooper.repository.db.Buckets
import scooper.service.ScoopService
import scooper.util.PAGE_SIZE
import scooper.util.ScooperException

data class PaginatedResult<T>(
    val value: List<T>,
    val totalCount: Long
)

class AppsRepository(
    private val scoopService: ScoopService,
) {
    fun getBuckets(): List<Bucket> = transaction {
        BucketEntity.all().map { Bucket(name = it.name, url = it.url) }
    }

    fun getApps(
        query: String = "",
        bucket: String = "",
        scope: String = "all",
        offset: Long = 0L,
        limit: Int = PAGE_SIZE,
        sort: String = "updated",
        sortOrder: String = "desc"
    ): PaginatedResult<App> = transaction {
        val conditions = Apps.leftJoin(Buckets).selectAll()
        if (query.isNotBlank()) {
            val words = query.trim().split(" ")
            for (word in words) {
                val escaped = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                conditions.andWhere { Apps.name like "%$escaped%" or (Apps.description match "%$escaped%") }
            }
        }
        if (bucket.isNotBlank()) {
            conditions.andWhere { Buckets.name eq bucket }
        }
        
        val installedStr = AppStatus.INSTALLED.name.lowercase()
        if (scope == installedStr) {
            conditions.andWhere { Apps.status eq installedStr }
        } else if (scope == "updates") {
            conditions.andWhere { Apps.status eq installedStr and (Apps.version neq Apps.latestVersion) }
        }

        val wrapRows = AppEntity.wrapRows(conditions)
        val totalCount = wrapRows.count()

        val column = when (sort) {
            "name" -> Apps.name
            "added" -> Apps.createAt
            else -> Apps.updateAt
        }
        val order = column to if (sortOrder == "asc") SortOrder.ASC else SortOrder.DESC

        val result = wrapRows
            .orderBy(order)
            .limit(limit, offset)

        val apps = result.map { row ->
            App(
                name = row.name,
                latestVersion = row.latestVersion,
                version = row.version,
                global = row.global,
                description = row.description,
                status = row.status,
                homepage = row.homepage,
                url = row.url,
                createAt = row.createAt,
                updateAt = row.updateAt,
                bucket = row.bucket?.let { Bucket(name = it.name) },
                shortcuts = row.shortcuts,
            )
        }
        PaginatedResult<App>(
            value = apps,
            totalCount = totalCount,
        )
    }

    fun getUpdateCount(): Long = transaction {
        Apps.selectAll()
            .where { Apps.status eq AppStatus.INSTALLED.name.lowercase() and (Apps.version neq Apps.latestVersion) }
            .count()
    }

    fun loadAll() {
        loadBuckets()
        loadApps()
    }

    fun loadApps() = transaction {
        val apps = scoopService.apps
        for (app in apps) {
            val query = Apps.leftJoin(Buckets).selectAll().where { Apps.name eq app.name }
            val rows = AppEntity.wrapRows(query).toList()
            val bkt = BucketEntity.find { Buckets.name eq app.bucket!!.name }.firstOrNull()
            when {
                rows.isEmpty() -> {
                    AppEntity.new { update(app, bkt) }
                }
                rows.size == 1 -> {
                    rows.first().update(app, bkt)
                }
                else -> {
                    throw ScooperException("Found more than one app with same name and bucket.")
                }
            }
        }
        val appNames = apps.map { it.name }
        Apps.deleteWhere { name notInList appNames and (status neq AppStatus.INSTALLED.name.lowercase()) }
    }

    fun loadBuckets() = transaction {
        for (bucketDir in scoopService.bucketDirs) {
            val bucket = bucketDir.name
            if (Buckets.selectAll().where { Buckets.name eq bucket }.count() <= 0) {
                BucketEntity.new {
                    name = bucket
                    url = scoopService.getRepoUrl(bucketDir)
                }
            }
        }
        Buckets.deleteWhere { name notInList scoopService.bucketNames }
    }

    fun updateApp(app: App) = transaction {
        val query = Apps.leftJoin(Buckets).selectAll().where { Apps.name eq app.name }
        if (app.bucket != null) {
            query.andWhere { Buckets.name eq app.bucket!!.name }
        }
        val appEntity = AppEntity.wrapRows(query).firstOrNull() ?: return@transaction
        appEntity.update(
            app.copy(
                createAt = appEntity.createAt,
                updateAt = appEntity.updateAt,
            ),
            appEntity.bucket
        )
    }

    private fun AppEntity.update(
        app: App,
        bkt: BucketEntity?,
    ) {
        name = app.name
        version = app.version
        latestVersion = app.latestVersion
        status = app.status
        global = app.global
        description = app.description
        homepage = app.homepage
        url = app.url
        createAt = app.createAt ?: LocalDateTime.now()
        updateAt = app.updateAt ?: LocalDateTime.now()
        bucket = bkt
        shortcuts = app.shortcuts
    }
}

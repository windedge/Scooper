package scooper.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
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
import scooper.service.GitHistoryService
import scooper.service.GitHistoryService.ManifestTimes
import scooper.service.ScoopService
import scooper.util.PAGE_SIZE

data class PaginatedResult<T>(
    val value: List<T>,
    val totalCount: Long
)

data class BucketIndexState(
    val name: String,
    val lastIndexedCommit: String?,
)

class AppsRepository(
    private val scoopService: ScoopService,
    private val gitHistoryService: GitHistoryService,
) {
    private val writeLock = Any()

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
        // Deduplicate: only include one row per (name, bucket_id)
        val dedupIds = Apps.select(Apps.id.max()).groupBy(Apps.name, Apps.bucketId)

        val conditions = Apps.leftJoin(Buckets).selectAll()
        conditions.andWhere { Apps.id inSubQuery dedupIds }
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
        loadApps(incremental = false)
    }

    fun loadApps(incremental: Boolean = true) {
        val bucketStates = getBucketIndexStates()
        val bucketDirsByName = scoopService.bucketDirs.associateBy { it.name }

        val allChangedApps = mutableListOf<App>()
        val deletedAppNames = mutableListOf<Pair<String, String>>() // (appName, bucketName)
        val bucketsNeedingFullLoad = mutableSetOf<String>()

        for (state in bucketStates) {
            if (!incremental) {
                bucketsNeedingFullLoad.add(state.name)
                continue
            }

            val bucketDir = bucketDirsByName[state.name] ?: continue
            val lastCommit = GitHistoryService.commitFromIndexState(state.lastIndexedCommit)
            if (lastCommit == null) {
                bucketsNeedingFullLoad.add(state.name)
                continue
            }

            val changes = gitHistoryService.getManifestChanges(bucketDir, lastCommit)
            if (changes == null) {
                bucketsNeedingFullLoad.add(state.name)
                continue
            }
            if (changes.addedOrModified.isEmpty() && changes.deleted.isEmpty()) continue

            val bucket = Bucket(name = state.name, url = "")
            allChangedApps.addAll(
                scoopService.buildAppsFromManifestNames(bucketDir, changes.addedOrModified, bucket)
            )
            for (fileName in changes.deleted) {
                deletedAppNames.add(fileName.removeSuffix(".json") to state.name)
            }
        }

        val fullLoadApps = if (bucketsNeedingFullLoad.isNotEmpty()) {
            scoopService.apps.filter { it.bucket?.name in bucketsNeedingFullLoad }
        } else {
            emptyList()
        }

        synchronized(writeLock) {
            transaction {
                upsertApps(allChangedApps)

                if (fullLoadApps.isNotEmpty()) {
                    upsertApps(fullLoadApps)
                    // Delete uninstalled apps that no longer exist in these buckets
                    val fullLoadAppNames = fullLoadApps.map { it.name }.toSet()
                    val fullLoadBucketIds = BucketEntity.find {
                        Buckets.name inList bucketsNeedingFullLoad.toList()
                    }.map { it.id }.toSet()
                    Apps.deleteWhere {
                        (name notInList fullLoadAppNames) and
                                (bucketId inList fullLoadBucketIds) and
                                (status neq AppStatus.INSTALLED.name.lowercase())
                    }
                }

                for ((appName, bucketName) in deletedAppNames) {
                    val bkt = BucketEntity.find { Buckets.name eq bucketName }.firstOrNull() ?: continue
                    Apps.deleteWhere {
                        (Apps.name eq appName) and
                                (Apps.bucketId eq bkt.id) and
                                (Apps.status neq AppStatus.INSTALLED.name.lowercase())
                    }
                }
            }
        }
    }

    private fun upsertApps(apps: List<App>) {
        for (app in apps) {
            val query = Apps.leftJoin(Buckets).selectAll().where { Apps.name eq app.name }
            val rows = AppEntity.wrapRows(query).toList()
            val bkt = BucketEntity.find { Buckets.name eq app.bucket!!.name }.firstOrNull()
            if (rows.isEmpty()) {
                AppEntity.new { update(app, bkt) }
            } else {
                val existing = rows.maxBy { it.id.value }
                rows.filter { it.id != existing.id }.forEach { it.delete() }
                existing.update(
                    app.copy(createAt = existing.createAt, updateAt = existing.updateAt),
                    bkt,
                )
            }
        }
    }

    fun loadBuckets() = synchronized(writeLock) { transaction {
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
    } }

    fun updateApp(app: App) = synchronized(writeLock) { transaction {
        val query = Apps.leftJoin(Buckets).selectAll().where { Apps.name eq app.name }
        if (app.bucket != null) {
            query.andWhere { Buckets.name eq app.bucket!!.name }
        }
        // Pick max-id row to handle stale duplicates
        val appEntities = AppEntity.wrapRows(query).toList()
        val appEntity = appEntities.maxByOrNull { it.id.value } ?: return@transaction
        // Delete stale duplicates if any
        for (entity in appEntities) {
            if (entity.id != appEntity.id) entity.delete()
        }
        appEntity.update(
            app.copy(
                createAt = appEntity.createAt,
                updateAt = appEntity.updateAt,
            ),
            appEntity.bucket
        )
    } }

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

    /** Batch update manifest times from Git indexer and record bucket HEAD. */
    fun updateManifestTimes(
        bucketName: String,
        manifestTimes: Map<String, ManifestTimes>,
        headCommit: String,
    ) = synchronized(writeLock) { transaction {
        val bkt = BucketEntity.find { Buckets.name eq bucketName }.firstOrNull() ?: return@transaction

        for ((fileName, times) in manifestTimes) {
            val appName = fileName.removeSuffix(".json")
            val appEntity = AppEntity.find {
                Apps.name eq appName and (Apps.bucketId eq bkt.id)
            }.firstOrNull() ?: continue

            if (times.createAt != null) {
                appEntity.createAt = times.createAt
            }
            if (times.updateAt != null) {
                appEntity.updateAt = times.updateAt
            }
        }

        bkt.lastIndexedCommit = headCommit
    } }

    /** Read all bucket index states for background indexing. */
    fun getBucketIndexStates(): List<BucketIndexState> = transaction {
        BucketEntity.all().map {
            BucketIndexState(name = it.name, lastIndexedCommit = it.lastIndexedCommit)
        }
    }
}

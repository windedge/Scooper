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
import scooper.service.ScoopClient
import scooper.util.PAGE_SIZE
import scooper.util.logger

data class PaginatedResult<T>(
    val value: List<T>,
    val totalCount: Long
)

data class BucketIndexState(
    val name: String,
    val lastIndexedCommit: String?,
)

class AppsRepository(
    private val scoopClient: ScoopClient,
    private val gitHistoryService: GitHistoryService,
) {
    private val logger by logger()
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

        // FTS + LIKE combined search: FTS results ranked first, LIKE fills gaps
        val ftsIds = if (query.isNotBlank()) searchFts(query) else null
        if (query.isNotBlank()) {
            val likeOps = buildLikeOps(query)
            when {
                likeOps.isNotEmpty() && ftsIds != null && ftsIds.isNotEmpty() -> {
                    val likeCondition = likeOps.reduce { acc, op -> acc or op }
                    conditions.andWhere { (Apps.id inList ftsIds) or likeCondition }
                }
                likeOps.isNotEmpty() -> {
                    val likeCondition = likeOps.reduce { acc, op -> acc or op }
                    conditions.andWhere { likeCondition }
                }
                ftsIds != null && ftsIds.isNotEmpty() -> {
                    conditions.andWhere { Apps.id inList ftsIds }
                }
                else -> {
                    conditions.andWhere { Apps.id eq -1 }
                }
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

        // When FTS results exist, prioritize them over LIKE-only matches
        val result = if (ftsIds != null && ftsIds.isNotEmpty()) {
            val ftsPriority = Case()
                .When(Apps.id inList ftsIds, intLiteral(0))
                .Else(intLiteral(1))
            wrapRows.orderBy(ftsPriority to SortOrder.ASC, order)
        } else {
            wrapRows.orderBy(order)
        }.limit(limit).offset(offset)

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
        val bucketDirs = scoopClient.bucketDirs
        val allApps = scoopClient.apps
        loadApps(incremental = false)
        rebuildFtsIndex()
    }

    fun loadApps(incremental: Boolean = true) {
        val bucketStates = getBucketIndexStates()
        val bucketDirsByName = scoopClient.bucketDirs.associateBy { it.name }

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

            val headCommit = gitHistoryService.getHeadCommit(bucketDir)
            if (headCommit == lastCommit) continue

            val changes = gitHistoryService.getManifestChanges(bucketDir, lastCommit)
            if (changes == null) {
                bucketsNeedingFullLoad.add(state.name)
                continue
            }
            if (changes.addedOrModified.isEmpty() && changes.deleted.isEmpty()) continue

            val bucket = Bucket(name = state.name, url = "")
            allChangedApps.addAll(
                scoopClient.buildAppsFromManifestNames(bucketDir, changes.addedOrModified, bucket)
            )
            for (fileName in changes.deleted) {
                deletedAppNames.add(fileName.removeSuffix(".json") to state.name)
            }
        }

        val fullLoadApps = if (!incremental && bucketsNeedingFullLoad.isNotEmpty()) {
            // Full reload: use entire apps list (includes orphan installed apps)
            scoopClient.apps
        } else if (bucketsNeedingFullLoad.isNotEmpty()) {
            scoopClient.apps.filter { it.bucket?.name in bucketsNeedingFullLoad }
        } else {
            emptyList()
        }

        synchronized(writeLock) {
            transaction {
                upsertApps(allChangedApps, preserveUpdateAt = false)

                // Clean deleted apps from incremental changes
                for ((appName, bucketName) in deletedAppNames) {
                    val bkt = BucketEntity.find { Buckets.name eq bucketName }.firstOrNull()
                    if (bkt != null) {
                        AppEntity.find { Apps.name eq appName and (Apps.bucketId eq bkt.id) }
                            .forEach { it.delete() }
                    }
                }

                if (fullLoadApps.isNotEmpty()) {
                    upsertApps(fullLoadApps, preserveUpdateAt = true)
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

                // Clean up non-installed orphans (bucketId is null)
                Apps.deleteWhere {
                    (Apps.bucketId eq null) and (Apps.status neq AppStatus.INSTALLED.name.lowercase())
                }
            }
        }

    }

    private fun upsertApps(apps: List<App>, preserveUpdateAt: Boolean) {
        for (app in apps) {
            if (app.bucket != null) {
                // App with a bucket: find by name and bucket
                val query = Apps.leftJoin(Buckets).selectAll().where { Apps.name eq app.name }
                val rows = AppEntity.wrapRows(query).toList()
                val bkt = BucketEntity.find { Buckets.name eq app.bucket!!.name }.firstOrNull()
                if (rows.isEmpty()) {
                    AppEntity.new { update(app, bkt) }
                } else {
                    val existing = rows.maxBy { it.id.value }
                    rows.filter { it.id != existing.id }.forEach { it.delete() }
                    val updatedApp = if (preserveUpdateAt) {
                        app.copy(createAt = existing.createAt, updateAt = existing.updateAt)
                    } else {
                        app.copy(createAt = existing.createAt)
                    }
                    existing.update(updatedApp, bkt)
                }
            } else {
                // Orphan app (no bucket): find by name where bucket is null
                val query = Apps.selectAll().where { (Apps.name eq app.name) and (Apps.bucketId eq null) }
                val existing = AppEntity.wrapRows(query).firstOrNull()
                if (existing != null) {
                    val updatedApp = if (preserveUpdateAt) {
                        app.copy(createAt = existing.createAt, updateAt = existing.updateAt)
                    } else {
                        app.copy(createAt = existing.createAt)
                    }
                    existing.update(updatedApp, null)
                } else {
                    AppEntity.new { update(app, null) }
                }
            }
        }
    }

    fun loadBuckets() = synchronized(writeLock) { transaction {
        for (bucketDir in scoopClient.bucketDirs) {
            val bucket = bucketDir.name
            if (Buckets.selectAll().where { Buckets.name eq bucket }.count() <= 0) {
                BucketEntity.new {
                    name = bucket
                    url = scoopClient.getRepoUrl(bucketDir)
                }
            }
        }
        val removedBuckets = BucketEntity.find { Buckets.name notInList scoopClient.bucketNames }.toList()
        if (removedBuckets.isNotEmpty()) {
            val removedBucketIds = removedBuckets.map { it.id }.toSet()
            // Delete non-installed apps belonging to removed buckets
            Apps.deleteWhere {
                (Apps.bucketId inList removedBucketIds) and
                        (Apps.status neq AppStatus.INSTALLED.name.lowercase())
            }
            // Keep installed apps but their bucketId will become null via SET_NULL
            removedBuckets.forEach { it.delete() }
        }
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

    /** Delete a single app by name (optionally scoped to bucket). */
    fun deleteApp(appName: String, bucketName: String? = null) = synchronized(writeLock) { transaction {
        val bkt = bucketName?.let { BucketEntity.find { Buckets.name eq it }.firstOrNull() }
        val query = Apps.selectAll().where { Apps.name eq appName }
        if (bkt != null) query.andWhere { Apps.bucketId eq bkt.id }
        AppEntity.wrapRows(query).toList().forEach { it.delete() }
    } }

    /** Insert or update a single app. If the app already exists (by name), update it; otherwise create a new record. */
    fun upsertApp(app: App) = synchronized(writeLock) { transaction {
        val bkt = app.bucket?.name?.let {
            BucketEntity.find { Buckets.name eq it }.firstOrNull()
        }
        val query = Apps.selectAll().where { Apps.name eq app.name }
        if (bkt != null) {
            query.andWhere { Apps.bucketId eq bkt.id }
        }
        val existing = AppEntity.wrapRows(query).firstOrNull()
        if (existing != null) {
            existing.update(
                app.copy(createAt = existing.createAt, updateAt = existing.updateAt),
                bkt,
            )
        } else {
            AppEntity.new { update(app, bkt) }
        }
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

    // ==================== Full-Text Search ====================

    /** Rebuild the FTS index from scratch. Call after bulk data changes. */
    fun rebuildFtsIndex() = transaction {
        exec("INSERT INTO apps_fts(apps_fts) VALUES ('rebuild')")
    }

    /** Search using FTS5 full-text index. Returns null if FTS is unavailable. */
    private fun Transaction.searchFts(query: String): List<Int>? {
        val ftsQuery = buildFtsQuery(query) ?: return emptyList()
        return try {
            exec(
                "SELECT rowid FROM apps_fts WHERE apps_fts MATCH ?",
                args = listOf(VarCharColumnType() to ftsQuery)
            ) { rs ->
                val ids = mutableListOf<Int>()
                while (rs.next()) ids.add(rs.getInt(1))
                ids
            }
        } catch (e: Exception) {
            logger.warn("FTS search failed, falling back to LIKE: ${e.message}")
            null
        }
    }

    /** Build FTS5 query from user input.
     *  Supports: `firefox chrome` (AND), `firefox OR chrome`, `firefox -esr` (exclude). */
    private fun buildFtsQuery(query: String): String? {
        val tokens = query.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val parts = mutableListOf<String>()
        for (token in tokens) {
            when {
                token.equals("OR", ignoreCase = true) -> parts.add("OR")
                token.startsWith("-") && token.length > 1 -> {
                    val word = token.removePrefix("-")
                    if (word.any { it.isLetterOrDigit() }) {
                        parts.add("NOT")
                        parts.add("\"${word.replace("\"", "")}\"*")
                    }
                }
                token.any { it.isLetterOrDigit() } -> {
                    parts.add("\"${token.replace("\"", "")}\"*")
                }
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    /** Build LIKE match ops from search query, filtering out OR/- syntax tokens.
     *  Each word produces (name LIKE OR description LIKE), words are ANDed together. */
    private fun buildLikeOps(query: String): List<Op<Boolean>> {
        val words = query.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.equals("OR", ignoreCase = true) && !it.startsWith("-") }
        if (words.isEmpty()) return emptyList()
        return words.map { word ->
            val escaped = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            Op.build { Apps.name like "%$escaped%" or (Apps.description like "%$escaped%") }
        }
    }

    /** Read all bucket index states for background indexing. */
    fun getBucketIndexStates(): List<BucketIndexState> = transaction {
        BucketEntity.all().map {
            BucketIndexState(name = it.name, lastIndexedCommit = it.lastIndexedCommit)
        }
    }
}

package scooper.repository

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import scooper.data.App
import scooper.data.Bucket
import scooper.util.ScooperException
import java.io.File
import java.time.LocalDateTime


object Apps : IntIdTable() {
    override val tableName = "apps"

    val name = varchar("name", 1000)
    val version = varchar("version", 1000).nullable()
    val latestVersion = varchar("latest_version", 1000)
    val bucketId = reference("bucket_id", Buckets, onDelete = ReferenceOption.SET_NULL).nullable()

    val global = bool("global").default(false)
    val status = varchar("status", 20).default("uninstall")
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

object Buckets : IntIdTable() {
    override val tableName = "buckets"

    val name = varchar("name", 1000)
    val url = text("url").nullable()
}

class AppEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : EntityClass<Int, AppEntity>(Apps)

    var name by Apps.name
    var version by Apps.version
    var latestVersion by Apps.latestVersion
    var bucket by BucketEntity optionalReferencedOn Apps.bucketId
    var global by Apps.global
    var status by Apps.status
    var description by Apps.description
    var url by Apps.url
    var homepage by Apps.homepage
    var license by Apps.license
    var createAt by Apps.createAt
    var updateAt by Apps.updateAt
}

class BucketEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BucketEntity>(Buckets)

    var name by Buckets.name
    var url by Buckets.url
}


object AppsRepository {
    fun getBuckets(): List<Bucket> = transaction {
        BucketEntity.all().map {
            Bucket(name = it.name, url = it.url)
        }
    }

    fun getApps(
        query: String = "",
        bucket: String = "",
        scope: String = "all",
        offset: Long = 0L,
        limit: Int = 20
    ): List<App> = transaction {
        val conditions = Apps.leftJoin(Buckets).selectAll()
        if (query.isNotBlank()) {
            val words = query.trim().split(" ")
            for (word in words) {
                conditions.andWhere { Apps.name like "%$word%" or (Apps.description match "%$word%") }
            }
        }
        if (bucket.isNotBlank()) {
            conditions.andWhere { Buckets.name eq bucket }
        }
        if (scope == "installed") {
            conditions.andWhere { Apps.status eq "installed" }
        } else if (scope == "updates") {
            conditions.andWhere { Apps.status eq "installed" and (Apps.version neq Apps.latestVersion) }
        }

        val result = AppEntity.wrapRows(conditions)
            .orderBy(Apps.updateAt to SortOrder.DESC)
            .limit(limit, offset)

        result.map {
            App(
                name = it.name,
                latestVersion = it.latestVersion,
                global = it.global,
                description = it.description,
                status = it.status,
                homepage = it.homepage,
                url = it.url,
            ).apply {
                version = it.version
                createAt = it.createAt
                updateAt = it.updateAt

                if (it.bucket != null) {
                    this.bucket = Bucket(name = it.bucket!!.name)
                }
            }
        }
    }

    fun loadAll() {
        loadBuckets()
        loadApps()
    }

    fun loadApps() = transaction {
        val apps = Scoop.apps
        for (app in apps) {
            val query = Apps.leftJoin(Buckets).select { Apps.name eq app.name }

            val rows = AppEntity.wrapRows(query).toList()
            val bkt = BucketEntity.find { Buckets.name eq app.bucket!!.name }.firstOrNull()
            when {
                rows.isEmpty() -> {
                    AppEntity.new { update(app, bkt) }
                }

                rows.size == 1 -> {
                    val row = rows.first()
                    row.apply { update(app, bkt) }
                }

                else -> {
                    throw ScooperException("Found more than one app with same name and bucket.")
                }
            }
        }

        val appNames = apps.map { it.name }
        Apps.deleteWhere { name notInList appNames and (status neq "installed") }
    }

    fun loadBuckets() = transaction {
        for (bucketDir in Scoop.bucketDirs) {
            val bucket = bucketDir.name
            if (Buckets.select { Buckets.name eq bucket }.count() <= 0) {
                BucketEntity.new {
                    name = bucket
                    url = Scoop.getBucketRepo(bucketDir)
                }
            }
        }
        Buckets.deleteWhere { name notInList Scoop.bucketNames }
    }

    fun updateApp(app: App) = transaction {
        val query = Apps.leftJoin(Buckets).select { Apps.name eq app.name }
        if (app.bucket != null) {
            query.andWhere { Buckets.name eq app.bucket!!.name }
        }
        val appEntity = AppEntity.wrapRows(query).firstOrNull() ?: return@transaction
        appEntity.update(
            app.also {
                it.createAt = appEntity.createAt
                it.updateAt = appEntity.updateAt
            },
            appEntity.bucket
        )
    }

    private fun AppEntity.update(
        app: App,
        bkt: BucketEntity?
    ) {
        name = app.name
        version = app.version
        latestVersion = app.latestVersion
        status = app.status
        global = app.global
        description = app.description
        homepage = app.homepage
        url = app.url
        createAt = app.createAt
        updateAt = app.updateAt
        bucket = bkt
    }
}

fun initDb() {
    val databasePath = File(System.getenv("USERPROFILE")).resolve("scooper.db")
    Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC", setupConnection = { connection ->
        connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
    })

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Apps, Buckets)
    }

    val appCount = transaction { Apps.selectAll().count() }
    if (appCount == 0L) {
        AppsRepository.loadAll()
    }
}


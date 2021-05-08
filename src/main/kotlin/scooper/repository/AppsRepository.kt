package scooper.repository

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import scooper.data.App
import scooper.data.Bucket
import scooper.util.ScooperException
import java.io.File


object Apps : IntIdTable() {
    override val tableName = "apps"

    val name = varchar("name", 1000)
    val version = varchar("version", 1000)
    val latestVersion = varchar("latest_version", 1000)
    val bucketId = reference("bucket_id", Buckets)

    val global = bool("global").default(false)
    val installed = bool("installed").default(false)
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
    var bucket by BucketEntity referencedOn Apps.bucketId
    var global by Apps.global
    var installed by Apps.installed
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
    ): List<App> =
        transaction {
            val conditions = Apps.leftJoin(Buckets).selectAll()
            if (query.isNotBlank()) {
                conditions.andWhere { Apps.name like "%$query%" or (Apps.description match "%$query%") }
            }
            if (bucket.isNotBlank()) {
                conditions.andWhere { Buckets.name eq bucket }
            }
            if (scope == "installed") {
                conditions.andWhere { Apps.installed eq true }
            } else if (scope == "updates") {
                conditions.andWhere { Apps.installed eq true and (Apps.version neq Apps.latestVersion) }
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
                    installed = it.installed,
                    homepage = it.homepage,
                    url = it.url,
                ).apply {
                    version = it.version
                    createAt = it.createAt
                    updateAt = it.updateAt

                    this.bucket = Bucket(name = it.bucket.name)
                }
            }
        }

    fun loadApps() {
        transaction {
            for (bucket in Scoop.bucketNames) {
                if (Buckets.select { Buckets.name eq bucket }.count() <= 0) {
                    BucketEntity.new {
                        name = bucket
                    }
                }
            }
        }

        transaction {
            for (app in Scoop.apps) {
                val query = Apps.leftJoin(Buckets).select {
                    Apps.name eq app.name and (Buckets.name eq app.bucket.name)
                }

                val rows = AppEntity.wrapRows(query).toList()
                val bkt = BucketEntity.find { Buckets.name eq app.bucket.name }.first()
                when {
                    rows.isEmpty() -> {
                        AppEntity.new {
                            updateByApp(app, bkt)
                        }
                    }
                    rows.size == 1 -> {
                        val row = rows[0]
                        row.apply {
                            updateByApp(app, bkt)
                        }
                    }
                    else -> {
                        throw ScooperException("Found more than one app with same name and same bucket.")
                    }
                }
            }
        }
    }

    private fun AppEntity.updateByApp(
        app: App,
        bkt: BucketEntity
    ) {
        name = app.name
        version = app.version
        latestVersion = app.latestVersion
        global = app.global
        installed = app.installed
        description = app.description
        homepage = app.homepage
        url = app.url

        createAt = app.createAt
        updateAt = app.updateAt

        bucket = bkt
    }
}

fun initDb() {
    val databasePath = File("d:\\tmp\\", "scooper.db")
    Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Apps, Buckets)
    }

    val appCount = transaction { Apps.selectAll().count() }
    if (appCount == 0L) {
        AppsRepository.loadApps()
        /*
        val extra = transaction { BucketEntity.new { name = "extra" } }
        transaction {
            val descList = listOf(
                "MikTeX is an up-to-date implementation of TeX/LaTeX and related programs.",
                "A cross-platform, statically typed",
                "A cross-platform, statically typed, general-purpose programming language with type inference."
            )

            for (i in 1..20) {
                AppEntity.new {
                    name = "kotlin $i"
                    version = "1.4.31"
                    bucket = extra
                    global = true
                    description = descList.random()
                    homepage = ""
                    license = "Apache-2.0"
                    url =
                        "https://github.com/JetBrains/kotlin/releases/download/v1.4.31/kotlin-compiler-1.4.31.zip"
                }
            }
        }
        */
    }
}


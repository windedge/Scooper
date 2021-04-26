package scooper.repository

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File


data class App(
    val name: String,
    val version: String,
    val global: Boolean = false,
    val installed: Boolean = false,
    val description: String? = null,
    val url: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null
)


object Apps : IntIdTable() {
    val name = varchar("name", 1000)
    val version = varchar("version", 1000)
    val bucket = reference("bucket_id", Buckets)

    val global = bool("global").default(false)
    val installed = bool("installed").default(false)
    val description = varchar("description", 5000).nullable()
    val url = text("url").nullable()
    val homepage = text("homepage").nullable()
    val license = varchar("license", 1000).nullable()
    val licenseUrl = text("license_url").nullable()
}

object Buckets : IntIdTable() {
    val name = varchar("name", 1000)
    val url = text("url").nullable()
}

class AppEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : EntityClass<Int, AppEntity>(Apps)

    var name by Apps.name
    var version by Apps.version
    var bucket by BucketEntity referencedOn Apps.bucket
    var global by Apps.global
    var installed by Apps.installed
    var description by Apps.description
    var url by Apps.url
    var homepage by Apps.homepage
    var license by Apps.license
}

class BucketEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BucketEntity>(Buckets)

    var name by Buckets.name
    var url by Buckets.url
}


object AppsRepository {
    fun getApps(offset: Long = 0L, limit: Int = 20): List<App> {
        val apps = transaction {
            val query = AppEntity.all().limit(limit, offset)
            query.map {
                App(
                    name = it.name,
                    version = it.version,
                    global = it.global,
                    description = it.description,
                    installed = it.installed,
                    homepage = it.homepage,
                    url = it.url,
                )
            }
        }
        println("apps.count() = ${apps.count()}")
        return apps
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
    }
}


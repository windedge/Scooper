package scooper.repository

// import org.jetbrains.exposed.dao.*
// import org.jetbrains.exposed.dao.id.EntityID
// import org.jetbrains.exposed.dao.id.IntIdTable
// import org.jetbrains.exposed.sql.*
// import org.jetbrains.exposed.sql.transactions.transaction
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import moe.tlaster.precompose.livedata.LiveData
import scooper.database.ScooperDatabase
import java.io.File


fun getScooperDatabaseDriver(): SqlDriver {
    val databasePath = File(System.getProperty("java.io.tmpdir"), "Scooper.db")
    val driver = JdbcSqliteDriver(url = "jdbc:sqlite:${databasePath.absolutePath}")
    ScooperDatabase.Schema.create(driver)

    return driver
}

val db by lazy {
    val db = ScooperDatabase(getScooperDatabaseDriver())
    val appCount = db.appQueries.count().executeAsOne()
    println("appCount = $appCount")
    if (appCount == 0L) {
        for (i in 1..20) {
            val descList = listOf(
                "MikTeX is an up-to-date implementation of TeX/LaTeX and related programs.",
                "A cross-platform, statically typed",
                "A cross-platform, statically typed, general-purpose programming language with type inference."
            )

            db.appQueries.add(
                name = "kotlin $i",
                version = "1.4.31",
                global = true,
                description = descList.random(),
                homepage = "",
                url = "https://github.com/JetBrains/kotlin/releases/download/v1.4.31/kotlin-compiler-1.4.31.zip",
                license = "Apache-2.0",
                licenseUrl = null
            )
        }
    }
    db
}

object AppsRepository {

    fun getApps(): LiveData<MutableList<App>> {
        val apps = db.appQueries.selectAll().executeAsList().map {
            App(
                name = it.name,
                version = it.version,
                global = it.global,
                installed = it.installed,
                description = it.description
            )
        }.toMutableList()
        return LiveData(apps)
    }

}

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


/*
interface Repository<T> {
    fun ResultRow.fromRow(): T
    fun T.toRow(): ResultRow

    fun get(id: Int): T?
    fun create(record: T)
    fun write(record: T)
    fun search(block: Table.() -> Unit): Iterable<T>
    fun remove(id: Int)
}


object Apps : IntIdTable() {
    val name = varchar("name", 1000)
    val version = varchar("version", 1000)
    val bucket = reference("bucket_id", Buckets)

    val global = bool("global").default(false)
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

class App(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : EntityClass<Int, App>(Apps)

    var name by Apps.name
    var version by Apps.version
    var bucket by Bucket referencedOn Apps.bucket
    var global by Apps.global
    var description by Apps.description
    var url by Apps.url
    var homepage by Apps.homepage
    var license by Apps.license
}

class Bucket(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Bucket>(Buckets)

    var name by Buckets.name
    var url by Buckets.url
}


object AppsRepository {
    fun getApps(offset: Int = 0, limit: Int = Int.MAX_VALUE): LiveData<List<App>> {
        val apps = transaction {
            App.all().with(App::bucket).limit(limit, offset.toLong()).toList()
        }
        println("apps.count() = ${apps.count()}")
        // val buckets = apps.joinToString { it.bucket.name }
        // println("buckets = ${buckets}")
        return LiveData(apps)
    }
}

fun initDb() {
    val databasePath = File(System.getProperty("java.io.tmpdir"), "scooper.db")
    Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Apps, Buckets)
    }

    val appCount = transaction { Apps.selectAll().count() }
    if (appCount == 0L) {
        transaction {
            for (i in 1..20) {
                val descList = listOf(
                    "MikTeX is an up-to-date implementation of TeX/LaTeX and related programs.",
                    "A cross-platform, statically typed",
                    "A cross-platform, statically typed, general-purpose programming language with type inference."
                )

                val extra = Bucket.new { name = "extra" }
                App.new {
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
*/

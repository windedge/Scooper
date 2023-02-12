package scooper.repository

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import scooper.data.Theme
import scooper.data.UIConfig

object Configs : IntIdTable(name = "configs") {
    val refreshOnStartup = bool("refresh_on_startup").default(false)
    val theme = enumeration("theme", Theme::class).default(Theme.Auto)
}

object ConfigRepository {
    fun getConfig(): UIConfig {
        createIfNone()

        return transaction {
            Configs.selectAll().first().run {
                UIConfig(
                    refreshOnStartup = this[Configs.refreshOnStartup],
                    theme = this[Configs.theme]
                )
            }
        }
    }

    fun setConfig(config: UIConfig) {
        createIfNone()

        transaction {
            Configs.update({ Configs.id eq Configs.selectAll().first()[Configs.id] }) {
                it[refreshOnStartup] = config.refreshOnStartup
                it[theme] = config.theme
            }
        }
    }

    private fun createIfNone() = transaction {
        val count = Configs.selectAll().count()
        if (count == 0L) {
            Configs.insert {
                it[refreshOnStartup] = false
                it[theme] = Theme.Auto
            }
        }
    }
}
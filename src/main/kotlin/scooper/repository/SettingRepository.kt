package scooper.repository

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import scooper.data.UIConfig
import scooper.repository.db.Configs

class ConfigRepository {
    fun getConfig(): UIConfig {
        createIfNone()
        return transaction {
            Configs.selectAll().first().run {
                UIConfig(
                    refreshOnStartup = this[Configs.refreshOnStartup],
                    theme = this[Configs.theme],
                    fontSizeScale = this[Configs.fontSizeScale],
                    viewMode = this[Configs.viewMode],
                    paginationMode = this[Configs.paginationMode],
                    pageSize = this[Configs.pageSize],
                    windowX = this[Configs.windowX],
                    windowY = this[Configs.windowY],
                    windowWidth = this[Configs.windowWidth],
                    windowHeight = this[Configs.windowHeight],
                    isMaximized = this[Configs.isMaximized],
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
                it[fontSizeScale] = config.fontSizeScale
                it[viewMode] = config.viewMode
                it[paginationMode] = config.paginationMode
                it[pageSize] = config.pageSize
                it[windowX] = config.windowX
                it[windowY] = config.windowY
                it[windowWidth] = config.windowWidth
                it[windowHeight] = config.windowHeight
                it[isMaximized] = config.isMaximized
            }
        }
    }

    private fun createIfNone() = transaction {
        val count = Configs.selectAll().count()
        if (count == 0L) {
            Configs.insert {
                it[refreshOnStartup] = false
                it[theme] = scooper.data.Theme.Auto
            }
        }
    }
}

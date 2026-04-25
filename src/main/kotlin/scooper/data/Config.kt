package scooper.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.skiko.SystemTheme
import scooper.util.PAGE_SIZE
import org.jetbrains.skiko.currentSystemTheme

@Serializable
data class ScoopConfig(
    @SerialName("proxy")
    var proxy: String = "",

    @SerialName("aria2-enabled")
    var aria2Enabled: Boolean = true,
) {
    @Suppress("unused")
    val proxyType: String
        get() {
            return if (proxy.isBlank()) {
                "default"
            } else if (proxy == "none") {
                "none"
            } else "custom"
        }
}


enum class Theme() {
    Auto, Light, Dark
}

enum class ViewMode { List, Grid }
enum class PaginationMode { Waterfall, Pagination }

const val DEFAULT_WINDOW_WIDTH = 1280
const val DEFAULT_WINDOW_HEIGHT = 800
const val MIN_WINDOW_WIDTH = 1080
const val MIN_WINDOW_HEIGHT = 680

data class UIConfig(
    val refreshOnStartup: Boolean = false,
    val theme: Theme = Theme.Auto,
    val fontSizeScale: Float = 1.0f,
    val viewMode: ViewMode = ViewMode.List,
    val paginationMode: PaginationMode = PaginationMode.Waterfall,
    val pageSize: Int = PAGE_SIZE,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int = DEFAULT_WINDOW_WIDTH,
    val windowHeight: Int = DEFAULT_WINDOW_HEIGHT,
    val isMaximized: Boolean = false,
)

fun Theme.toSystemTheme() = when (this) {
    Theme.Auto -> currentSystemTheme
    Theme.Light -> SystemTheme.LIGHT
    Theme.Dark -> SystemTheme.DARK
}
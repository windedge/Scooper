package scooper.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.skiko.SystemTheme
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

data class UIConfig(
    val refreshOnStartup: Boolean = false,
    val theme: Theme = Theme.Auto,
)

fun Theme.toSystemTheme() = when (this) {
    Theme.Auto -> currentSystemTheme
    Theme.Light -> SystemTheme.LIGHT
    Theme.Dark -> SystemTheme.DARK
}
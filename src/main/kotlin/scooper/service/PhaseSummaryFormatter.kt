package scooper.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PhaseSummaryFormatter {

    private val json = Json { ignoreUnknownKeys = true }

    fun formatPhaseStart(phase: String, appName: String?, attempt: Int = 0): String {
        val name = appName?.trim()?.takeIf { it.isNotEmpty() }
        return when (phase.lowercase()) {
            // Keep the titles short: they render on a single line inside the
            // execution step header, where the ellipsis is only a fallback.
            "research" -> if (name != null) "Researching $name..." else "Researching repository..."
            "analyze" -> "Analyzing package..."
            "generate" -> "Writing manifest..."
            "assist" -> "Working on your request..."
            "repair" -> {
                val count = if (attempt > 0) attempt else 1
                "Repairing manifest (attempt $count/3)..."
            }
            "verify" -> "Validating manifest..."
            else -> "Working on phase: $phase..."
        }
    }

    fun formatPhaseOutput(phase: String, outputJson: String): String? {
        if (outputJson.isBlank()) return null
        return try {
            val root = json.parseToJsonElement(outputJson) as? JsonObject ?: return null
            when (phase.lowercase()) {
                "research" -> formatResearchSummary(root)
                "analyze" -> formatAnalyzeSummary(root)
                "generate" -> formatGenerateSummary(root)
                "assist", "repair" -> formatRevisionSummary(root)
                // verify has no output summary (proposal 4.6: the step shows duration only)
                "verify" -> null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatResearchSummary(root: JsonObject): String {
        val status = root["status"]?.jsonPrimitive?.content ?: ""
        if (status.equals("NO_WINDOWS_ASSETS", ignoreCase = true)) {
            return "No Windows assets found for this repository"
        }

        val version = root["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val assets = root["windows_assets"]?.jsonArray ?: emptyList()
        val count = assets.size

        val assetType = assets.firstOrNull()?.let {
            (it as? JsonObject)?.get("asset_type")?.jsonPrimitive?.content
        }?.takeIf { it.isNotBlank() }

        val typeSuffix = if (assetType != null) " ($assetType)" else ""
        val countText = if (count == 1) "1 Windows asset$typeSuffix" else "$count Windows assets$typeSuffix"

        return if (version != null) {
            "Found release ${formatVersion(version)} with $countText"
        } else {
            "Found $countText"
        }
    }

    private fun formatAnalyzeSummary(root: JsonObject): String {
        val strategy = root["strategy"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "standard"
        val binArray = root["bin"]?.jsonArray
        val firstBin = binArray?.firstOrNull()?.let { elem ->
            when (elem) {
                is JsonArray -> elem.firstOrNull()?.jsonPrimitive?.content
                else -> elem.jsonPrimitive.content
            }
        }?.takeIf { it.isNotBlank() }

        return if (firstBin != null) {
            "Detected strategy '$strategy' with executable '$firstBin'"
        } else {
            "Detected strategy '$strategy'"
        }
    }

    private fun formatGenerateSummary(root: JsonObject): String {
        val version = root["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val archObj = root["architecture"]?.jsonObject
        val archs = archObj?.keys?.filter { it.isNotBlank() }?.sorted()?.joinToString("/")
            ?: if (root.containsKey("url")) "64bit" else "generic"

        val hasCheckver = root.containsKey("checkver")
        val hasAutoupdate = root.containsKey("autoupdate")
        val rulesText = when {
            hasCheckver && hasAutoupdate -> "checkver & autoupdate configured"
            hasCheckver -> "checkver configured"
            hasAutoupdate -> "autoupdate configured"
            else -> "schema assembled"
        }

        return if (version != null) {
            "Generated manifest ${formatVersion(version)} ($archs, $rulesText)"
        } else {
            "Generated manifest ($archs, $rulesText)"
        }
    }

    private fun formatRevisionSummary(root: JsonObject): String {
        val version = root["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val archObj = root["architecture"]?.jsonObject
        val archs = archObj?.keys?.filter { it.isNotBlank() }?.sorted()?.joinToString("/")
            ?: if (root.containsKey("url")) "64bit" else null

        val features = mutableListOf<String>()
        if (archs != null) features.add(archs)

        val hasPreInstall = root.containsKey("pre_install")
        val hasShortcuts = root.containsKey("shortcuts")
        val hasPersist = root.containsKey("persist")
        val hasEnv = root.containsKey("env_set") || root.containsKey("env_add_path")
        val hasAutoupdate = root.containsKey("autoupdate")
        val hasBin = root.containsKey("bin")

        when {
            hasPreInstall && hasShortcuts -> features.add("pre_install script & shortcuts configured")
            hasPreInstall -> features.add("pre_install script configured")
            hasPersist && hasEnv -> features.add("persist & environment configured")
            hasPersist -> features.add("persist configured")
            hasShortcuts -> features.add("shortcuts configured")
            hasEnv -> features.add("environment configured")
            hasAutoupdate -> features.add("autoupdate rules resolved")
            hasBin -> features.add("bin & schema resolved")
            else -> features.add("resolved validation & review feedback")
        }

        val details = features.joinToString(", ")
        return if (version != null) {
            "Updated manifest ${formatVersion(version)} ($details)"
        } else {
            "Updated manifest ($details)"
        }
    }

    private fun formatVersion(version: String): String =
        if (version.startsWith("v", ignoreCase = true)) version else "v$version"
}

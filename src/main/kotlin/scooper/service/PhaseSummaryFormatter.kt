package scooper.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PhaseSummaryFormatter {

    private val json = Json { ignoreUnknownKeys = true }

    fun formatPhaseStart(phase: String, appName: String?, refineAttempt: Int = 0): String {
        val name = appName?.trim()?.takeIf { it.isNotEmpty() }
        return when (phase.lowercase()) {
            "research" -> if (name != null) {
                "Researching repository metadata, releases, and assets for $name..."
            } else {
                "Researching repository metadata, releases, and assets..."
            }
            "analyze" -> "Analyzing Windows package (hashes, archive structure, executables)..."
            "generate" -> "Assembling Scoop manifest schema (architecture, checkver, autoupdate)..."
            "refine" -> {
                val count = if (refineAttempt > 0) refineAttempt else 1
                "Refining manifest (attempt $count/3): resolving validation & review feedback..."
            }
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
                "refine" -> formatRefineSummary(root)
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
            val vPrefix = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            "Found release $vPrefix with $countText"
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
            val vPrefix = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            "Generated manifest $vPrefix ($archs, $rulesText)"
        } else {
            "Generated manifest ($archs, $rulesText)"
        }
    }

    private fun formatRefineSummary(root: JsonObject): String {
        return "Updated manifest fields (resolved validation & review feedback)"
    }
}

package scooper.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                // No hardcoded denominator: the retry budget is engine-side config.
                "Repairing manifest (attempt $count)..."
            }
            "verify" -> "Validating manifest..."
            else -> "Working on phase: $phase..."
        }
    }

    fun formatPhaseOutput(phase: String, outputJson: String): String? {
        if (outputJson.isBlank()) return null
        return try {
            // The engine may wrap the manifest in a code fence and ride phase
            // activities under the top-level _meta key; strip/harvest before dispatch.
            val root = extractJsonObject(outputJson) ?: return null
            extractActivities(root)?.let { return it }
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

    // Tolerates a fenced manifest (```json ... ```) plus surrounding chatter;
    // returns null only when no JSON object can be located.
    private fun extractJsonObject(outputJson: String): JsonObject? {
        val direct = runCatching { json.parseToJsonElement(outputJson) }.getOrNull()
        if (direct is JsonObject) return direct

        val fenceRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?\\s*```")
        fenceRegex.find(outputJson)?.let { match ->
            val candidate = runCatching { json.parseToJsonElement(match.groupValues[1]) }.getOrNull()
            if (candidate is JsonObject) return candidate
        }

        val trimmed = outputJson.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val candidate = runCatching { json.parseToJsonElement(trimmed.substring(start, end + 1)) }.getOrNull()
            if (candidate is JsonObject) return candidate
        }
        return null
    }

    // Reads root["_meta"]["activities"]; accepts an array of strings or a single
    // string. Returns null so the caller falls back to the per-phase template.
    private fun extractActivities(root: JsonObject): String? {
        val meta = root["_meta"] as? JsonObject ?: return null
        val activities = meta["activities"]
        val lines: List<String>? = when (activities) {
            // All-string arrays only; anything else falls back to the template.
            is JsonArray ->
                if (activities.all { it is JsonPrimitive && it.isString }) {
                    activities.map { it.jsonPrimitive.content }
                } else {
                    null
                }
            is JsonPrimitive -> if (activities.isString) listOf(activities.content) else null
            else -> null
        }?.map { it.trim() }?.filter { it.isNotEmpty() }
        return if (lines.isNullOrEmpty()) null else lines.joinToString("\n")
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

package scooper.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import scooper.data.ScoopConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object ScoopConfigManager {
    val configFile: File
        get() = File(System.getenv("USERPROFILE")).resolve(".config/scoop/config.json")

    fun readScoopConfig(file: File = configFile): ScoopConfig {
        val format = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }
        val jsonText = file.readText()
        return format.decodeFromString(jsonText.ifBlank { "{}" })
    }

    fun writeScoopConfig(
        config: ScoopConfig,
        file: File = configFile,
        outputStream: OutputStream? = null,
    ) {
        val (result, format) = mergeConfigToJson(config, file.readText())
        val finalText = format.encodeToString(result)
        val output = outputStream ?: FileOutputStream(file)
        output.use {
            it.write(finalText.toByteArray())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun mergeConfigToJson(
        config: ScoopConfig,
        originalJsonStr: String,
    ): Pair<JsonObject, Json> {
        val format = Json {
            isLenient = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        val json = format.encodeToJsonElement(config).jsonObject
        val jsonFromConfig = Json.parseToJsonElement(originalJsonStr.ifBlank { "{}" }).jsonObject

        val keysToRemove = ScoopConfig.serializer().descriptor.elementNames.toSet() - json.jsonObject.keys
        val jsonToWrite = buildJsonObject {
            jsonFromConfig.filter { it.key !in keysToRemove }.forEach { put(it.key, it.value) }
            json.forEach { put(it.key, it.value) }
        }
        return jsonToWrite to format
    }
}

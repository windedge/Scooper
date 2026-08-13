package scooper.viewmodels

import com.scoopai.core.ManifestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import scooper.data.App
import scooper.service.GithubUrlParser
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.util.logger
import java.io.File
import java.util.UUID

enum class ChatRole {
    User,
    Agent,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val phase: String? = null,
    val note: String? = null,
    val isTyping: Boolean = false,
)

data class CreateState(
    val messages: List<ChatMessage> = emptyList(),
    val inputUrl: String = "",
    val inputNote: String = "",
    val lastUrl: String = "",
    val lastNote: String = "",
    val running: Boolean = false,
    val testing: Boolean = false,
    val currentPhase: String? = null,
    val manifestJson: String = "",
    val suggestedAppName: String? = null,
    val error: String? = null,
    val aiConfigured: Boolean = true,
    val configHint: String? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty()
}

class CreateViewModel(
    private val scoopAiBridge: ScoopAiBridge,
    private val scoopClient: ScoopClient,
) : ContainerHost<CreateState, AppSideEffect>, AutoCloseable {
    private val logger by logger()
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    override val container: Container<CreateState, AppSideEffect> =
        coroutineScope.container(CreateState()) {
            refreshAiConfig()
        }

    fun refreshAiConfig() = intent {
        val missing = scoopAiBridge.readSettings().missingReason()
        reduce {
            state.copy(
                aiConfigured = missing == null,
                configHint = missing,
            )
        }
    }

    fun onUrlChange(url: String) = intent {
        reduce { state.copy(inputUrl = url, error = null) }
    }

    fun onNoteChange(note: String) = intent {
        reduce { state.copy(inputNote = note) }
    }

    fun onSend(url: String? = null, note: String? = null) = intent {
        if (state.running) return@intent
        val rawUrl = url ?: state.inputUrl
        val rawNote = note ?: state.inputNote
        // Keep state in sync so regenerate / future reads see the latest values
        reduce { state.copy(inputUrl = rawUrl, inputNote = rawNote, error = null) }
        startGeneration(rawUrlInput = rawUrl, noteInput = rawNote)
    }

    fun onRegenerate() = intent {
        if (state.running) return@intent
        val url = state.lastUrl.ifBlank { state.inputUrl }
        if (url.isBlank()) {
            postSideEffect(AppSideEffect.Toast("Nothing to regenerate", ToastType.ERROR))
            return@intent
        }
        startGeneration(rawUrlInput = url, noteInput = state.lastNote)
    }

    fun onCancel() = intent {
        if (!state.running) return@intent
        scoopAiBridge.cancel()
        reduce {
            state.copy(
                running = false,
                currentPhase = null,
                messages = state.messages.filterNot { it.isTyping } +
                    agentMessage("Generation cancelled by user.", "error"),
            )
        }
        postSideEffect(AppSideEffect.Toast("Cancelled", ToastType.INFO))
    }

    fun onCopyDone() = intent {
        postSideEffect(AppSideEffect.Toast("Manifest copied to clipboard", ToastType.SUCCESS))
    }

    fun onTestInstall() = intent {
        if (state.running || state.testing) {
            postSideEffect(AppSideEffect.Toast("Another operation is running", ToastType.INFO))
            return@intent
        }
        val manifestJson = state.manifestJson
        if (manifestJson.isBlank()) {
            postSideEffect(AppSideEffect.Toast("No manifest to test install", ToastType.ERROR))
            return@intent
        }

        val baseName = state.suggestedAppName?.takeIf { it.isNotBlank() } ?: "app"
        val testName = "$baseName-scooper-test"
        val tempFile = File(System.getProperty("java.io.tmpdir"), "$testName.json")
        val startMsg = agentMessage(
            "Starting test install as $testName...",
            "test-install",
        )

        reduce {
            state.copy(
                testing = true,
                messages = state.messages + startMsg,
            )
        }

        var resultMsg: ChatMessage? = null
        try {
            val content = rewriteManifestName(manifestJson, testName)
            withContext(Dispatchers.IO) {
                tempFile.writeText(content)
            }

            val result = withContext(Dispatchers.IO) {
                scoopClient.testInstallManifest(testName, tempFile)
            }
            logger.info(
                "Test install finished for $testName: exitCode=${result.exitCode}, " +
                    "isSuccess=${result.isSuccess}, errorMessage=${result.errorMessage}",
            )

            if (result.isSuccess) {
                resultMsg = agentMessage(
                    "Test install passed for $testName. Package installed successfully.",
                    "done",
                )
                postSideEffect(
                    AppSideEffect.Toast("Test install succeeded: $testName", ToastType.SUCCESS),
                )
            } else {
                val detail = result.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "exit code ${result.exitCode}"
                val safe = sanitizeError(detail)
                resultMsg = agentMessage(
                    "Test install failed for $testName: $safe",
                    "error",
                )
                postSideEffect(
                    AppSideEffect.Toast("Test install failed: $testName", ToastType.ERROR),
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = e.message?.takeIf { it.isNotBlank() }
                ?: e::class.simpleName
                ?: "Test install failed"
            logger.error("Test install failed: $message")
            val safe = sanitizeError(message)
            resultMsg = agentMessage(safe, "error")
            postSideEffect(AppSideEffect.Toast(safe, ToastType.ERROR))
        } finally {
            try {
                withContext(Dispatchers.IO) {
                    cleanupTestInstall(testName, tempFile)
                }
            } catch (e: Exception) {
                logger.warn("Test install cleanup threw for $testName: ${e.message}")
            }
            val cleanupMsg = agentMessage(
                "Test install cleanup finished for $testName.",
                "test-install",
            )
            // Single reduce at end so intermediate success/error is not lost to Orbit races.
            reduce {
                state.copy(
                    testing = false,
                    messages = state.messages + listOfNotNull(resultMsg, cleanupMsg),
                )
            }
        }
    }

    fun onSave() = intent {
        postSideEffect(AppSideEffect.Toast("Save to Bucket coming soon", ToastType.INFO))
    }

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.startGeneration(
        rawUrlInput: String,
        noteInput: String,
    ) {
        val missing = scoopAiBridge.readSettings().missingReason()
        if (missing != null) {
            reduce {
                state.copy(
                    aiConfigured = false,
                    configHint = missing,
                    error = missing,
                )
            }
            postSideEffect(AppSideEffect.Toast(missing, ToastType.ERROR))
            return
        }

        val (urlFromField, extraFromField) = GithubUrlParser.extract(rawUrlInput)
        if (urlFromField == null) {
            val msg = "Please enter a GitHub repository URL (github.com/owner/repo)."
            reduce { state.copy(error = msg) }
            postSideEffect(AppSideEffect.Toast(msg, ToastType.ERROR))
            return
        }

        val userPrompt = listOfNotNull(
            extraFromField.ifBlank { null },
            noteInput.trim().ifBlank { null },
        ).joinToString(" ").ifBlank { null }
        val appGuess = GithubUrlParser.deriveAppName(urlFromField)

        reduce {
            state.copy(
                messages = state.messages +
                    ChatMessage(role = ChatRole.User, text = urlFromField, note = userPrompt) +
                    typingMessage("research"),
                running = true,
                currentPhase = "research",
                lastUrl = urlFromField,
                lastNote = userPrompt.orEmpty(),
                manifestJson = "",
                suggestedAppName = appGuess,
                error = null,
                aiConfigured = true,
                configHint = null,
                inputUrl = urlFromField,
                inputNote = noteInput,
            )
        }

        scoopAiBridge.startGenerate(
            url = urlFromField,
            userPrompt = userPrompt,
            onPhase = { phase -> handlePhase(phase) },
            onFinished = { result -> handleFinished(result) },
        )
    }

    private fun handlePhase(phase: String) = intent {
        if (!state.running) return@intent
        val withoutTyping = state.messages.filterNot { it.isTyping }
        reduce {
            state.copy(
                currentPhase = phase,
                messages = withoutTyping +
                    agentMessage(phaseMessage(phase), phase) +
                    typingMessage(phase),
            )
        }
    }

    private fun handleFinished(result: Result<ManifestResult>) = intent {
        val withoutTyping = state.messages.filterNot { it.isTyping }

        result.fold(
            onSuccess = { manifestResult ->
                if (manifestResult.noAssets) {
                    val name = manifestResult.suggestedAppName ?: state.suggestedAppName ?: "app"
                    reduce {
                        state.finishGeneration(
                            messages = withoutTyping +
                                agentMessage("No Windows assets found for this repository.", "error"),
                            error = "No Windows assets found",
                            suggestedAppName = name,
                        )
                    }
                    postSideEffect(AppSideEffect.Toast("No Windows assets found", ToastType.ERROR))
                    return@fold
                }

                val pretty = prettyManifest(manifestResult.manifest)
                val name = manifestResult.suggestedAppName
                    ?: state.suggestedAppName
                    ?: GithubUrlParser.deriveAppName(state.lastUrl)
                reduce {
                    state.finishGeneration(
                        messages = withoutTyping + agentMessage(
                            "Done. Suggested name: $name. Manifest ready (see card below).",
                            "done",
                        ),
                        error = null,
                        manifestJson = pretty,
                        suggestedAppName = name,
                    )
                }
                postSideEffect(AppSideEffect.Toast("Manifest generated: $name", ToastType.SUCCESS))
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    if (state.running) {
                        reduce {
                            state.finishGeneration(
                                messages = withoutTyping + agentMessage("Generation cancelled.", "error"),
                            )
                        }
                    }
                    return@fold
                }

                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: error::class.simpleName
                    ?: "Generation failed"
                logger.error("Create generate failed: $message")
                val safe = sanitizeError(message)
                reduce {
                    state.finishGeneration(
                        messages = withoutTyping + agentMessage(safe, "error"),
                        error = safe,
                    )
                }
                postSideEffect(AppSideEffect.Toast(safe, ToastType.ERROR))
            },
        )
    }

    private fun prettyManifest(raw: String): String {
        if (raw.isBlank()) return raw
        return try {
            val element = prettyJson.parseToJsonElement(raw)
            prettyJson.encodeToString(JsonElement.serializer(), element)
        } catch (_: Exception) {
            raw
        }
    }

    /** If the manifest has a top-level name field, rewrite it to the test app name. */
    private fun rewriteManifestName(raw: String, testName: String): String {
        return try {
            val element = prettyJson.parseToJsonElement(raw)
            val obj = element.jsonObject
            if (!obj.containsKey("name")) return raw
            val rewritten = JsonObject(
                obj.toMutableMap().apply { put("name", JsonPrimitive(testName)) },
            )
            prettyJson.encodeToString(JsonElement.serializer(), rewritten)
        } catch (_: Exception) {
            raw
        }
    }

    private suspend fun cleanupTestInstall(testName: String, tempFile: File) {
        try {
            val installed = scoopClient.localInstalledAppDirs.any {
                it.name.equals(testName, ignoreCase = true)
            }
            if (installed) {
                scoopClient.uninstall(
                    App(name = testName, latestVersion = "0"),
                    global = false,
                )
            }
            scoopClient.removeCache(testName)
        } catch (e: Exception) {
            logger.warn("Test install cleanup failed for $testName: ${e.message}")
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to delete temp manifest ${tempFile.absolutePath}: ${e.message}")
            }
        }
    }

    private fun sanitizeError(message: String): String {
        return message.replace(Regex("""sk-[A-Za-z0-9_-]{8,}"""), "sk-***")
    }

    private fun agentMessage(text: String, phase: String) = ChatMessage(
        role = ChatRole.Agent,
        text = text,
        phase = phase,
    )

    private fun typingMessage(phase: String) = ChatMessage(
        role = ChatRole.Agent,
        text = "",
        phase = phase,
        isTyping = true,
    )

    private fun CreateState.finishGeneration(
        messages: List<ChatMessage>,
        error: String? = this.error,
        manifestJson: String = this.manifestJson,
        suggestedAppName: String? = this.suggestedAppName,
    ) = copy(
        running = false,
        currentPhase = null,
        messages = messages,
        error = error,
        manifestJson = manifestJson,
        suggestedAppName = suggestedAppName,
    )

    private fun phaseMessage(phase: String): String = when (phase.lowercase()) {
        "research" -> "Researching repository metadata and latest release assets..."
        "analyze" -> "Analyzing Windows assets, computing hashes, and detecting binaries..."
        "generate" -> "Writing Scoop manifest (architecture, checkver, autoupdate)..."
        "refine" -> "Refining manifest based on validation / review feedback..."
        else -> "Working on phase: $phase..."
    }

    override fun close() {
        // Cancel in-flight generation only; bridge is a Koin singleton shared for app lifetime
        scoopAiBridge.cancel()
        supervisorJob.cancel()
    }
}

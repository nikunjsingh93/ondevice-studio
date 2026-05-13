package com.nikunj.gemmabuilder

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuilderViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BuilderUiState())
    val uiState = _uiState.asStateFlow()

    private var engine: BuilderEngine = DemoBuilderEngine()
    private var multimodalEngine: MediaPipeMultimodalEngine? = null
    private var prepared = false
    private var conversations: MutableList<ChatConversation> = mutableListOf()
    private var generationInProgress = false
    private var generationJob: Job? = null

    fun prepare(context: Context) {
        if (prepared) return
        prepared = true
        PDFBoxResourceLoader.init(context.applicationContext)

        conversations = loadConversations(context).toMutableList()
        if (conversations.isEmpty()) {
            val blank = newBlankConversation()
            conversations.add(blank)
            saveConversationFile(context, blank)
        }
        val active = conversations.maxByOrNull { it.updatedAt } ?: conversations.first()
        ensureProjectForConversation(context, active.id, migrateLegacy = true)
        refreshWorkspace(context, active.id)
        _uiState.update {
            val hasModel = modelFile(context).exists()
            it.copy(
                conversations = conversationInfos(),
                activeConversationId = active.id,
                messages = active.messages,
                modelName = savedModelName(context),
                modelSupportsMultimodal = isLikelyMultimodalModel(savedModelName(context)),
                multimodalBackendReady = false,
                activeRoutingMode = "text",
                modelReady = hasModel,
                chatFontScale = savedChatFontScale(context),
                codeFontScale = savedCodeFontScale(context),
                contextSizeChars = savedContextSizeChars(context),
                backendPreference = savedBackendPreference(context),
                speculativeDecodingEnabled = savedSpeculativeDecodingEnabled(context),
                status = if (hasModel) "${savedModelName(context)} found. Type a message and tap Send to auto-load." else "Import a .litertlm model from the ⋮ menu."
            )
        }
    }

    fun toggleSidebar() {
        _uiState.update { it.copy(sidebarOpen = !it.sidebarOpen) }
    }

    fun setChatFontScale(context: Context, scale: Float) {
        saveChatFontScale(context, scale)
        _uiState.update { it.copy(chatFontScale = scale) }
    }

    fun setCodeFontScale(context: Context, scale: Float) {
        saveCodeFontScale(context, scale)
        _uiState.update { it.copy(codeFontScale = scale) }
    }

    fun setContextSizeChars(context: Context, contextSizeChars: Int) {
        saveContextSizeChars(context, contextSizeChars)
        _uiState.update { it.copy(contextSizeChars = savedContextSizeChars(context)) }
        refreshWorkspace(context)
    }

    fun setBackendPreference(context: Context, backendPreference: String) {
        saveBackendPreference(context, backendPreference)
        _uiState.update {
            it.copy(
                backendPreference = savedBackendPreference(context),
                gemmaLoaded = false,
                engineLabel = "Not loaded",
                status = "Backend updated. Send a message to reload model."
            )
        }
    }

    fun setSpeculativeDecodingEnabled(context: Context, enabled: Boolean) {
        saveSpeculativeDecodingEnabled(context, enabled)
        _uiState.update {
            it.copy(
                speculativeDecodingEnabled = savedSpeculativeDecodingEnabled(context),
                gemmaLoaded = false,
                engineLabel = "Not loaded",
                status = "Speculative decoding setting updated. Send a message to reload model."
            )
        }
    }

    fun newConversation(context: Context) {
        val conv = newBlankConversation()
        conversations.add(0, conv)
        saveConversationFile(context, conv)
        ensureProjectForConversation(context, conv.id, migrateLegacy = false)
        refreshWorkspace(context, conv.id)
        _uiState.update {
            it.copy(
                activeConversationId = conv.id,
                messages = emptyList(),
                prompt = "",
                conversations = conversationInfos(),
                sidebarOpen = false,
                tab = 0,
                previewVersion = it.previewVersion + 1,
                lastWrittenPaths = emptyList(),
                lastRawResponse = "",
                status = "New chat started."
            )
        }
    }

    fun selectConversation(context: Context, id: String) {
        val conv = conversations.firstOrNull { it.id == id } ?: return
        ensureProjectForConversation(context, conv.id, migrateLegacy = false)
        refreshWorkspace(context, conv.id)
        _uiState.update {
            it.copy(
                activeConversationId = conv.id,
                messages = conv.messages,
                prompt = "",
                conversations = conversationInfos(),
                sidebarOpen = false,
                tab = 0,
                previewVersion = it.previewVersion + 1,
                status = "Opened ${conv.title}."
            )
        }
    }

    fun deleteConversation(context: Context, id: String) {
        if (generationInProgress || uiState.value.isBusy) {
            _uiState.update { it.copy(status = "Wait for the model to finish before deleting a chat.") }
            return
        }
        val deleted = conversations.firstOrNull { it.id == id } ?: return
        conversations = conversations.filterNot { it.id == id }.toMutableList()
        File(chatsRoot(context), "$id.chat").delete()
        File(projectsRoot(context), id).deleteRecursively()
        if (conversations.isEmpty()) {
            val fresh = newBlankConversation()
            conversations.add(fresh)
            saveConversationFile(context, fresh)
            ensureProjectForConversation(context, fresh.id, migrateLegacy = false)
        }
        val next = if (uiState.value.activeConversationId == id) {
            conversations.maxByOrNull { it.updatedAt } ?: conversations.first()
        } else {
            conversations.firstOrNull { it.id == uiState.value.activeConversationId } ?: conversations.maxByOrNull { it.updatedAt } ?: conversations.first()
        }
        ensureProjectForConversation(context, next.id, migrateLegacy = false)
        refreshWorkspace(context, next.id)
        _uiState.update {
            it.copy(
                activeConversationId = next.id,
                messages = next.messages,
                prompt = "",
                conversations = conversationInfos(),
                sidebarOpen = true,
                tab = 0,
                previewVersion = it.previewVersion + 1,
                status = "Deleted ${deleted.title}."
            )
        }
    }

    fun setPrompt(prompt: String) {
        _uiState.update { it.copy(prompt = prompt) }
    }

    fun setTab(tab: Int) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun reloadPreview() {
        _uiState.update { it.copy(previewVersion = it.previewVersion + 1) }
    }

    fun togglePreviewFullscreen() {
        _uiState.update { it.copy(previewFullscreen = !it.previewFullscreen, previewVersion = it.previewVersion + 1) }
    }

    fun toggleWorkPanelCollapsed() {
        _uiState.update { it.copy(workPanelCollapsed = !it.workPanelCollapsed) }
    }

    fun openFileInCode(context: Context, relativePath: String) {
        val result = runCatching {
            val root = activeProjectRoot(context)
            val file = safeResolve(root, relativePath)
            require(file.exists()) { "File does not exist: $relativePath" }
            val text = file.readText()
            _uiState.update {
                it.copy(
                    selectedCodePath = relativePath,
                    currentCode = text,
                    tab = 1,
                    status = "Opened $relativePath"
                )
            }
        }
        if (result.isFailure) {
            _uiState.update { it.copy(status = "Open file failed: ${result.exceptionOrNull()?.message ?: "unknown error"}") }
        }
    }

    fun deleteFile(context: Context, relativePath: String) {
        val result = runCatching {
            val root = activeProjectRoot(context)
            val file = safeResolve(root, relativePath)
            require(file.exists()) { "File does not exist: $relativePath" }
            require(file.delete()) { "Could not delete file: $relativePath" }
            refreshWorkspace(context)
        }
        _uiState.update {
            it.copy(
                status = if (result.isSuccess) "Deleted $relativePath" else "Delete failed: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                previewVersion = if (relativePath.equals("index.html", ignoreCase = true) && result.isSuccess) it.previewVersion + 1 else it.previewVersion
            )
        }
    }

    fun importModel(context: Context, uri: Uri) {
        viewModelScope.launch {
            val importedName = displayNameForUri(context.contentResolver, uri).ifBlank { "Gemma model" }
            val isLiteRtLm = importedName.lowercase(Locale.US).endsWith(".litertlm")
            if (!isLiteRtLm) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        status = "Only .litertlm models are supported. Selected: $importedName",
                        messages = it.messages + ChatMessage("assistant", "Model import blocked: only .litertlm files are allowed. Selected: $importedName")
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isBusy = true,
                    status = "Importing model into app storage...",
                    messages = it.messages + ChatMessage("assistant", "Importing model into app storage...")
                )
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    copyModel(context.contentResolver, uri, modelFile(context))
                    saveModelName(context, importedName)
                }
            }
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        isBusy = false,
                        modelName = importedName,
                        modelSupportsMultimodal = isLikelyMultimodalModel(importedName),
                        multimodalBackendReady = false,
                        activeRoutingMode = "text",
                        modelReady = true,
                        gemmaLoaded = false,
                        engineLabel = "Not loaded",
                        messages = it.messages + ChatMessage("assistant", "$importedName imported. Send a message to load it automatically."),
                        status = "$importedName imported. Type a message and tap Send to auto-load."
                    )
                } else {
                    it.copy(
                        isBusy = false,
                        messages = it.messages + ChatMessage("assistant", "Model import failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"),
                        status = "Model import failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun importFiles(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, status = "Importing file(s) into this chat...") }
            val importedNames = uris.map { uri ->
                sanitizeImportedFileName(displayNameForUri(context.contentResolver, uri, fallback = "imported-file"))
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val root = activeProjectRoot(context)
                    root.mkdirs()
                    uris.forEachIndexed { index, uri ->
                        val name = importedNames.getOrElse(index) { "imported-file" }
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Could not open selected file: $name" }
                            safeResolve(root, name).outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            refreshWorkspace(context)
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        isBusy = false,
                        status = "Imported ${uris.size} file(s) into this chat workspace.",
                        tab = 0,
                        previewVersion = it.previewVersion + 1,
                        lastWrittenPaths = importedNames,
                        pendingAttachments = (it.pendingAttachments + importedNames).distinct().takeLast(12)
                    )
                } else {
                    it.copy(
                        isBusy = false,
                        status = "File import failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun openImagePreview(context: Context, relativePath: String) {
        viewModelScope.launch {
            val result = runCatching {
                val root = activeProjectRoot(context)
                val file = safeResolve(root, relativePath)
                require(file.exists()) { "Image not found: $relativePath" }
                require(isPreviewableImageFile(relativePath)) { "Not an image file: $relativePath" }
                file.absolutePath
            }
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(previewImagePath = result.getOrNull())
                } else {
                    it.copy(status = "Image preview failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
                }
            }
        }
    }

    fun closeImagePreview() {
        _uiState.update { it.copy(previewImagePath = null) }
    }

    fun loadGemma(context: Context) {
        viewModelScope.launch { loadGemmaInternal(context.applicationContext) }
    }

    private suspend fun loadGemmaInternal(context: Context): Boolean {
        val file = modelFile(context)
        if (!file.exists()) {
            _uiState.update { it.copy(isBusy = false, status = "Import a .litertlm model from the ⋮ menu first.") }
            return false
        }
        _uiState.update { it.copy(isBusy = true, status = "Loading model. First load can take several seconds...") }
        val result = runCatching {
            val newEngine = LiteRtGemmaEngine(
                context = context.applicationContext,
                modelPath = file.absolutePath,
                backendPreference = uiState.value.backendPreference,
                speculativeDecodingEnabled = uiState.value.speculativeDecodingEnabled
            )
            newEngine.load()
            engine.close()
            engine = newEngine
            ensureMultimodalBackend(context)
        }
        _uiState.update {
            if (result.isSuccess) {
                it.copy(
                    isBusy = false,
                    gemmaLoaded = true,
                    engineLabel = savedModelName(context),
                    modelName = savedModelName(context),
                    modelSupportsMultimodal = isLikelyMultimodalModel(savedModelName(context)),
                    multimodalBackendReady = multimodalEngine?.isReady() == true,
                    activeRoutingMode = "text",
                    status = "${savedModelName(context)} loaded. Sending message..."
                )
            } else {
                it.copy(
                    isBusy = false,
                    gemmaLoaded = false,
                    engineLabel = "Not loaded",
                    status = "Model load failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                )
            }
        }
        return result.isSuccess
    }

    private fun ensureMultimodalBackend(context: Context) {
        if (!uiState.value.modelSupportsMultimodal) return
        if (multimodalEngine?.isReady() == true) return
        multimodalEngine = MediaPipeMultimodalEngine(context.applicationContext, modelFile(context).absolutePath).apply {
            load()
        }
    }

    fun generate(context: Context) {
        val prompt = uiState.value.prompt.trim()
        if (prompt.isBlank() || uiState.value.isBusy || generationInProgress) return
        generationInProgress = true

        generationJob = viewModelScope.launch {
            try {
                if (!uiState.value.modelReady) {
                    _uiState.update { it.copy(status = "Import a .litertlm model from the ⋮ menu first.") }
                    return@launch
                }
                if (!uiState.value.gemmaLoaded) {
                    val loaded = loadGemmaInternal(context.applicationContext)
                    if (!loaded) return@launch
                }

                val pendingAttachments = uiState.value.pendingAttachments
                val userMessage = ChatMessage("user", prompt, attachments = pendingAttachments)
                val hasImageOrAudioAttachment = pendingAttachments.any { path ->
                    isPreviewableImageFile(path) || isAudioFilePath(path)
                }
                val multimodalRoute = hasImageOrAudioAttachment &&
                    uiState.value.modelSupportsMultimodal &&
                    (multimodalEngine?.isReady() == true)
                val pendingMessages = uiState.value.messages + userMessage
                _uiState.update {
                    it.copy(
                        isBusy = true,
                        canStopGeneration = true,
                        prompt = "",
                        status = "Thinking...",
                        messages = pendingMessages,
                        streamingCode = "",
                        lastRawResponse = "",
                        tab = 1,
                        generationMetrics = null,
                        pendingAttachments = emptyList(),
                        activeRoutingMode = if (multimodalRoute) "multimodal" else "text"
                    )
                }
                persistActiveConversation(context, pendingMessages)
                val generationStartedAtMs = System.currentTimeMillis()
                var firstTokenAtMs: Long? = null

                if (multimodalRoute) {
                    val multimodalReply = withContext(Dispatchers.IO) {
                        multimodalEngine?.generateResponse(
                            prompt = prompt,
                            root = activeProjectRoot(context),
                            attachmentPaths = pendingAttachments
                        ).orEmpty()
                    }
                    if (multimodalReply.isNotBlank()) {
                        val metrics = buildGenerationMetrics(generationStartedAtMs, firstTokenAtMs)
                        val assistantMessage = ChatMessage("assistant", multimodalReply, statsInline = metrics)
                        val finalMessages = uiState.value.messages + assistantMessage
                        persistActiveConversation(context, finalMessages)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                status = "Multimodal response ready.",
                                messages = finalMessages,
                                streamingCode = "",
                                lastRawResponse = multimodalReply,
                                tab = 0,
                                generationMetrics = metrics
                            )
                        }
                        return@launch
                    }
                    _uiState.update {
                        it.copy(status = "Multimodal route unavailable for this input; falling back to text route.")
                    }
                }

                val basePrompt = withContext(Dispatchers.IO) { buildPrompt(context, prompt, pendingMessages, userMessage.attachments) }
                var responseText = ""
                var actions: List<WriteFileAction> = emptyList()
                var assistantReply: String? = null
                var lastError: Throwable? = null
                val maxAttempts = 4

                for (attempt in 1..maxAttempts) {
                    val output = StringBuilder()
                    var lastUiUpdate = 0L
                    val attemptPrompt = if (attempt == 1) {
                        basePrompt
                    } else {
                        """
$basePrompt

RETRY ATTEMPT $attempt OF $maxAttempts:
Your previous response was incomplete. Return complete XML write_file action(s) only, with every opened tag properly closed (including </content> and </action>). Do not stream explanations.
                        """.trimIndent()
                    }

                    _uiState.update {
                        it.copy(
                            status = if (attempt == 1) "Thinking..." else "Retrying $attempt/$maxAttempts...",
                            streamingCode = if (attempt == 1) "" else it.streamingCode
                        )
                    }

                    val result = runCatching {
                        engine.generate(attemptPrompt).collect { chunk ->
                            if (firstTokenAtMs == null && chunk.isNotBlank()) {
                                firstTokenAtMs = System.currentTimeMillis()
                            }
                            output.append(chunk)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate > 220L || output.length < 1000) {
                                lastUiUpdate = now
                                val streamed = output.toString()
                                _uiState.update {
                                    it.copy(
                                        status = "Thinking... attempt $attempt/$maxAttempts • ${output.length} chars",
                                        lastRawResponse = streamed,
                                        streamingCode = extractStreamingCode(streamed)
                                    )
                                }
                            }
                        }
                    }

                    if (result.isFailure) {
                        lastError = result.exceptionOrNull()
                        if (attempt < maxAttempts) continue
                    }

                    responseText = output.toString()
                    _uiState.update {
                        it.copy(
                            status = "Checking generated file...",
                            lastRawResponse = responseText,
                            streamingCode = extractStreamingCode(responseText)
                        )
                    }
                    actions = normalizeWriteActions(recoverWriteFileActions(responseText))
                    assistantReply = recoverAssistantReply(responseText)
                    if (actions.isNotEmpty() && actions.all { isCompleteEnoughForWriting(it) }) {
                        break
                    }
                    if (!assistantReply.isNullOrBlank()) {
                        break
                    }
                    if (attempt < maxAttempts) {
                        _uiState.update { it.copy(status = "Generated file looked incomplete. Retrying ${attempt + 1}/$maxAttempts...") }
                    }
                }

                if (actions.isEmpty() && !assistantReply.isNullOrBlank()) {
                    val metrics = buildGenerationMetrics(generationStartedAtMs, firstTokenAtMs)
                    val reply = assistantReply!!.trim()
                    val finalMessages = uiState.value.messages + ChatMessage("assistant", reply, statsInline = metrics)
                    persistActiveConversation(context, finalMessages)
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            status = "Answered.",
                            lastRawResponse = responseText,
                            streamingCode = "",
                            messages = finalMessages,
                            tab = 0,
                            generationMetrics = metrics
                        )
                    }
                    return@launch
                }

                if (actions.isEmpty() || actions.any { !isCompleteEnoughForWriting(it) }) {
                    val metrics = buildGenerationMetrics(generationStartedAtMs, firstTokenAtMs)
                    val message = if (lastError != null) {
                        "Generation failed after 3 retries. The previous working project was kept. Try a shorter request."
                    } else {
                        "The model stopped before finishing write actions after 3 retries. The previous working project was kept. Try asking for a shorter complete rewrite."
                    }
                    val finalMessages = uiState.value.messages + ChatMessage("assistant", message, statsInline = metrics)
                    persistActiveConversation(context, finalMessages)
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            status = message,
                            lastRawResponse = responseText,
                            streamingCode = "",
                            messages = finalMessages,
                            tab = 1,
                            generationMetrics = metrics
                        )
                    }
                    return@launch
                }

                val written = runCatching {
                    withContext(Dispatchers.IO) {
                        val root = activeProjectRoot(context)
                        actions.forEach { writeAction(root, it) }
                    }
                }

                if (written.isSuccess) {
                    val metrics = buildGenerationMetrics(generationStartedAtMs, firstTokenAtMs)
                    refreshWorkspace(context)
                    val assistantText = assistantReply?.takeIf { it.isNotBlank() }?.trim() ?: "Updated preview."
                    val assistantMessage = ChatMessage("assistant", assistantText, statsInline = metrics)
                    val finalMessages = uiState.value.messages + assistantMessage
                    val preferredPath = actions.firstOrNull { it.path.equals("index.html", ignoreCase = true) }?.path
                        ?: actions.firstOrNull()?.path
                        ?: uiState.value.selectedCodePath
                    persistActiveConversation(context, finalMessages)
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            status = "Wrote ${actions.size} file action(s) and refreshed preview.",
                            previewVersion = it.previewVersion + 1,
                            tab = 0,
                            selectedCodePath = preferredPath,
                            lastWrittenPaths = actions.map { a -> a.path },
                            lastRawResponse = responseText,
                            streamingCode = "",
                            messages = finalMessages,
                            conversations = conversationInfos(),
                            generationMetrics = metrics
                        )
                    }
                } else {
                    val metrics = buildGenerationMetrics(generationStartedAtMs, firstTokenAtMs)
                    val finalMessages = uiState.value.messages + ChatMessage("assistant", "I generated a complete file, but the app could not save it.", statsInline = metrics)
                    persistActiveConversation(context, finalMessages)
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            status = "Could not write files: ${written.exceptionOrNull()?.message ?: "unknown error"}",
                            lastRawResponse = responseText,
                            streamingCode = "",
                            messages = finalMessages,
                            generationMetrics = metrics
                        )
                    }
                }
            } finally {
                generationInProgress = false
                generationJob = null
                _uiState.update { it.copy(isBusy = false, canStopGeneration = false) }
            }
        }
    }

    fun stopGeneration() {
        val job = generationJob
        if (job != null && job.isActive) {
            job.cancel()
            generationInProgress = false
            _uiState.update {
                it.copy(
                    isBusy = false,
                    canStopGeneration = false,
                    status = "Stopped.",
                    streamingCode = ""
                )
            }
        }
    }

    fun openInBrowser(context: Context) {
        viewModelScope.launch {
            val result = runCatching {
                val index = File(activeProjectRoot(context), "index.html")
                require(index.exists()) { "No index.html to open." }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", index)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/html")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Open HTML in browser").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            _uiState.update { it.copy(status = if (result.isSuccess) "Opening in browser..." else "Open in browser failed: ${result.exceptionOrNull()?.message ?: "unknown error"}") }
        }
    }

    fun exportFile(context: Context, relativePath: String, destination: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val source = safeResolve(activeProjectRoot(context), relativePath)
                    require(source.exists()) { "File does not exist: $relativePath" }
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Could not open destination file." }
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
            _uiState.update { it.copy(status = if (result.isSuccess) "Saved $relativePath" else "Save failed: ${result.exceptionOrNull()?.message ?: "unknown error"}") }
        }
    }

    fun exportProjectZip(context: Context, destination: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val root = activeProjectRoot(context)
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Could not open destination ZIP." }
                        ZipOutputStream(output).use { zip ->
                            root.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = file.relativeTo(root).path.replace('\\', '/')
                                zip.putNextEntry(ZipEntry(entryName))
                                file.inputStream().use { input -> input.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            _uiState.update { it.copy(status = if (result.isSuccess) "Saved project ZIP." else "ZIP save failed: ${result.exceptionOrNull()?.message ?: "unknown error"}") }
        }
    }

    fun exportPwaReadyProjectZip(context: Context, destination: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val root = activeProjectRoot(context)
                    ensurePwaFiles(root)
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Could not open destination ZIP." }
                        ZipOutputStream(output).use { zip ->
                            root.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = file.relativeTo(root).path.replace('\\', '/')
                                zip.putNextEntry(ZipEntry(entryName))
                                file.inputStream().use { input -> input.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            _uiState.update {
                it.copy(
                    status = if (result.isSuccess) {
                        "Saved PWA-ready ZIP (with manifest.webmanifest and service-worker.js)."
                    } else {
                        "PWA ZIP save failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    }
                )
            }
            if (result.isSuccess) {
                refreshWorkspace(context)
            }
        }
    }

    private fun activeProjectRoot(context: Context): File {
        val id = uiState.value.activeConversationId ?: conversations.firstOrNull()?.id
        require(!id.isNullOrBlank()) { "No active conversation." }
        return ensureProjectForConversation(context, id, migrateLegacy = false)
    }

    private fun refreshWorkspace(context: Context, conversationId: String? = null) {
        val root = if (conversationId != null) {
            ensureProjectForConversation(context, conversationId, migrateLegacy = false)
        } else {
            activeProjectRoot(context)
        }
        val index = File(root, "index.html")
        val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()
        val selected = uiState.value.selectedCodePath
        val selectedFile = runCatching { safeResolve(root, selected) }.getOrNull()
        val effectivePath = when {
            selectedFile?.exists() == true -> selected
            index.exists() -> "index.html"
            files.isNotEmpty() -> files.first()
            else -> "index.html"
        }
        val current = runCatching { safeResolve(root, effectivePath).readText() }.getOrDefault("")
        val modelNameNow = savedModelName(context)
        val estimatedFileContextChars = estimateFileContextChars(
            root = root,
            maxTotalChars = uiState.value.contextSizeChars
        )
        _uiState.update {
            it.copy(
                indexHtmlPath = index.absolutePath,
                selectedCodePath = effectivePath,
                currentCode = current,
                files = files,
                estimatedFileContextChars = estimatedFileContextChars,
                modelName = modelNameNow,
                modelSupportsMultimodal = isLikelyMultimodalModel(modelNameNow),
                multimodalBackendReady = multimodalEngine?.isReady() == true,
                modelReady = modelFile(context).exists()
            )
        }
    }

    private fun estimateFileContextChars(root: File, maxTotalChars: Int): Int {
        val perFileLimit = 5000
        var total = 0
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') to it }
            .filter { (path, _) -> !path.equals("index.html", ignoreCase = true) }
            .toList()
        if (files.isEmpty()) return 0

        // Mirror buildProjectContextForModel framing overhead for a closer estimate.
        files.forEach { (path, file) ->
            if (total >= maxTotalChars) return@forEach
            val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
            val bodyChars = when {
                vmIsTextExtension(ext) -> minOf(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), perFileLimit)
                ext == "docx" || ext == "pdf" || vmIsImageExtension(ext) || vmIsAudioExtension(ext) -> 600
                else -> 0
            }
            if (bodyChars <= 0) return@forEach
            val wrapped = bodyChars + path.length + 28 // <<<FILE:path>>> + <<<END_FILE>>> + newlines
            val remaining = maxTotalChars - total
            total += minOf(wrapped, remaining)
        }
        return total.coerceAtMost(maxTotalChars)
    }

    private fun vmIsTextExtension(ext: String): Boolean = ext in setOf(
        "txt", "md", "json", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "sass",
        "html", "htm", "xml", "csv", "tsv", "yaml", "yml", "toml", "ini", "log"
    )

    private fun vmIsImageExtension(ext: String): Boolean = ext in setOf(
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif"
    )

    private fun vmIsAudioExtension(ext: String): Boolean = ext in setOf(
        "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "3gp", "amr"
    )

    private suspend fun buildPrompt(
        context: Context,
        userRequest: String,
        messages: List<ChatMessage>,
        preferredAttachmentPaths: List<String> = emptyList()
    ): String {
        val root = activeProjectRoot(context)
        val current = htmlContextForModel(File(root, "index.html").takeIf { it.exists() }?.readText().orEmpty())
        val fileContext = buildProjectContextForModel(
            context = context,
            root = root,
            preferredPaths = preferredAttachmentPaths,
            onlyPreferredWhenProvided = preferredAttachmentPaths.isNotEmpty(),
            maxTotalChars = uiState.value.contextSizeChars
        )
        val multimodalHint = if (uiState.value.modelSupportsMultimodal) {
            "Model capability routing: multimodal-capable model detected. Prioritize attached image/audio understanding first, then use extracted file text."
        } else {
            "Model capability routing: text-only model path. Use extracted OCR/transcript/file text provided by the app."
        }
        val recent = messages.takeLast(8).joinToString("\n") { msg ->
            "${msg.role}: ${msg.text.take(1000)}"
        }
        return """
$BUILD_SYSTEM_PROMPT

Recent chat:
$recent

Current index.html (if present):
```html
$current
```

Additional imported/generated files context:
$fileContext

$multimodalHint

Important instructions for this turn:
- The app has already provided readable content from local imported files above (including extracted text from PDF/image/DOCX when available).
- Do not say you cannot access local files. Use the provided file context directly.
- If the user asks a question or asks for explanation only, return one reply action and do not modify files.
- Return one or more write_file action(s) when file changes are needed.
- Never include explanations or markdown before or after the XML action.
- Never stop inside the XML action. Always finish with </content> and </action>.
- Keep the generated file compact enough for mobile. Avoid unnecessary comments, huge CSS blocks, or repeated code.
- Do not put the XML action in a chat message; the app will parse it silently.
- If the user asks for a completely different app or design, replace the current app completely.
- Do not preserve the old calculator or previous UI unless the user explicitly asks to keep it.
- You may create multiple files (for example index.html, styles.css, app.js, data.json, assets/*) when useful.
- If index.html references local files (for example styles.css or app.js), include write_file actions for each referenced file in the same response.
- Center the generated app/page by default using a full viewport layout such as min-height:100vh and display:grid/place-items:center or flexbox centering.
- Make the layout responsive for phone screens, avoid fixed desktop-only widths, and use width:min(92vw, ...px) for main cards.
- Avoid referencing missing functions or variables; make the generated app fully runnable offline from local files.
- If editing an existing project, update only the files needed and keep paths relative to the project root.

User request:
$userRequest

Return the XML action(s) now.
        """.trimIndent()
    }

    private fun persistActiveConversation(context: Context, messages: List<ChatMessage>) {
        val id = uiState.value.activeConversationId ?: return
        val old = conversations.firstOrNull { it.id == id } ?: ChatConversation(id, "New chat", System.currentTimeMillis(), emptyList())
        val firstUser = messages.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
        val title = if (old.title == "New chat" && firstUser.isNotBlank()) firstUser.take(42) else old.title
        val updated = old.copy(title = title, updatedAt = System.currentTimeMillis(), messages = messages)
        conversations = (conversations.filterNot { it.id == id } + updated).sortedByDescending { it.updatedAt }.toMutableList()
        saveConversationFile(context, updated)
        _uiState.update { it.copy(conversations = conversationInfos(), activeConversationId = id, messages = messages) }
    }

    private fun buildGenerationMetrics(startedAtMs: Long, firstTokenAtMs: Long?): String {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
        val elapsedSec = elapsedMs / 1000.0
        val ttftMs = (firstTokenAtMs?.minus(startedAtMs) ?: elapsedMs).coerceAtLeast(1L)
        val ttftSec = ttftMs / 1000.0
        return "• ${String.format(Locale.US, "%.2f", elapsedSec)}s • ~${String.format(Locale.US, "%.2f", ttftSec)}s TTFT"
    }

    private fun conversationInfos(): List<ConversationInfo> = conversations
        .sortedByDescending { it.updatedAt }
        .map { ConversationInfo(it.id, it.title, it.updatedAt, it.messages.size) }

    override fun onCleared() {
        super.onCleared()
        engine.close()
        multimodalEngine?.close()
        multimodalEngine = null
    }
}

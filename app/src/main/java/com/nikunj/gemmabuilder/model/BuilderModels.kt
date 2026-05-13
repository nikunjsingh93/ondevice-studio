package com.nikunj.gemmabuilder

import kotlinx.coroutines.flow.Flow
import java.util.Locale

fun isCodeFile(path: String): Boolean {
    val lower = path.lowercase(Locale.US)
    return lower.endsWith(".html") ||
        lower.endsWith(".htm") ||
        lower.endsWith(".css") ||
        lower.endsWith(".js") ||
        lower.endsWith(".mjs") ||
        lower.endsWith(".json") ||
        lower.endsWith(".txt") ||
        lower.endsWith(".md") ||
        lower.endsWith(".xml")
}

fun isPreviewableImageFile(path: String): Boolean {
    val lower = path.lowercase(Locale.US)
    return lower.endsWith(".png") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".bmp") ||
        lower.endsWith(".gif") ||
        lower.endsWith(".heic") ||
        lower.endsWith(".heif")
}

fun isAudioFilePath(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
    return isAudioExtension(ext)
}

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<String> = emptyList(),
    val statsInline: String? = null
)

data class ChatConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>
)

data class ConversationInfo(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

data class BuilderUiState(
    val prompt: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<ConversationInfo> = emptyList(),
    val activeConversationId: String? = null,
    val sidebarOpen: Boolean = false,
    val engineLabel: String = "Not loaded",
    val status: String = "Import a .litertlm model to begin.",
    val modelName: String = "Gemma model",
    val modelSupportsMultimodal: Boolean = false,
    val multimodalBackendReady: Boolean = false,
    val activeRoutingMode: String = "text",
    val modelReady: Boolean = false,
    val gemmaLoaded: Boolean = false,
    val isBusy: Boolean = false,
    val canStopGeneration: Boolean = false,
    val previewVersion: Int = 0,
    val tab: Int = 0,
    val indexHtmlPath: String? = null,
    val selectedCodePath: String = "index.html",
    val currentCode: String = "",
    val files: List<String> = emptyList(),
    val lastWrittenPaths: List<String> = emptyList(),
    val lastRawResponse: String = "",
    val streamingCode: String = "",
    val previewFullscreen: Boolean = false,
    val workPanelCollapsed: Boolean = false,
    val chatFontScale: Float = 1.2f,
    val codeFontScale: Float = 1f,
    val contextSizeChars: Int = 24000,
    val estimatedFileContextChars: Int = 0,
    val pendingAttachments: List<String> = emptyList(),
    val generationMetrics: String? = null,
    val previewImagePath: String? = null
)

data class WriteFileAction(val path: String, val content: String)

interface BuilderEngine {
    fun generate(prompt: String): Flow<String>
    fun close() {}
}

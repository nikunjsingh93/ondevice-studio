package com.nikunj.gemmabuilder

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

fun sanitizeImportedFileName(name: String): String {
    val normalized = name
        .replace("\\", "/")
        .substringAfterLast('/')
        .trim()
        .ifBlank { "imported-file" }
    return normalized.replace(Regex("""[^\w.\- ]"""), "_")
}

fun writeAction(root: File, action: WriteFileAction) {
    val out = safeResolve(root, action.path)
    out.parentFile?.mkdirs()
    out.writeText(action.content)
}

fun projectRoot(context: Context, conversationId: String): File = File(projectsRoot(context), conversationId)

fun legacyProjectRoot(context: Context): File = File(projectsRoot(context), "default")

fun ensureProjectForConversation(context: Context, conversationId: String, migrateLegacy: Boolean): File {
    val root = projectRoot(context, conversationId)
    root.mkdirs()
    val index = File(root, "index.html")
    if (!index.exists()) {
        val legacy = legacyProjectRoot(context)
        val shouldMigrate = migrateLegacy && legacy.exists() && legacy.walkTopDown().any { it.isFile }
        if (shouldMigrate) {
            legacy.walkTopDown().filter { it.isFile }.forEach { source ->
                val destination = File(root, source.relativeTo(legacy).path)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
        } else {
            index.writeText(welcomeHtml())
        }
    }
    return root
}

fun savedModelName(context: Context): String =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("modelName", null)
        ?.takeIf { it.isNotBlank() }
        ?: modelFile(context).takeIf { it.exists() }?.name
        ?: "Gemma model"

fun isLikelyMultimodalModel(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return lower.contains("multimodal") ||
        lower.contains("vision") ||
        lower.contains("audio") ||
        lower.contains("3n") ||
        lower.contains("e2b")
}

fun saveModelName(context: Context, name: String) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("modelName", name.ifBlank { "Gemma model" })
        .apply()
}

fun savedChatFontScale(context: Context): Float =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getFloat("chatFontScale", 1.2f)
        .coerceIn(0.8f, 1.8f)

fun saveChatFontScale(context: Context, scale: Float) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putFloat("chatFontScale", scale.coerceIn(0.8f, 1.8f))
        .apply()
}

fun savedCodeFontScale(context: Context): Float =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getFloat("codeFontScale", 1f)
        .coerceIn(0.8f, 1.8f)

fun saveCodeFontScale(context: Context, scale: Float) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putFloat("codeFontScale", scale.coerceIn(0.8f, 1.8f))
        .apply()
}

fun displayNameForUri(resolver: ContentResolver, uri: Uri, fallback: String = "Gemma model"): String {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index).orEmpty()
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { fallback } ?: fallback
}

fun modelFile(context: Context): File = File(context.filesDir, "models/gemma-4-e2b.litertlm")

fun copyModel(resolver: ContentResolver, uri: Uri, outFile: File) {
    outFile.parentFile?.mkdirs()
    resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not open selected file." }
        outFile.outputStream().use { output -> input.copyTo(output) }
    }
}


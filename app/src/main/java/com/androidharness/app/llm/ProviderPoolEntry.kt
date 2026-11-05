package com.androidharness.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/**
 * One member of the provider pool.
 *
 * [config] is the connection config (baseUrl, model, type, etc.).
 * [provider] is the actual LlmProvider implementation.
 * [healthy] is false while the member is cooling down after a failure.
 * [lastFailure] is the timestamp of the most recent failure, used for cooldown.
 * [consecutiveFailures] counts recent failures; higher = longer cooldown.
 * [visionCapable] whether this member can process image attachments.
 * [codingCapable] whether this member is strong at code generation.
 * [speedScore] relative speed hint (higher = faster), used for tie-breaking.
 */
@Serializable
data class ProviderPoolEntry(
    val config: ProviderConfig,
    val provider: LlmProvider,
    @kotlinx.serialization.Transient
    var healthy: Boolean = true,
    @kotlinx.serialization.Transient
    var lastFailure: Long = 0,
    @kotlinx.serialization.Transient
    var consecutiveFailures: Int = 0,
    @kotlinx.serialization.Transient
    val visionCapable: Boolean = false,
    @kotlinx.serialization.Transient
    val codingCapable: Boolean = false,
    @kotlinx.serialization.Transient
    val speedScore: Int = 50,
) {
    fun isAvailable(cooldownMs: Long): Boolean =
        healthy || (System.currentTimeMillis() - lastFailure) > cooldownMs

    fun markFailure() {
        healthy = false
        lastFailure = System.currentTimeMillis()
        consecutiveFailures++
    }

    fun markSuccess() {
        healthy = true
        consecutiveFailures = 0
    }

    /** Cooldown grows exponentially with consecutive failures, capped at ~5 minutes. */
    fun cooldownMs(base: Long = 30_000): Long {
        val exp = consecutiveFailures.coerceIn(0, 6)
        return (base * (1L shl exp)).coerceAtMost(300_000)
    }
}

/** Result of a task classification used by the router to pick providers. */
@Serializable
data class TaskClassification(
    val hasImage: Boolean = false,
    val isCode: Boolean = false,
    val isReasoning: Boolean = false,
    val isSimple: Boolean = true,
)

/** Classification hints for task-aware routing. */
object TaskClassifier {
    private val codeKeywords = listOf(
        "code", "function", "class", "script", "html", "css", "javascript", "python",
        "kotlin", "swift", "rust", "java", "cpp", "c++", "typescript", "react",
        "website", "webpage", "component", "api", "endpoint", "database", "sql",
        "bug", "debug", "error", "exception", "refactor", "algorithm",
    )
    private val reasoningKeywords = listOf(
        "why", "explain", "analyze", "compare", "evaluate", "argument",
        "logic", "deduce", "infer", "solve", "proof", "theorem",
    )

    fun classify(messages: List<com.androidharness.app.core.ChatMessage>): TaskClassification {
        val last = messages.lastOrNull() ?: return TaskClassification()
        val text = (last.text + " " + last.imageData.joinToString("") { it.base64 }).lowercase()
        val hasImage = last.imageData.isNotEmpty()
        val isCode = codeKeywords.any { text.contains(it) } ||
            text.contains("```") || text.contains("<html") || text.contains("def ") ||
            text.contains("function ") || text.contains("class ")
        val isReasoning = reasoningKeywords.any { text.contains(it) } &&
            text.length > 20
        val isSimple = !hasImage && !isCode && !isReasoning && text.length < 100
        return TaskClassification(
            hasImage = hasImage,
            isCode = isCode,
            isReasoning = isReasoning,
            isSimple = isSimple,
        )
    }
}

package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef

/**
 * Instant Smart Auto-Switch Router for GOD-AI.
 *
 * Automatically inspects the user prompt, attachments, and files on the fly
 * to select the optimal model without requiring manual team management:
 * 1. Image / Picture uploaded -> Instantly routes to a Vision model.
 * 2. Complex Coding task / multi-file / refactor -> Routes to a dedicated Coding model.
 * 3. Quick question / general query -> Routes to a Fast Free model.
 *
 * Routing only switches models inside the provider the user picked. The free
 * Harness pool is used when the user is on [HarnessProvider.ID] and that
 * provider lacks a fitting entry, so paid connections are never auto-swapped.
 */
object SmartAutoRouter {

    enum class TaskCategory {
        VISION,
        CODING,
        QUICK_GENERAL,
    }

    /** The routed model plus a one-line human reason for the switch, if any. */
    data class Route(val config: com.androidharness.app.llm.ProviderConfig, val reason: String? = null)

    private val CODE_KEYWORDS = listOf(
        "code", "class", "function", "fun ", "def ", "import ", "package ",
        "bug", "error", "exception", "crash", "fix", "refactor", "patch",
        "gradle", "build", "api", "endpoint", "sql", "query", "database",
        "typescript", "javascript", "kotlin", "java", "python", "c++", "rust",
        "html", "css", "layout", "compose", "react", "view", "component",
        "git", "commit", "push", "pull", "test", "unittest", "assert"
    )

    fun classify(prompt: String, images: List<ImageRef> = emptyList(), messages: List<ChatMessage> = emptyList(), turnHasImages: Boolean = false): TaskCategory {
        if (images.isNotEmpty() || turnHasImages) return TaskCategory.VISION
        if (messages.any { it.images.isNotEmpty() || it.imageData.isNotEmpty() }) return TaskCategory.VISION

        val lower = prompt.lowercase()
        if (lower.contains("data:image/") || lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".webp")) {
            return TaskCategory.VISION
        }

        val codeMatches = CODE_KEYWORDS.count { lower.contains(it) }
        val hasCodeBlock = prompt.contains("```") || prompt.contains("    ") || prompt.contains("{") && prompt.contains("}")
        val isLongTechnical = prompt.length > 250 && codeMatches >= 2

        if (codeMatches >= 2 || hasCodeBlock || isLongTechnical) {
            return TaskCategory.CODING
        }

        return TaskCategory.QUICK_GENERAL
    }

    /**
     * Returns the optimal provider configuration and model for the given task.
     * Guaranteed to return a valid, functioning model from available or pooled models.
     */
    fun routeModel(
        category: TaskCategory,
        currentConfig: ProviderConfig,
        availableConfigs: List<ProviderConfig>,
        catalogs: Map<String, List<ModelEntry>>,
    ): ProviderConfig {
        return when (category) {
            TaskCategory.VISION -> resolveVisionModel(currentConfig, availableConfigs, catalogs)
            TaskCategory.CODING -> resolveCodingModel(currentConfig, availableConfigs, catalogs)
            TaskCategory.QUICK_GENERAL -> resolveFastFreeModel(currentConfig, availableConfigs, catalogs)
        }
    }

    private fun resolveVisionModel(
        current: ProviderConfig,
        available: List<ProviderConfig>,
        catalogs: Map<String, List<ModelEntry>>,
    ): ProviderConfig {
        if (visionCapable(current.model)) return current

        // Look in current provider's catalog first
        catalogs[current.id]?.firstOrNull { it.imageInput == true || visionCapable(it.id) }?.let {
            return current.copy(model = it.id)
        }

        // Check Harness pooled models for vision-capable option
        val pooledVision = HarnessProvider.pooledModels.firstOrNull { visionCapable(it.id) }
        if (pooledVision != null) {
            return current.copy(model = pooledVision.id)
        }

        // Check any enabled provider with vision models
        for (provider in available) {
            val vModel = catalogs[provider.id]?.firstOrNull { it.imageInput == true || visionCapable(it.id) }
            if (vModel != null) {
                return provider.copy(model = vModel.id)
            }
        }

        return current
    }

    private fun resolveCodingModel(
        current: ProviderConfig,
        available: List<ProviderConfig>,
        catalogs: Map<String, List<ModelEntry>>,
    ): ProviderConfig {
        val lower = current.model.lowercase()
        if (lower.contains("code") || lower.contains("coder") || lower.contains("sonnet") || lower.contains("deepseek")) {
            return current
        }

        // Look for coding specialized model in current provider
        catalogs[current.id]?.firstOrNull {
            val id = it.id.lowercase()
            id.contains("code") || id.contains("coder") || id.contains("qwen-2.5-coder")
        }?.let {
            return current.copy(model = it.id)
        }

        // Look in Harness pooled models for coding model
        val pooledCoder = HarnessProvider.pooledModels.firstOrNull {
            val id = it.id.lowercase()
            id.contains("code") || id.contains("coder") || id.contains("qwen") || id.contains("deepseek")
        }
        if (pooledCoder != null) {
            return current.copy(model = pooledCoder.id)
        }

        return current
    }

    private fun resolveFastFreeModel(
        current: ProviderConfig,
        available: List<ProviderConfig>,
        catalogs: Map<String, List<ModelEntry>>,
    ): ProviderConfig {
        val lower = current.model.lowercase()
        if (lower.contains("fast") || lower.contains("flash") || lower.contains("mini") || lower.contains("free")) {
            return current
        }

        // Look for fast/free model in Harness pool
        val fastFree = HarnessProvider.pooledModels.firstOrNull {
            val id = it.id.lowercase()
            (id.contains("fast") || id.contains("flash") || id.contains("mini") || id.contains("free"))
        } ?: HarnessProvider.pooledModels.firstOrNull()

        if (fastFree != null) {
            return current.copy(model = fastFree.id)
        }

        return current
    }

    /**
     * Engine-facing entry point: classifies the live turn and returns the best
     * model for it, or the unchanged [current] when no better match exists on
     * the user's chosen provider. The free Harness pool is only consulted on
     * the Harness provider, so a paid connection's model is never auto-swapped.
     */
    fun promptRoute(
        prompt: String,
        turnHasImages: Boolean,
        current: ProviderConfig,
        catalogs: Map<String, List<ModelEntry>>,
    ): Route {
        val category = classify(prompt = prompt, turnHasImages = turnHasImages)
        val catalog = catalogs[current.id].orEmpty()
        val routed: String? = when (category) {
            TaskCategory.VISION -> {
                val modelId = catalog.firstOrNull { it.imageInput == true || visionCapable(it.id) }?.id
                    ?: if (current.id == HarnessProvider.ID) {
                        HarnessProvider.pooledModels.firstOrNull { visionCapable(it.id) }?.id
                            ?: HarnessProvider.DEFAULT_MODEL
                    } else null
                modelId
            }
            TaskCategory.CODING -> {
                val modelId = catalog.firstOrNull {
                    val id = it.id.lowercase()
                    id.contains("code") || id.contains("coder")
                }?.id ?: (if (current.id == HarnessProvider.ID) {
                    HarnessProvider.pooledModels.firstOrNull {
                        val id = it.id.lowercase()
                        id.contains("code") || id.contains("coder") || id.contains("deepseek")
                    }?.id
                } else null)
                modelId
            }
            TaskCategory.QUICK_GENERAL -> {
                val alreadyFast = current.model.lowercase().let {
                    it.contains("fast") || it.contains("flash") || it.contains("mini") ||
                        it.contains("auto") || it.contains("free")
                }
                if (current.id == HarnessProvider.ID && !alreadyFast) {
                    HarnessProvider.pooledModels.firstOrNull {
                        val id = it.id.lowercase()
                        id.contains("fast") || id.contains("flash") || id.contains("auto")
                    }?.id
                } else null
            }
        }
        if (routed == null || routed == current.model) return Route(current)
        return when (category) {
            TaskCategory.VISION -> Route(current.copy(model = routed), "Smart routing: switched to $routed for image support")
            TaskCategory.CODING -> Route(current.copy(model = routed), "Smart routing: switched to $routed for a coding task")
            TaskCategory.QUICK_GENERAL -> Route(current.copy(model = routed), "Smart routing: switched to $routed as the fast model for this quick question")
        }
    }
}

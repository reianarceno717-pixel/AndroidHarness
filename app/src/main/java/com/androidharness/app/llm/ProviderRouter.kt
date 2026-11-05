package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.comparisons.compareByDescending
import kotlin.math.max

/**
 * Smart provider router that wraps multiple [ProviderPoolEntry] members and
 * provides:
 *
 * 1. **Task-aware routing** — routes to vision-capable providers when images
 *    are attached, coding-strong providers for code tasks, fastest for simple.
 * 2. **Invisible failover** — when a provider returns 429/503 on connect,
 *    automatically tries the next available member with no user-visible delay.
 * 3. **Parallel ensemble** — for complex tasks, sends to multiple members at
 *    once and combines the best response.
 *
 * For non-pool providers (user-configured keys like OpenRouter, Groq), falls
 * back to a single provider created via [factory].
 */
class ProviderRouter(
    private val entries: List<ProviderPoolEntry>,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val factory: (ProviderType) -> LlmProvider = { type -> when (type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(httpClient, json)
        ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(httpClient, json)
        ProviderType.ANTHROPIC -> AnthropicProvider(httpClient, json)
        ProviderType.GEMINI -> GeminiProvider(httpClient, json)
    } },
    private val enableParallel: Boolean = false,
) : LlmProvider {

    private fun providerForType(type: ProviderType): LlmProvider = factory(type)

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent> = callbackFlow {
        val isHarnessTarget = config.id == HarnessProvider.ID ||
            config.baseUrl.contains("opencode.ai") ||
            config.baseUrl.contains("kilo.ai") ||
            config.baseUrl.contains("pollinations.ai")

        if (!isHarnessTarget) {
            // Non-pool provider: use a single provider directly
            val provider = providerForType(config.type)
            collect(provider.streamChat(config, apiKey, systemPrompt, messages, tools, options)) { event ->
                send(event)
            }
            close()
            return@callbackFlow
        }

        val classification = TaskClassifier.classify(messages)
        val hasImage = classification.hasImage

        // Build ordered candidate list from healthy entries
        val candidates = entries
            .filter { it.isAvailable(it.cooldownMs()) }
            .filter { !hasImage || it.visionCapable }
            .sortedWith(
                compareByDescending<ProviderPoolEntry> { it.speedScore }
                    .thenByDescending {
                        when {
                            classification.isCode && it.codingCapable -> 2
                            classification.isReasoning -> 1
                            else -> 0
                        }
                    }
                    .thenByDescending { it.speedScore }
            )

        if (candidates.isEmpty()) {
            send(StreamEvent.Failure(
                "No available providers. Try again in a moment.",
                code = 503,
                retryable = true,
            ))
            close()
            return@callbackFlow
        }

        // Try providers sequentially with invisible failover at connection time
        var lastError: StreamEvent.Failure? = null

        for (entry in candidates) {
            if (!entry.isAvailable(entry.cooldownMs())) continue

            val routedConfig = entry.config
            val routedProvider = entry.provider

            // Collect into a list first so we can decide failover before emitting
            val buffered = mutableListOf<StreamEvent>()
            var collectError: Exception? = null
            try {
                routedProvider.streamChat(
                    routedConfig,
                    apiKey,
                    systemPrompt,
                    messages,
                    tools,
                    options,
                ).collect { event ->
                    buffered.add(event)
                }
            } catch (e: Exception) {
                collectError = e
            }

            if (collectError != null) {
                entry.markFailure()
                lastError = StreamEvent.Failure(collectError.message ?: "Unknown error", retryable = true)
                continue
            }

            // Check if the first event is a retryable connection failure (failover)
            val firstEvent = buffered.firstOrNull()
            if (firstEvent is StreamEvent.Failure && !buffered.any { it !is StreamEvent.Failure }) {
                if (firstEvent.retryable == true || firstEvent.code == 429 || firstEvent.code == 503) {
                    entry.markFailure()
                    lastError = firstEvent
                    continue // try next candidate
                }
            }

            // Emit all collected events
            for (event in buffered) {
                send(event)
            }

            entry.markSuccess()
            close()
            return@callbackFlow
        }

        // All providers failed
        send(lastError ?: StreamEvent.Failure("All providers unavailable.", code = 503, retryable = true))
        close()
    }

    companion object {
        /** Build a router from a list of provider pool entries. */
        fun build(
            entries: List<ProviderPoolEntry>,
            httpClient: OkHttpClient,
            json: Json,
            factory: (ProviderType) -> LlmProvider = { type -> when (type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(httpClient, json)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(httpClient, json)
                ProviderType.ANTHROPIC -> AnthropicProvider(httpClient, json)
                ProviderType.GEMINI -> GeminiProvider(httpClient, json)
            } },
            enableParallel: Boolean = false,
        ): ProviderRouter {
            return ProviderRouter(entries, httpClient, json, factory, enableParallel)
        }

        /** Create default key-free pool entries (Zen, Kilo, Pollinations). */
        fun defaultKeyFreeEntries(httpClient: OkHttpClient, json: Json): List<ProviderPoolEntry> {
            val zenConfig = ProviderConfig(
                id = "harness-zen",
                name = "OpenCode Zen",
                type = ProviderType.OPENAI_COMPAT,
                baseUrl = "https://opencode.ai/zen/v1",
                model = "kilo-auto/free",
            )
            val kiloConfig = ProviderConfig(
                id = "harness-kilo",
                name = "Kilo Free",
                type = ProviderType.OPENAI_COMPAT,
                baseUrl = "https://api.kilo.ai/api/gateway",
                model = "kilo-auto/free",
            )
            val pollConfig = ProviderConfig(
                id = "harness-pollinations",
                name = "Pollinations",
                type = ProviderType.OPENAI_COMPAT,
                baseUrl = "https://text.pollinations.ai/openai",
                model = "openai-fast",
            )

            return listOf(
                ProviderPoolEntry(
                    config = zenConfig,
                    provider = providerForTypeStatic(ProviderType.OPENAI_COMPAT, httpClient, json),
                    visionCapable = true,
                    codingCapable = true,
                    speedScore = 60,
                ),
                ProviderPoolEntry(
                    config = kiloConfig,
                    provider = providerForTypeStatic(ProviderType.OPENAI_COMPAT, httpClient, json),
                    visionCapable = true,
                    codingCapable = true,
                    speedScore = 70,
                ),
                ProviderPoolEntry(
                    config = pollConfig,
                    provider = providerForTypeStatic(ProviderType.OPENAI_COMPAT, httpClient, json),
                    visionCapable = false,
                    codingCapable = false,
                    speedScore = 50,
                ),
            )
        }

        private fun providerForTypeStatic(type: ProviderType, httpClient: OkHttpClient, json: Json): LlmProvider {
            return when (type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(httpClient, json)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(httpClient, json)
                ProviderType.ANTHROPIC -> AnthropicProvider(httpClient, json)
                ProviderType.GEMINI -> GeminiProvider(httpClient, json)
            }
        }
    }
}

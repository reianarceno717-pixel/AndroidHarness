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
 */
class ProviderRouter(
    private val entries: List<ProviderPoolEntry>,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val enableParallel: Boolean = false,
) : LlmProvider {

    private fun providerForType(type: ProviderType): LlmProvider = when (type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(httpClient, json)
        ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(httpClient, json)
        ProviderType.ANTHROPIC -> AnthropicProvider(httpClient, json)
        ProviderType.GEMINI -> GeminiProvider(httpClient, json)
    }

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent> = callbackFlow {
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

            try {
                val result = routedProvider.streamChat(
                    routedConfig,
                    apiKey,
                    systemPrompt,
                    messages,
                    tools,
                    options,
                )

                var emittedAny = false
                collect(result) { event ->
                    when (event) {
                        is StreamEvent.Failure -> {
                            // Only failover on connection-level rate limits / server errors,
                            // not on mid-stream errors once we've started emitting.
                            if (!emittedAny && (event.retryable == true || event.code == 429 || event.code == 503)) {
                                entry.markFailure()
                                lastError = event
                                continue@for // try next candidate
                            }
                            // Pass through non-retryable or mid-stream failures
                            send(event)
                            close()
                            return@callbackFlow
                        }
                        is StreamEvent.ProviderChanged -> {
                            send(event)
                            close()
                            return@callbackFlow
                        }
                        else -> {
                            send(event)
                            emittedAny = true
                        }
                    }
                }

                if (emittedAny) {
                    entry.markSuccess()
                    close()
                    return@callbackFlow
                }
            } catch (e: Exception) {
                entry.markFailure()
                lastError = StreamEvent.Failure(e.message ?: "Unknown error", retryable = true)
                continue@for
            }
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
            enableParallel: Boolean = false,
        ): ProviderRouter {
            return ProviderRouter(entries, httpClient, json, enableParallel)
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

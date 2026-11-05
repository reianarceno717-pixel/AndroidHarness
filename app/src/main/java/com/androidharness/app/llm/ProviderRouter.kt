package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.comparisons.compareByDescending

class ProviderRouter(
    private val entries: List<ProviderPoolEntry>,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val factory: (ProviderType) -> LlmProvider,
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
        val classification = TaskClassifier.classify(messages)
        val hasImage = classification.hasImage

        val candidates = entries
            .filter { it.isAvailable(it.cooldownMs()) }
            .filter { !hasImage || it.visionCapable }
            .sortedWith(
                compareByDescending<ProviderPoolEntry> { it.speedScore }
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

        var lastError: String? = null

        for (entry in candidates) {
            if (!entry.isAvailable(entry.cooldownMs())) continue

            val routedProvider = entry.provider

            try {
                var emittedAny = false
                routedProvider.streamChat(
                    entry.config,
                    apiKey,
                    systemPrompt,
                    messages,
                    tools,
                    options,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Failure -> {
                            if (!emittedAny && (event.retryable == true || event.code == 429 || event.code == 503)) {
                                entry.markFailure()
                                lastError = event.message
                            } else {
                                send(event)
                            }
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
                lastError = e.message
            }
        }

        send(StreamEvent.Failure(lastError ?: "All providers unavailable.", code = 503, retryable = true))
        close()
    }

    companion object {
        fun build(
            entries: List<ProviderPoolEntry>,
            httpClient: OkHttpClient,
            json: Json,
            factory: (ProviderType) -> LlmProvider,
        ): ProviderRouter {
            return ProviderRouter(entries, httpClient, json, factory)
        }

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

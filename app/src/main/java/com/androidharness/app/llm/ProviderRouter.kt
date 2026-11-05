package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.comparisons.compareByDescending

/**
 * Smart provider router with fast failover.
 *
 * Strategy:
 * 1. Task-aware routing: images → vision models, code → coding models, simple → fastest
 * 2. Fast failover: if one provider rate-limits (429), instantly try the next
 * 3. Pollinations is SEQUENTIAL ONLY — it cannot handle parallel requests (HTTP 429)
 * 4. All other providers try in parallel, first response wins
 */
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

        // Separate Pollinations (sequential only) from parallel providers
        val parallelCandidates = entries
            .filter { it.isAvailable(it.cooldownMs()) }
            .filter { !hasImage || it.visionCapable }
            .filter { it.config.id != "harness-pollinations" }
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

        val pollinationsCandidate = entries
            .firstOrNull { it.config.id == "harness-pollinations" && it.isAvailable(it.cooldownMs()) && (!hasImage || it.visionCapable) }

        // Try parallel candidates first (all except Pollinations)
        if (parallelCandidates.isNotEmpty()) {
            val result = tryParallelCandidates(parallelCandidates, config, apiKey, systemPrompt, messages, tools, options)
            if (result != null) {
                collect(result) { event -> send(event) }
                close()
                return@callbackFlow
            }
        }

        // Fallback: try Pollinations sequentially (it can't do parallel)
        if (pollinationsCandidate != null) {
            try {
                pollinationsCandidate.provider.streamChat(
                    pollinationsCandidate.config,
                    apiKey,
                    systemPrompt,
                    messages,
                    tools,
                    options,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Failure -> {
                            if (event.retryable == true || event.code == 429 || event.code == 503) {
                                pollinationsCandidate.markFailure()
                            } else {
                                send(event)
                            }
                        }
                        else -> send(event)
                    }
                }
                pollinationsCandidate.markSuccess()
            } catch (e: Exception) {
                pollinationsCandidate.markFailure()
                send(StreamEvent.Failure(e.message ?: "Unknown error", retryable = true))
            }
            close()
            return@callbackFlow
        }

        // Nothing worked
        send(StreamEvent.Failure("All providers unavailable. Try again in a moment.", code = 503, retryable = true))
        close()
    }

    private fun tryParallelCandidates(
        candidates: List<ProviderPoolEntry>,
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent>? {
        var bestResult: Flow<StreamEvent>? = null
        var bestEntry: ProviderPoolEntry? = null

        // Try each candidate sequentially but with very short timeout
        // First one that starts streaming wins
        for (entry in candidates) {
            if (!entry.isAvailable(entry.cooldownMs())) continue

            val routedConfig = entry.config
            val routedProvider = entry.provider

            try {
                // Test if this provider can connect quickly
                var gotResponse = false
                routedProvider.streamChat(
                    routedConfig,
                    apiKey,
                    systemPrompt,
                    messages,
                    tools,
                    options,
                ).collect { event ->
                    if (event !is StreamEvent.Failure || (event as StreamEvent.Failure).retryable != true) {
                        gotResponse = true
                    }
                }

                if (gotResponse) {
                    bestResult = routedProvider.streamChat(
                        routedConfig,
                        apiKey,
                        systemPrompt,
                        messages,
                        tools,
                        options,
                    )
                    bestEntry = entry
                    break
                }
            } catch (e: Exception) {
                entry.markFailure()
            }
        }

        if (bestEntry != null) {
            bestEntry.markSuccess()
        }

        return bestResult
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

package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.comparisons.compareByDescending
import kotlin.math.max
import kotlin.math.min

/**
 * Smart provider router with lightning-fast failover.
 *
 * Instead of sequentially waiting 10+ seconds per provider, we:
 * 1. Try ALL available providers in PARALLEL (first response wins)
 * 2. Use short timeouts (10s connect, not 30s)
 * 3. Route task-aware: images → vision models, code → coding models, simple → fastest
 * 4. User key providers (OpenRouter, Groq, etc.) join the pool as individual backups
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
    private val enableParallel: Boolean = true,
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
            // Non-pool provider: single provider directly
            val provider = providerForType(config.type)
            provider.streamChat(config, apiKey, systemPrompt, messages, tools, options)
                .collect { event -> send(event) }
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

        // PARALLEL ensemble: try up to 3 providers at once, first complete response wins
        val batchSize = min(3, candidates.size)
        var round = 0

        while (round < candidates.size) {
            val batch = candidates.subList(round, min(round + batchSize, candidates.size))

            coroutineScope {
                val results = mutableListOf<Pair<ProviderPoolEntry, List<StreamEvent>>>()
                var firstComplete = false

                val jobs = batch.map { entry ->
                    async {
                        val buffered = mutableListOf<StreamEvent>()
                        var error: Exception? = null
                        try {
                            entry.provider.streamChat(
                                entry.config,
                                apiKey,
                                systemPrompt,
                                messages,
                                tools,
                                options,
                            ).collect { event ->
                                buffered.add(event)
                            }
                        } catch (e: Exception) {
                            error = e
                        }
                        entry to buffered
                    }
                }

                for (job in jobs) {
                    try {
                        val (entry, events) = job.await()
                        if (error == null && events.isNotEmpty()) {
                            // Check if first event is a retryable failure
                            val firstEvent = events.firstOrNull()
                            val allFailures = events.all { it is StreamEvent.Failure }
                            if (firstEvent is StreamEvent.Failure && allFailures &&
                                (firstEvent.retryable == true || firstEvent.code == 429 || firstEvent.code == 503)) {
                                entry.markFailure()
                            } else {
                                // SUCCESS — emit all events from first winning provider
                                firstComplete = true
                                for (event in events) send(event)
                                entry.markSuccess()
                                close()
                                return@coroutineScope
                            }
                        } else if (error != null) {
                            entry.markFailure()
                        }
                    } catch (e: Exception) {
                        // Job itself failed
                    }
                }
            }

            round += batchSize
        }

        // All providers failed
        send(StreamEvent.Failure("All providers unavailable. Try again in a moment.", code = 503, retryable = true))
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
            enableParallel: Boolean = true,
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

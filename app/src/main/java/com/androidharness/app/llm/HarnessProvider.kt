package com.androidharness.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object HarnessProvider {
    const val ID = "harness"
    const val BASE_URL = "https://opencode.ai/zen/v1"
    const val KILO_BASE_URL = "https://api.kilo.ai/api/gateway"
    const val POLLINATIONS_BASE_URL = "https://text.pollinations.ai/openai"
    const val KILO_NOTE = "Kilo · ~200 req/hour per IP"
    const val CATALOG_SOURCE = "kilo-free-v1"
    const val DEFAULT_MODEL = "kilo-auto/free"
    const val KEYLESS = "harness-keyless"
    const val SESSION_HEADER = "x-opencode-session"
    const val USER_AGENT = "AndroidHarness"
    private const val FIRST_OUTPUT_TIMEOUT_MS = 25_000L
    private const val UPSTREAM_COOLDOWN_MS = 60_000L

    val config = ProviderConfig(ID, "Harness", ProviderType.OPENAI_COMPAT, BASE_URL, DEFAULT_MODEL)

    /** Routing for the last successful free catalog, restored from disk on startup. */
    data class Pool(val baseUrl: String, val note: String)

    private val pollinations = ModelEntry("openai-fast", note = "Pollinations · ~4 req/min per IP")
    val fallbackModels = listOf(ModelEntry(DEFAULT_MODEL, note = KILO_NOTE), pollinations)
    @Volatile private var availableModels = fallbackModels
    @Volatile var customModelIds: Set<String> = emptySet()

    private val upstreamCooldownUntil = ConcurrentHashMap<String, Long>()

    private fun isUpstreamHealthy(key: String): Boolean {
        val until = upstreamCooldownUntil[key] ?: return true
        if (System.currentTimeMillis() >= until) {
            upstreamCooldownUntil.remove(key)
            return true
        }
        return false
    }

    private fun markUpstreamCooldown(key: String, durationMs: Long = UPSTREAM_COOLDOWN_MS) {
        upstreamCooldownUntil[key] = System.currentTimeMillis() + durationMs
    }

    private fun markUpstreamHealthy(key: String) {
        upstreamCooldownUntil.remove(key)
    }

    val pooledModels: List<ModelEntry> get() = availableModels

    val pool: Map<String, Pool> get() = availableModels.associate { entry ->
        entry.id to if (entry.id == pollinations.id) Pool(POLLINATIONS_BASE_URL, pollinations.note!!)
        else Pool(KILO_BASE_URL, KILO_NOTE)
    }

    fun restoreModels(entries: List<ModelEntry>) {
        availableModels = entries.toList()
        upstreamCooldownUntil.clear()
    }

    internal fun cachedModels(raw: String?, source: String?): List<ModelEntry> =
        if (source == CATALOG_SOURCE && raw != null) {
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<ModelEntry>>(raw) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: fallbackModels
        } else fallbackModels

    fun isPooled(model: String): Boolean = pool.containsKey(model)

    fun sanitize(model: String?, custom: Set<String> = emptySet()): String =
        model?.takeIf { it in pool || it in custom }
            ?: pooledModels.firstOrNull { it.id == DEFAULT_MODEL }?.id
            ?: pooledModels.firstOrNull { it.id != pollinations.id }?.id
            ?: pollinations.id

    /** Kilo declares free access explicitly; a :free suffix alone is insufficient. */
    internal fun parseKiloCatalog(body: String): List<ModelEntry> {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = requireNotNull(root["data"]?.jsonArrayOrAbsent()) { "Invalid Kilo model catalog" }
        val allowed = data.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            if (entry["isFree"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
            val supportsTools = entry["supported_parameters"]?.jsonArrayOrAbsent()
                ?.any { it.jsonPrimitive.contentOrNull == "tools" } == true
            if (!supportsTools) return@mapNotNull null
            entry["id"]?.jsonPrimitive?.contentOrNull
        }.toSet()
        val entries = ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, body)
            .filter { it.id in allowed }.map { it.copy(note = KILO_NOTE) }
        require(entries.isNotEmpty()) { "Kilo has no free models with tool support available" }
        return models(entries)
    }

    fun models(entries: List<ModelEntry>): List<ModelEntry> =
        (entries.filterNot { it.id == pollinations.id }.map { it.copy(note = KILO_NOTE) } + pollinations)
            .distinctBy { it.id }

    /**
     * The Zen relay serves one catalog behind three wires. Where Hermes pins
     * a static per-model table, we probe instead: the first request for a
     * model tries chat/completions, then Anthropic /messages, then OpenAI
     * /responses. The winner persists in DataStore (mirrored into [pins])
     * so later requests route directly.
     */
    @Volatile var pins: Map<String, String> = emptyMap()

    fun wire(model: String): ProviderType = when (pins[model]) {
        ProviderType.ANTHROPIC.name -> ProviderType.ANTHROPIC
        ProviderType.OPENAI_RESPONSES.name -> ProviderType.OPENAI_RESPONSES
        else -> ProviderType.OPENAI_COMPAT
    }

    private fun looksAnthropicError(body: String?): Boolean =
        body?.contains("\"type\":\"error\"") == true && body.contains("\"error\"")

    /** Zen's sentinel key means anonymous; anything else rides out as a Bearer. */
    private fun realKey(apiKey: String?): String? =
        apiKey?.takeIf { it.isNotBlank() && it != KEYLESS }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("HTTP-Referer", "https://github.com/Sanuu7/AndroidHarness")
                .header("X-Title", "Harness")
                .build())
        }
        .build()

    /**
     * Probe for the wire a model actually speaks. 2xx wins; a failed
     * chat/completions falls through to the Anthropic and Responses
     * endpoints. Null when nothing answered, leaving the default wire.
     * When a Zen key is available it probes keyed: anonymous probes now 403
     * on every wire, which used to pin the wrong protocol and produce 500s.
     */
    suspend fun probeWire(model: String, apiKey: String? = null): ProviderType? = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val key = realKey(apiKey)
        val chat = post("$BASE_URL/chat/completions", jsonBody(model), sessionId, key)
        if (chat) return@withContext ProviderType.OPENAI_COMPAT
        if (post(BASE_URL.removeSuffix("/v1") + "/v1/messages", anthropicBody(model), sessionId, key))
            return@withContext ProviderType.ANTHROPIC
        if (post("$BASE_URL/responses", responsesBody(model), sessionId, key))
            ProviderType.OPENAI_RESPONSES
        else null
    }

    private fun post(url: String, body: String, sessionId: String, key: String?): Boolean = runCatching {
        val builder = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
        withSession(builder, sessionId)
        if (key != null) builder.header("Authorization", "Bearer $key")
        ProviderNetwork.client(probeClient, ID).newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string()
            resp.isSuccessful && text != null && !looksAnthropicError(text)
        }
    }.getOrDefault(false)

    private fun jsonBody(model: String) =
        """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""

    private fun anthropicBody(model: String) =
        """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""

    private fun responsesBody(model: String) =
        """{"model":"$model","max_output_tokens":16,"input":"hi"}"""

    fun withSession(builder: Request.Builder, sessionId: String?): Request.Builder {
        if (!sessionId.isNullOrBlank()) builder.header(SESSION_HEADER, sessionId)
        return builder.header("User-Agent", USER_AGENT)
    }

    fun anonymous(request: Request): Request {
        val builder = request.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("x-api-key")
            .header("HTTP-Referer", "https://github.com/Sanuu7/AndroidHarness")
            .header("X-Title", "Harness")
        withSession(builder, request.header(SESSION_HEADER))
        return builder.build()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain -> chain.proceed(anonymous(chain.request())) }
        .build()

    /** Keyed variant: same timeouts, but the Authorization header survives. */
    private val keyedClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Anonymous community upstreams: our sentinel never rides out. */
    private val poolClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("Authorization").build())
        }
        .build()

    private data class UpstreamCandidate(
        val key: String,
        val model: String,
        val baseUrl: String,
        val streamFactory: () -> Flow<StreamEvent>,
    )

    private fun singleUpstreamStream(
        pooledClient: OkHttpClient,
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<com.androidharness.app.core.ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent> {
        pool[config.model]?.let { upstream ->
            val routed = config.copy(type = ProviderType.OPENAI_COMPAT, baseUrl = upstream.baseUrl)
            val stream = OpenAiCompatProvider(pooledClient, ProviderFactory.json)
                .streamChat(routed, OpenAiCompatProvider.LOCAL_KEY, systemPrompt, messages, tools, options)
            if (upstream.baseUrl != KILO_BASE_URL) return stream
            return stream.map { event ->
                if (event is StreamEvent.Failure) HarnessFreeLimit.normalize(event) else event
            }.catch { failure ->
                if (failure is ApiException) emit(HarnessFreeLimit.normalize(StreamEvent.Failure(
                    failure.message.orEmpty(), failure.code, failure.errorCode,
                    retryAfterSeconds = failure.retryAfterSeconds)))
                else throw failure
            }
        }
        if (config.model !in customModelIds) return flowOf(StreamEvent.Failure(
            "${config.model} is no longer available in Harness. Refresh the model list and choose an available model.",
        ))
        val key = realKey(apiKey)
        val wire = wire(config.model)
        val baseUrl = if (wire == ProviderType.ANTHROPIC) BASE_URL.removeSuffix("/v1") else BASE_URL
        val routed = config.copy(type = wire, baseUrl = baseUrl)
        val routedOptions = if (options.cacheKey.isNullOrBlank()) {
            options.copy(cacheKey = UUID.randomUUID().toString())
        } else options
        val http = if (key != null) keyedClient else client
        val provider = when (wire) {
            ProviderType.ANTHROPIC -> AnthropicProvider(http, ProviderFactory.json)
            ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(http, ProviderFactory.json)
            else -> OpenAiCompatProvider(http, ProviderFactory.json)
        }
        return provider.streamChat(routed, key ?: KEYLESS, systemPrompt, messages, tools, routedOptions)
    }

    private fun zenStream(
        config: ProviderConfig,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<com.androidharness.app.core.ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent> {
        val key = realKey(apiKey)
        val wire = wire(model)
        val baseUrl = if (wire == ProviderType.ANTHROPIC) BASE_URL.removeSuffix("/v1") else BASE_URL
        val routed = config.copy(type = wire, baseUrl = baseUrl, model = model)
        val routedOptions = if (options.cacheKey.isNullOrBlank()) {
            options.copy(cacheKey = UUID.randomUUID().toString())
        } else options
        val http = if (key != null) keyedClient else client
        val provider = when (wire) {
            ProviderType.ANTHROPIC -> AnthropicProvider(http, ProviderFactory.json)
            ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(http, ProviderFactory.json)
            else -> OpenAiCompatProvider(http, ProviderFactory.json)
        }
        return provider.streamChat(routed, key ?: KEYLESS, systemPrompt, messages, tools, routedOptions)
    }

    fun create(pooledClient: OkHttpClient = poolClient): LlmProvider = object : LlmProvider {
        override fun streamChat(
            config: ProviderConfig,
            apiKey: String,
            systemPrompt: String,
            messages: List<com.androidharness.app.core.ChatMessage>,
            tools: List<ToolSchema>,
            options: RequestOptions,
        ): Flow<StreamEvent> {
            // Preserve direct single-upstream behavior when a custom test client is injected
            if (pooledClient !== poolClient) {
                return singleUpstreamStream(pooledClient, config, apiKey, systemPrompt, messages, tools, options)
            }

            if (!isPooled(config.model) && config.model !in customModelIds) {
                return flowOf(StreamEvent.Failure(
                    "${config.model} is no longer available in Harness. Refresh the model list and choose an available model.",
                ))
            }

            val needsVision = messages.any { it.images.isNotEmpty() || it.imageData.isNotEmpty() }
            val effectivePrimaryModel = if (needsVision && (config.model == pollinations.id || !visionCapable(config.model))) {
                pooledModels.firstOrNull { it.id == DEFAULT_MODEL }?.id
                    ?: pooledModels.firstOrNull { it.id != pollinations.id && visionCapable(it.id) }?.id
                    ?: DEFAULT_MODEL
            } else {
                config.model
            }

            val rawCandidates = mutableListOf<UpstreamCandidate>()

            // 1. Primary requested/vision-upgraded model
            val primaryConfig = config.copy(model = effectivePrimaryModel)
            val primaryBaseUrl = pool[effectivePrimaryModel]?.baseUrl ?: BASE_URL
            rawCandidates += UpstreamCandidate(
                key = "$primaryBaseUrl#$effectivePrimaryModel",
                model = effectivePrimaryModel,
                baseUrl = primaryBaseUrl,
                streamFactory = {
                    singleUpstreamStream(pooledClient, primaryConfig, apiKey, systemPrompt, messages, tools, options)
                },
            )

            // 2. OpenCode Zen gateway fallback for instant quota/stall recovery
            val zenModel = if (effectivePrimaryModel == pollinations.id) DEFAULT_MODEL else effectivePrimaryModel
            if (primaryBaseUrl != BASE_URL) {
                rawCandidates += UpstreamCandidate(
                    key = "$BASE_URL#$zenModel",
                    model = zenModel,
                    baseUrl = BASE_URL,
                    streamFactory = {
                        zenStream(config, zenModel, apiKey, systemPrompt, messages, tools, options)
                    },
                )
            }

            // 3. Additional pooled free models on Kilo Gateway
            for (entry in pooledModels) {
                if (entry.id == pollinations.id) continue
                if (needsVision && !visionCapable(entry.id)) continue
                val key = "$KILO_BASE_URL#${entry.id}"
                if (rawCandidates.none { it.key == key }) {
                    val altConfig = config.copy(model = entry.id)
                    rawCandidates += UpstreamCandidate(
                        key = key,
                        model = entry.id,
                        baseUrl = KILO_BASE_URL,
                        streamFactory = {
                            singleUpstreamStream(pooledClient, altConfig, apiKey, systemPrompt, messages, tools, options)
                        },
                    )
                }
            }

            // 4. Pollinations fast text/code fallback (when no images are attached)
            if (!needsVision) {
                val pollKey = "$POLLINATIONS_BASE_URL#${pollinations.id}"
                if (rawCandidates.none { it.key == pollKey }) {
                    val pollConfig = config.copy(model = pollinations.id)
                    rawCandidates += UpstreamCandidate(
                        key = pollKey,
                        model = pollinations.id,
                        baseUrl = POLLINATIONS_BASE_URL,
                        streamFactory = {
                            singleUpstreamStream(pooledClient, pollConfig, apiKey, systemPrompt, messages, tools, options)
                        },
                    )
                }
            }

            // Prioritize healthy upstreams while keeping cooled-down ones as last-resort fallbacks
            val orderedCandidates = rawCandidates.sortedBy { if (isUpstreamHealthy(it.key)) 0 else 1 }

            return channelFlow {
                var activeModel = config.model
                var lastFailure: StreamEvent.Failure? = null

                for ((index, candidate) in orderedCandidates.withIndex()) {
                    var visibleOutputEmitted = false
                    var candidateFailed: StreamEvent.Failure? = null

                    try {
                        if (candidate.model != activeModel) {
                            activeModel = candidate.model
                            send(
                                StreamEvent.ProviderChanged(
                                    config = config.copy(model = candidate.model, baseUrl = candidate.baseUrl),
                                    reason = if (needsVision && !visionCapable(config.model)) {
                                        "Smart routing: switched to ${candidate.model} for image support"
                                    } else {
                                        "Auto-fallback: switched to ${candidate.model}"
                                    },
                                    persistSelection = false,
                                )
                            )
                        }

                        candidate.streamFactory()
                            .timeout(FIRST_OUTPUT_TIMEOUT_MS.toDouble().milliseconds)
                            .collect { event ->
                                when (event) {
                                    is StreamEvent.Failure -> {
                                        candidateFailed = event
                                        throw RuntimeException("__UPSTREAM_FAILURE__")
                                    }
                                    is StreamEvent.TextDelta -> {
                                        if (event.text.isNotEmpty()) visibleOutputEmitted = true
                                        send(event)
                                    }
                                    is StreamEvent.ToolCallDelta,
                                    is StreamEvent.ToolCallReady,
                                    is StreamEvent.ToolCallBatch -> {
                                        visibleOutputEmitted = true
                                        send(event)
                                    }
                                    else -> {
                                        send(event)
                                    }
                                }
                            }
                        if (candidateFailed == null) {
                            markUpstreamHealthy(candidate.key)
                            return@channelFlow
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        if (e !is TimeoutCancellationException) throw e
                        // Timed out waiting for first visible response
                        markUpstreamCooldown(candidate.key)
                        lastFailure = StreamEvent.Failure(
                            message = "Upstream ${candidate.model} timed out after ${FIRST_OUTPUT_TIMEOUT_MS / 1000}s",
                            code = 504,
                            retryable = true,
                        )
                    } catch (e: Throwable) {
                        val failure = candidateFailed ?: when (e) {
                            is ApiException -> HarnessFreeLimit.normalize(
                                StreamEvent.Failure(
                                    e.message.orEmpty(),
                                    e.code,
                                    e.errorCode,
                                    retryAfterSeconds = e.retryAfterSeconds,
                                )
                            )
                            else -> StreamEvent.Failure(
                                message = e.message ?: "Upstream request failed",
                                retryable = true,
                            )
                        }
                        lastFailure = failure
                        markUpstreamCooldown(candidate.key)
                    }

                    // If visible text or tool calls were already streamed to the UI, we cannot replay from scratch
                    if (visibleOutputEmitted || index == orderedCandidates.lastIndex) {
                        send(lastFailure ?: StreamEvent.Failure("All Harness gateways failed"))
                        return@channelFlow
                    }
                }
            }
        }
    }
}

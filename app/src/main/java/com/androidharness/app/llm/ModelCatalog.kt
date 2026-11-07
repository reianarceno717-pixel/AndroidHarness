package com.androidharness.app.llm

import com.androidharness.app.llm.jsonArrayOrAbsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One entry of a provider's model catalog. [reasoning] is a tri-state:
 * true/false when the endpoint reports capability (e.g. OpenRouter's
 * `supported_parameters`), null when unknown, the UI then falls back to
 * [reasoningCapable] id heuristics.
 */
@Serializable
data class ModelEntry(
    val id: String,
    val reasoning: Boolean? = null,
    val contextTokens: Long? = null,
    val custom: Boolean = false,
    val note: String? = null,
    val displayName: String? = null,
    val reasoningEfforts: List<String>? = null,
    val defaultReasoningEffort: String? = null,
    /** Distinguishes Gemini's reported thinking field from older cached family-name guesses. */
    val reasoningReported: Boolean = false,
    /** Explicit catalog metadata only. Null means this connection did not report it. */
    val tools: Boolean? = null,
    val imageInput: Boolean? = null,
    // HarnessProvider-specific fields for custom/free model persistence
    val name: String? = null,
    val maxOutput: Int? = null,
    val free: Boolean? = null,
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val contextWindow: Long? = null,
)

/** Family-based thinking-capability hint for endpoints that don't report it. */
fun reasoningCapable(modelId: String): Boolean {
    val m = modelId.lowercase()
    return Regex(
        "(^|/)(o[1345]([-.]|$)|gpt-5)" +                 // OpenAI reasoning families
            "|deepseek-(r1|reasoner)|deepseek-v[4-9]" +   // DeepSeek reasoners / v4+
            "|claude" +                                   // Claude 3.7+ all support thinking
            "|gemini-[23]\\.[0-9]" +                      // Gemini 2.x/3.x flash/pro
            "|qwen3|glm-[45]|minimax-m2|nemotron|hy3|kimi-latest|k2",
    ).containsMatchIn(m)
}

/**
 * Family-based vision-capability heuristic.
 * Returns false for models known to reject multimodal/image input payloads.
 */
fun visionCapable(modelId: String): Boolean {
    val m = modelId.lowercase()
    if (m.contains("-vl") || m.contains("vision") || m.contains("omni") || m.contains("4o") ||
        m.contains("gemini") || m.contains("claude") || m.contains("gpt-4-turbo") ||
        // V4 Pro is text-only; only the Flash variants support image input.
        m.contains("deepseek-v4-flash") || m.contains("deepseek-v4.1-flash")
    ) {
        return true
    }
    return !Regex(
        "(^|/)(deepseek-(chat|coder|r1|reasoner|v[0-9])|qwen.*coder|o1-mini|codellama|mistral-(tiny|small|embed)|llama-.*-(?!.*vision))",
    ).containsMatchIn(m)
}

/** Fetches the model catalog from a provider, also doubles as a connection test. */
object ModelCatalog {

    @Volatile var chatGptModels: (suspend (String) -> List<ModelEntry>)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Models(val models: List<ModelEntry>, val latencyMs: Long) : Result
        data class Failed(val message: String, val status: Int? = null) : Result
    }

    suspend fun listModels(config: ProviderConfig, apiKey: String, httpClient: OkHttpClient = client): Result =
        withContext(Dispatchers.IO) {
            if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id)) {
                return@withContext try {
                    Result.Models(checkNotNull(chatGptModels) { "Connect ChatGPT in Settings > Connected accounts." }(config.id), 0)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { Result.Failed(e.message ?: "Could not refresh ChatGPT models.") }
            }
            if (com.androidharness.app.local.LocalModelCatalog.isLocal(config.id)) {
                return@withContext Result.Models(listOf(ModelEntry(config.model, note = "On-device · tools and vision depend on model and projector")), 0)
            }
            val started = System.currentTimeMillis()
            try {
                val request = buildRequest(config, apiKey)
                ProviderHeaders.client(config, httpClient).newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use Result.Failed("HTTP ${resp.code}: ${resp.message}", resp.code)
                    }
                    val body = resp.body?.string() ?: return@use Result.Failed("Empty response")
                    val entries = if (config.id == HarnessProvider.ID) HarnessProvider.parseKiloCatalog(body)
                        else parseCatalog(config.type, body)
                    Result.Models(entries, System.currentTimeMillis() - started)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Connection failed")
            }
        }

    internal fun buildRequest(config: ProviderConfig, apiKey: String): Request {
        if (config.id == HarnessProvider.ID) return Request.Builder()
            .url(HarnessProvider.KILO_BASE_URL + "/models")
            .header("User-Agent", HarnessProvider.USER_AGENT).build()
        val authed = apiKey.isNotBlank() && apiKey != OpenAiCompatProvider.LOCAL_KEY
        val (url, requestBuilder) = when (config.type) {
            // Responses is OpenAI-only; its model listing is identical.
            ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES ->
                config.baseUrl.trimEnd('/') + "/models" to
                    Request.Builder().apply { if (authed) header("Authorization", "Bearer $apiKey") }

            ProviderType.ANTHROPIC -> config.baseUrl.trimEnd('/') + "/v1/models" to
                Request.Builder()
                    .apply { if (authed) header("x-api-key", apiKey) }
                    .header("anthropic-version", "2023-06-01")

            ProviderType.GEMINI ->
                config.baseUrl.trimEnd('/') + "/models" to
                    Request.Builder().apply { if (authed) header("x-goog-api-key", apiKey) }
        }
        val rawRequest = requestBuilder.url(url).build()
        val request = when {
            config.id == HarnessProvider.ID -> HarnessProvider.anonymous(rawRequest)
            HarnessProvider.isOpenCode(config) -> HarnessProvider.withSession(rawRequest.newBuilder(), null).build()
            else -> rawRequest
        }
        return ProviderHeaders.apply(request.newBuilder(), config).build()
    }

    /** Pure parser, unit-testable without HTTP. */
    internal fun parseCatalog(type: ProviderType, body: String): List<ModelEntry> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (type) {
            ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES, ProviderType.ANTHROPIC ->
                root["data"]?.jsonArrayOrAbsent()?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }
                        ?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val effort = obj["effort"] as? JsonObject
                    val efforts = reportedEfforts(effort?.get("supported_levels")
                        ?: obj["supported_reasoning_efforts"] ?: obj["supported_reasoning_levels"])
                    val defaultEffort = ((effort?.get("default_level") ?: obj["default_reasoning_effort"]
                        ?: obj["default_reasoning_level"]) as? JsonPrimitive)?.takeIf { it.isString }
                        ?.content?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it in efforts.orEmpty() }
                    // OpenRouter-style capability reporting; absent elsewhere.
                    val reasoning = (obj["reasoning"] as? JsonPrimitive)?.booleanOrNull
                        ?: (obj["capabilities"]?.jsonObjectOrAbsent()?.get("reasoning") as? JsonPrimitive)?.booleanOrNull
                        ?: efforts?.any { it !in setOf("none", "disabled", "off") }
                        ?: obj["supported_parameters"]?.jsonArrayOrAbsent()?.let { params ->
                        params.any { p ->
                            (p as? JsonPrimitive)?.contentOrNull?.contains("reasoning") == true
                        }
                    }
                    // llm7 reports {"tokens":400000,"chars":null} rather than
                    // a scalar context_window. Unknown metadata must not block setup.
                    val window = obj["context_window"]
                    val ctx = sequenceOf(obj["context_length"], obj["max_tokens"], window,
                        window.jsonObjectOrAbsent()?.get("tokens"))
                        .mapNotNull { (it as? JsonPrimitive)?.longOrNull }.firstOrNull()
                    ModelEntry(id, reasoning, ctx,
                        reasoningEfforts = efforts, defaultReasoningEffort = defaultEffort,
                        reasoningReported = efforts != null,
                        tools = reportedTools(obj), imageInput = reportedImageInput(obj))
                } ?: emptyList()

            ProviderType.GEMINI ->
                root["models"]?.jsonArrayOrAbsent()?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val thinking = (obj["thinking"] as? JsonPrimitive)?.booleanOrNull
                    ModelEntry(name, reasoning = thinking, reasoningReported = thinking != null)
                } ?: emptyList()
        }.sortedBy { it.id }
    }

    private fun reportedEfforts(value: JsonElement?): List<String>? {
        val array = value as? JsonArray ?: return null
        if (array.size > 16) return null
        val names = array.map { item ->
            val name = ((if (item is JsonObject) item["effort"] else item) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim() ?: return null
            if (name.isBlank() || name.length > 32 || name.any(Char::isISOControl)) return null
            name.lowercase(java.util.Locale.ROOT)
        }
        return names.distinct()
    }

    internal fun reportedTools(obj: JsonObject): Boolean? {
        val parameters = obj["supported_parameters"] as? JsonArray ?: return null
        if (parameters.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "tools" }) return true
        return false.takeIf { parameters.all { it is JsonPrimitive && it.isString } }
    }

    internal fun reportedImageInput(obj: JsonObject): Boolean? = inputImages(
        (obj["architecture"] as? JsonObject)?.get("input_modalities"),
    )

    internal fun inputImages(value: JsonElement?): Boolean? {
        val modalities = value as? JsonArray ?: return null
        if (modalities.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "image" }) return true
        return false.takeIf { modalities.all { it is JsonPrimitive && it.isString } }
    }
}


/**
 * Maintainable free-tier provider directory and quota/limit reference (Phase 2).
 * Only lists providers with verified developer free tiers; never hardcodes private API keys.
 */
data class FreeTierProviderInfo(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderType,
    val authRequirement: String,
    val rateLimitSummary: String,
    val freeModels: List<ModelEntry>,
)

object FreeTierProviderCatalog {
    val providers: List<FreeTierProviderInfo> = listOf(
        FreeTierProviderInfo(
            id = "google",
            name = "Google Gemini API",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            protocol = ProviderType.GEMINI,
            authRequirement = "x-goog-api-key (Google AI Studio free API key)",
            rateLimitSummary = "15 RPM · 1M TPM · 1,500 RPD on Gemini 2.5 Flash free tier",
            freeModels = listOf(
                ModelEntry("gemini-2.5-flash", reasoning = true, contextTokens = 1_048_576, tools = true, imageInput = true, free = true, note = "Free tier · 15 RPM / 1,500 RPD"),
                ModelEntry("gemini-2.5-flash-lite", reasoning = true, contextTokens = 1_048_576, tools = true, imageInput = true, free = true, note = "Free tier · high-throughput lightweight model"),
            ),
        ),
        FreeTierProviderInfo(
            id = "openrouter",
            name = "OpenRouter Free Models",
            baseUrl = "https://openrouter.ai/api/v1",
            protocol = ProviderType.OPENAI_COMPAT,
            authRequirement = "Bearer API key (OpenRouter account key, $0 free models)",
            rateLimitSummary = "20 RPM · 50–1,000 RPD on :free suffixed community models",
            freeModels = listOf(
                ModelEntry("deepseek/deepseek-chat-v3-0324:free", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "OpenRouter :free tier"),
                ModelEntry("deepseek/deepseek-r1:free", reasoning = true, contextTokens = 128_000, tools = false, free = true, note = "OpenRouter :free reasoning"),
                ModelEntry("meta-llama/llama-3.3-70b-instruct:free", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "OpenRouter :free tool-capable"),
                ModelEntry("qwen/qwen-2.5-coder-32b-instruct:free", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "OpenRouter :free coding specialist"),
            ),
        ),
        FreeTierProviderInfo(
            id = "groq",
            name = "Groq Cloud",
            baseUrl = "https://api.groq.com/openai/v1",
            protocol = ProviderType.OPENAI_COMPAT,
            authRequirement = "Bearer API key (Groq Console free developer key)",
            rateLimitSummary = "30 RPM · 6,000–30,000 TPM · 1,000–14,400 RPD free developer tier",
            freeModels = listOf(
                ModelEntry("llama-3.3-70b-versatile", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "Groq free tier · 30 RPM / 1K RPD"),
                ModelEntry("llama-3.1-8b-instant", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "Groq free tier · 30 RPM / 14.4K RPD"),
                ModelEntry("qwen-qwq-32b", reasoning = true, contextTokens = 128_000, tools = true, free = true, note = "Groq free tier reasoning"),
            ),
        ),
        FreeTierProviderInfo(
            id = "cerebras",
            name = "Cerebras Inference",
            baseUrl = "https://api.cerebras.ai/v1",
            protocol = ProviderType.OPENAI_COMPAT,
            authRequirement = "Bearer API key (Cerebras Cloud free developer tier)",
            rateLimitSummary = "30 RPM · 60,000 TPM · 1,000,000 TPD free developer tier",
            freeModels = listOf(
                ModelEntry("llama-3.3-70b", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "Cerebras free tier · ~2,000 tok/s · 1M TPD"),
                ModelEntry("llama3.1-8b", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "Cerebras free tier · ultra-low latency"),
                ModelEntry("qwen-3-32b", reasoning = true, contextTokens = 128_000, tools = true, free = true, note = "Cerebras free tier reasoning & code"),
            ),
        ),
        FreeTierProviderInfo(
            id = "sambanova",
            name = "SambaNova Cloud",
            baseUrl = "https://api.sambanova.ai/v1",
            protocol = ProviderType.OPENAI_COMPAT,
            authRequirement = "Bearer API key (SambaNova Cloud free developer tier)",
            rateLimitSummary = "20–30 RPM free developer tier on Llama 3.3 & DeepSeek R1/V3",
            freeModels = listOf(
                ModelEntry("Meta-Llama-3.3-70B-Instruct", reasoning = false, contextTokens = 128_000, tools = true, free = true, note = "SambaNova free developer tier"),
                ModelEntry("DeepSeek-R1", reasoning = true, contextTokens = 64_000, tools = false, free = true, note = "SambaNova free tier reasoning"),
                ModelEntry("Qwen2.5-Coder-32B-Instruct", reasoning = false, contextTokens = 64_000, tools = true, free = true, note = "SambaNova free tier coding"),
            ),
        ),
        FreeTierProviderInfo(
            id = "github-models",
            name = "GitHub Models",
            baseUrl = "https://models.inference.ai.azure.com/v1",
            protocol = ProviderType.OPENAI_COMPAT,
            authRequirement = "Bearer GitHub Personal Access Token (models:read scope)",
            rateLimitSummary = "10–15 RPM · 50–150 RPD free developer playground tier",
            freeModels = listOf(
                ModelEntry("gpt-4o-mini", reasoning = false, contextTokens = 128_000, tools = true, imageInput = true, free = true, note = "GitHub Models free tier · 15 RPM / 150 RPD"),
                ModelEntry("gpt-4o", reasoning = false, contextTokens = 128_000, tools = true, imageInput = true, free = true, note = "GitHub Models free tier · 10 RPM / 50 RPD"),
                ModelEntry("DeepSeek-R1", reasoning = true, contextTokens = 64_000, tools = false, free = true, note = "GitHub Models free tier reasoning"),
            ),
        ),
    )

    fun forBaseUrl(baseUrl: String?): FreeTierProviderInfo? {
        if (baseUrl.isNullOrBlank()) return null
        val normalized = baseUrl.trim().trimEnd('/').lowercase()
        return providers.firstOrNull { normalized.startsWith(it.baseUrl.trimEnd('/').lowercase()) }
    }
}

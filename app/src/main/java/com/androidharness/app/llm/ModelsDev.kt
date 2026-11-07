package com.androidharness.app.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.doubleOrNull

/**
 * The models.dev community catalog (https://models.dev, the same feed
 * OpenCode consumes, refreshed daily by its maintainers). Unlike every
 * provider API, which reports only *whether* reasoning exists, this
 * catalog enumerates each model's actual thinking vocabulary: effort values
 * ("low"/"high"/"xhigh"/"max"…), budget-token ranges, or a plain on/off
 * toggle. We fetch it daily, cache it on disk, and let it override the
 * shipped [com.androidharness.app.agent.ThinkingSpecs] family table; when the
 * cache is absent or the model is unknown, the shipped table wins, so the app
 * degrades to today's behavior offline.
 */
object ModelsDev {

    private const val API_URL = "https://models.dev/api.json"
    private const val CACHE_FILE = "models-dev.json"
    /** Refresh daily so model-specific reasoning vocabularies do not linger for a week. */
    private const val STALE_MS = 24L * 60 * 60 * 1000

    /** List price in USD per 1,000,000 tokens for a model. */
    @Serializable
    data class ModelCost(
        val input: Double, // $/1M prompt tokens
        val output: Double, // $/1M completion tokens
        val cacheRead: Double = input * 0.25, // $/1M cache read tokens
        val cacheWrite: Double = input, // $/1M cache write tokens
    )

    /** Explicit catalog rates only, USD per million tokens. */
    data class CachePrices(val input: Double, val read: Double?, val write: Double?)

    /** Per-model thinking capability as the catalog reports it. */
    data class Entry(
        /** Tri-state like ModelEntry.reasoning; false definitively means "cannot think". */
        val reasoning: Boolean?,
        /** Exact effort vocabulary, e.g. ["low","high","max"]; null when not enumerated. */
        val effortValues: List<String>?,
        /** Takes an explicit thinking budget (Anthropic budget_tokens, Gemini thinkingBudget). */
        val budgetTokens: Boolean,
        /** Upper bound for the budget, when the catalog knows one. */
        val budgetMax: Int?,
        /** Thinking can be toggled on/off but has no strength dial. */
        val toggle: Boolean,
        /** Context window tokens ("limit.context"), for display in pickers. */
        val contextTokens: Long? = null,
        /** Live model pricing from the models.dev catalog ($ per 1M tokens). */
        val cost: ModelCost? = null,
        val cachePrices: CachePrices? = null,
        val tools: Boolean? = null,
        val imageInput: Boolean? = null,
        val outputTokens: Int? = null,
        val budgetMin: Int? = null,
    )

    /** One provider listed on models.dev: display name, endpoint, protocol hint. */
    data class ProviderInfo(
        val id: String,
        val name: String,
        val api: String?,
        val npm: String?,
        val modelCount: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var entries: Map<String, Map<String, Entry>> = emptyMap()
    @Volatile private var providerInfos: List<ProviderInfo> = emptyList()
    @Volatile private var loaded = false

    /**
     * Hot-swap observable: every [load]/[refresh]/[replaceForTesting] pushes
     * the fresh directory here so Compose screens recompute without reopening.
     */
    private val _providersFlow = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val providersFlow: StateFlow<List<ProviderInfo>> = _providersFlow.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Subset of [ProviderInfo] the app can actually speak to: known protocol
     * plus not shadowed by a curated brand. Shared so the update toast in the
     * provider manager and the add-provider directory count the same rows.
     */
    fun speakableProviders(all: List<ProviderInfo> = providers()): List<ProviderInfo> {
        val curatedHosts = setOf(
            "openrouter.ai", "api.anthropic.com", "generativelanguage.googleapis.com",
            "api.openai.com", "api.groq.com", "api.deepseek.com", "api.together.xyz",
            "api.mistral.ai", "api.cerebras.ai", "api.sambanova.ai", "models.inference.ai.azure.com", "127.0.0.1",
        )
        val curatedIds = setOf(
            "openrouter", "anthropic", "google", "openai", "groq",
            "deepseek", "togetherai", "mistral", "cerebras", "sambanova", "github-models",
        )
        return all.filter { info ->
            protocolFor(info.npm) != null &&
                info.id !in curatedIds &&
                (info.api == null ||
                    curatedHosts.none {
                        it in info.api.substringAfter("://").lowercase()
                    })
        }
    }

    private fun publish(infos: List<ProviderInfo>) {
        providerInfos = infos
        _providersFlow.value = infos
        _revision.update { it + 1 }
    }

    /** models.dev provider key for an endpoint, or null when unmapped. */
    fun providerKeyFor(baseUrl: String?): String? {
        val endpoint = runCatching { java.net.URI(baseUrl ?: return null) }.getOrNull() ?: return null
        val host = endpoint.host?.lowercase() ?: return null
        val path = endpoint.path.orEmpty().trimEnd('/')
        val known = when (host) {
            "openrouter.ai" -> "openrouter"
            "api.kilo.ai", "ai-gateway.kilo.ai" -> "kilo"
            "api.openai.com" -> "openai"
            "api.anthropic.com" -> "anthropic"
            "generativelanguage.googleapis.com" -> "google"
            "api.deepseek.com" -> "deepseek"
            "api.groq.com" -> "groq"
            "api.cerebras.ai" -> "cerebras"
            "api.sambanova.ai" -> "sambanova"
            "models.inference.ai.azure.com" -> "github-models"
            "api.mistral.ai" -> "mistral"
            "api.moonshot.ai" -> "moonshotai"
            "api.moonshot.cn" -> "moonshotai-cn"
            "api.kimi.com" -> "kimi-code-plan-cn"
            "api.kimi.ai" -> "kimi-code-plan-global"
            "open.bigmodel.cn" -> if (path.contains("/coding/")) "zhipuai-coding-plan" else "zhipuai"
            "api.z.ai" -> if (path.contains("/coding/")) "zai-coding-plan" else "zai"
            "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com" -> "alibaba"
            "api.together.xyz", "api.together.ai" -> "togetherai"
            "api.x.ai" -> "xai"
            "opencode.ai" -> if (path.startsWith("/zen/go") || path.startsWith("/inference/go")) "opencode-go"
                else if (path.startsWith("/zen") || path.startsWith("/inference")) "opencode" else null
            else -> null
        }
        // One host may expose several protocols/products. The most specific endpoint wins.
        return providerInfos.mapNotNull { info ->
            val api = runCatching { java.net.URI(info.api ?: "") }.getOrNull() ?: return@mapNotNull null
            val apiPath = api.path.orEmpty().trimEnd('/')
            if (api.host?.lowercase() == host && api.port == endpoint.port && api.scheme == endpoint.scheme &&
                (path == apiPath || path.startsWith("$apiPath/"))) info.id to apiPath.length else null
        }.maxByOrNull { it.second }?.first ?: known
    }

    /**
     * The app's protocol for a catalog provider's SDK hint. Only protocols the
     * app actually speaks are returned; the rest (bedrock/azure/vertex-style
     * auth) stay off the add-provider list.
     */
    fun protocolFor(npm: String?): ProviderType? = when (npm) {
        "@ai-sdk/anthropic" -> ProviderType.ANTHROPIC
        "@ai-sdk/google" -> ProviderType.GEMINI
        "@ai-sdk/openai", "@ai-sdk/openai-compatible", "@ai-sdk/deepseek",
        "@ai-sdk/groq", "@ai-sdk/mistral", "@ai-sdk/togetherai", "@ai-sdk/xai",
        "@ai-sdk/cerebras", "@ai-sdk/deepinfra", "@ai-sdk/perplexity",
        "@openrouter/ai-sdk-provider" -> ProviderType.OPENAI_COMPAT
        else -> null
    }

    /** All providers the catalog lists, alphabetical by display name. */
    fun providers(): List<ProviderInfo> = providerInfos

    /** Model entries for a catalog provider section (for offline browsing). */
    fun modelsFor(providerKey: String?): Map<String, Entry> =
        entries[providerKey].orEmpty()

    /** Idempotent load of the cached catalog into memory (safe on any thread). */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return
        val parsed = runCatching { parse(file.readText()) }.getOrNull() ?: return
        entries = parsed.entries
        publish(parsed.providers)
    }

    /**
     * Downloads the catalog when the cache is stale (or [force]), stores it on
     * disk, and hot-swaps the in-memory maps. Returns an error message on
     * failure, the old cache (or the shipped table) keeps serving.
     */
    suspend fun refresh(context: Context, force: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, CACHE_FILE)
            if (!force && file.exists() &&
                System.currentTimeMillis() - file.lastModified() < STALE_MS
            ) return@withContext null
            try {
                ProviderNetwork.client(client, null).newCall(Request.Builder().url(API_URL).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                    val body = resp.body?.string() ?: return@withContext "Empty response"
                    val parsed = parse(body)
                    file.writeText(body)
                    entries = parsed.entries
                    publish(parsed.providers)
                    null
                }
            } catch (e: Exception) {
                e.message ?: "models.dev refresh failed"
            }
        }

    /** Catalog entry for [modelId] under [providerKey], matching exact ids first. */
    fun entry(providerKey: String?, modelId: String?): Entry? {
        if (providerKey == null || modelId.isNullOrBlank()) return null
        val models = entries[providerKey] ?: return null
        models[modelId]?.let { return it }
        // Catalog ids and user-configured ids differ in vendor prefixing both
        // ways ("anthropic/claude-sonnet-4-5" vs "claude-sonnet-4-5"), fall
        // back to suffix matching, first hit wins.
        val suffix = modelId.substringAfterLast('/')
        models[suffix]?.let { return it }
        return models.entries.firstOrNull { (key, _) ->
            key.substringAfterLast('/') == suffix || key.endsWith("/$modelId")
        }?.value
    }

    /** Finds live cost information for a model across catalog providers. */
    /** Exact provider endpoint and model only. Never borrow another provider's price. */
    fun exactCachePrices(baseUrl: String, model: String): CachePrices? {
        val endpoint = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return null
        val host = endpoint.host?.lowercase() ?: return null
        val path = endpoint.path.orEmpty().trimEnd('/')
        val matches = providerInfos.mapNotNull { info ->
            val api = runCatching { java.net.URI(info.api ?: "") }.getOrNull() ?: return@mapNotNull null
            val apiPath = api.path.orEmpty().trimEnd('/')
            if (api.host?.lowercase() != host || api.scheme != endpoint.scheme || api.port != endpoint.port ||
                !(path == apiPath || path.startsWith("$apiPath/"))) return@mapNotNull null
            info to apiPath.length
        }
        // A host may serve separately priced endpoints. Do not fall back across them.
        val specificity = matches.maxOfOrNull { it.second } ?: return null
        return matches.filter { it.second == specificity }
            .mapNotNull { entries[it.first.id]?.get(model)?.cachePrices }.distinct().singleOrNull()
    }

    fun findCost(providerKey: String?, modelId: String?): ModelCost? {
        if (modelId.isNullOrBlank()) return null
        // Resellers list the same model id at every price from free promo to
        // list rate, and catalog order is alphabetical, not canonical: kenari
        // lists glm-5-3-flash at $0/$0 ahead of every paid entry, which held
        // a whole session's estimate at $0.00 (on-device QA, 2026-09-06). The
        // first match that actually charges wins; a model free everywhere
        // keeps its $0 via the first zero-cost match.
        var freeMatch: ModelCost? = null
        fun charged(cost: ModelCost?): ModelCost? {
            if (cost == null) return null
            if (cost.input > 0 || cost.output > 0) return cost
            if (freeMatch == null) freeMatch = cost
            return null
        }

        // 1. Direct match under providerKey
        if (providerKey != null) {
            charged(entry(providerKey, modelId)?.cost)?.let { return it }
        }
        // 2. Exact match across all providers
        for ((_, models) in entries) {
            charged(models[modelId]?.cost)?.let { return it }
        }
        // 3. Suffix match (e.g. "claude-3-5-sonnet" vs "anthropic/claude-3-5-sonnet")
        val suffix = modelId.substringAfterLast('/')
        for ((_, models) in entries) {
            charged(models[suffix]?.cost)?.let { return it }
            val hit = models.entries.firstOrNull { (k, v) ->
                (k.substringAfterLast('/') == suffix || k.endsWith("/$modelId")) && v.cost != null
            }?.value?.cost
            charged(hit)?.let { return it }
        }
        // 4. Substring / normalized match
        val norm = modelId.lowercase().replace("-", "").replace(".", "").replace("/", "").replace(":", "")
        for ((_, models) in entries) {
            val hit = models.entries.firstOrNull { (k, v) ->
                val kNorm = k.lowercase().replace("-", "").replace(".", "").replace("/", "").replace(":", "")
                (kNorm.contains(norm) || norm.contains(kNorm)) && v.cost != null
            }?.value?.cost
            charged(hit)?.let { return it }
        }
        return freeMatch
    }

    /** Parse output: per-provider model maps plus the searchable provider directory. */
    internal data class Parsed(
        val entries: Map<String, Map<String, Entry>>,
        val providers: List<ProviderInfo>,
    )

    /** Pure parser (unit-testable without disk or network). */
    internal fun parse(body: String): Parsed {
        val root = json.parseToJsonElement(body).jsonObject
        val entriesMap = LinkedHashMap<String, Map<String, Entry>>()
        val providersList = ArrayList<ProviderInfo>()
        for ((providerId, providerEl) in root) {
            val providerObj = providerEl.jsonObject
            val modelsEl = providerObj["models"] ?: continue
            val models = LinkedHashMap<String, Entry>()
            for ((modelId, modelEl) in modelsEl.jsonObject) {
                val obj = modelEl.jsonObject
                val reasoning = obj["reasoning"]?.jsonPrimitive?.booleanOrNull
                var effort: List<String>? = null
                var budget = false
                var budgetMax: Int? = null
                var budgetMin: Int? = null
                var toggle = false
                obj["reasoning_options"]?.jsonArray?.forEach { optEl ->
                    val opt = optEl.jsonObject
                    when (opt["type"]?.jsonPrimitive?.contentOrNull) {
                        "effort" -> effort = opt["values"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        "budget_tokens" -> {
                            budget = true
                            budgetMax = opt["max"]?.jsonPrimitive?.intOrNull
                            budgetMin = opt["min"]?.jsonPrimitive?.intOrNull
                        }
                        "toggle" -> toggle = true
                    }
                }
                val ctx = obj["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.longOrNull
                val costObj = obj["cost"]?.jsonObject
                val cost = if (costObj != null) {
                    val input = costObj["input"]?.jsonPrimitive?.doubleOrNull
                    val output = costObj["output"]?.jsonPrimitive?.doubleOrNull
                    if (input != null && output != null) {
                        val cacheRead = costObj["cache_read"]?.jsonPrimitive?.doubleOrNull ?: (input * 0.25)
                        val cacheWrite = costObj["cache_write"]?.jsonPrimitive?.doubleOrNull ?: input
                        ModelCost(input, output, cacheRead, cacheWrite)
                    } else null
                } else null

                fun explicitRate(key: String) = costObj?.get(key)?.jsonPrimitive?.doubleOrNull
                    ?.takeIf { it.isFinite() && it >= 0 }
                val cachePrices = explicitRate("input")?.let {
                    CachePrices(it, explicitRate("cache_read"), explicitRate("cache_write"))
                }
                val tools = (obj["tool_call"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                val images = ModelCatalog.inputImages((obj["modalities"] as? JsonObject)?.get("input"))
                val output = obj["limit"]?.jsonObject?.get("output")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                models[modelId] = Entry(reasoning, effort, budget, budgetMax, toggle, ctx, cost, cachePrices, tools, images, output, budgetMin)
            }
            entriesMap[providerId] = models
            providersList += ProviderInfo(
                id = providerId,
                name = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: providerId,
                api = providerObj["api"]?.jsonPrimitive?.contentOrNull,
                npm = providerObj["npm"]?.jsonPrimitive?.contentOrNull,
                modelCount = models.size,
            )
        }
        return Parsed(entriesMap, providersList.sortedBy { it.name.lowercase() })
    }

    /** Test hook: swap the in-memory catalog without touching disk. */
    internal fun replaceForTesting(
        map: Map<String, Map<String, Entry>>,
        providers: List<ProviderInfo> = emptyList(),
    ) {
        entries = map
        publish(providers)
        loaded = true
    }
}

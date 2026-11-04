package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderApiKey
import com.androidharness.app.llm.ProviderKeyPool
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.effectiveApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.providerStore by preferencesDataStore(name = "providers")

class ProviderRepository(
    private val context: Context,
    private val keys: KeyStoreManager,
    private val localModels: com.androidharness.app.local.LocalModelManager,
    private val chatGpt: com.androidharness.app.chatgpt.ChatGptAccounts,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listKey = stringPreferencesKey("provider_list")
    private val headersRevision = longPreferencesKey("provider_headers_revision")
    private val catalogRefreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val automaticCatalogChecks = java.util.concurrent.ConcurrentHashMap<ProviderConfig, Long>()

    val providers: Flow<List<ProviderConfig>> = kotlinx.coroutines.flow.combine(context.providerStore.data, localModels.installed, chatGpt.state) { prefs, installed, accounts ->
        HarnessProvider.restoreModels(harnessModels(prefs))
        val saved = prefs[listKey]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val harnessCustom = prefs[customModelsKey(HarnessProvider.ID)]?.let { raw ->
            runCatching {
                json.decodeFromString<List<ModelEntry>>(raw)
            }.getOrDefault(emptyList())
        }?.map { it.id }?.toSet().orEmpty()
        HarnessProvider.customModelIds = harnessCustom
        accounts.accounts.filter { it.connected && it.models.isNotEmpty() }.map { it.config() } +
            listOf(HarnessProvider.config.copy(model = HarnessProvider.sanitize(saved.firstOrNull { it.id == HarnessProvider.ID }?.model, harnessCustom))) + saved.filterNot { it.id == HarnessProvider.ID || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) || com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) || it.baseUrl.startsWith("local://") } + localModels.configs(installed)
    }.combine(settings.settings) { available, saved ->
        ProviderListPreferences.apply(available.map { config ->
            if (com.androidharness.app.llm.ProviderHeaders.editable(config)) config.copy(headers = keys.providerHeaders(config.id)) else config
        }, saved.providerOrder, saved.disabledProviderIds)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = settings.setProviderEnabled(id, enabled)

    suspend fun move(id: String, direction: Int, visibleIds: List<String>) =
        settings.moveProvider(id, direction, providers.first().map { it.id }, visibleIds)

    /**
     * Fetched model catalogs per provider id, persisted so the pickers show
     * every model a provider offers without refetching on each open. Custom
     * models added by the user merge at the top of each list.
     */
    val catalogs: Flow<Map<String, List<ModelEntry>>> = kotlinx.coroutines.flow.combine(context.providerStore.data, localModels.installed, chatGpt.state) { prefs, installed, accounts ->
        val catalogMap = prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(CATALOG_PREFIX) }
            .mapNotNull { (key, value) ->
                val providerId = key.name.removePrefix(CATALOG_PREFIX)
                runCatching {
                    json.decodeFromString(ListSerializer(ModelEntry.serializer()), value as String)
                }.getOrNull()?.let { providerId to it }
            }
            .toMap().toMutableMap()

        catalogMap[HarnessProvider.ID] = harnessModels(prefs)

        prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(CUSTOM_MODELS_PREFIX) }
            .forEach { (key, value) ->
                val providerId = key.name.removePrefix(CUSTOM_MODELS_PREFIX)
                val customList = runCatching {
                    json.decodeFromString(ListSerializer(ModelEntry.serializer()), value as String)
                }.getOrDefault(emptyList())
                if (customList.isNotEmpty()) {
                    val fetched = catalogMap[providerId].orEmpty()
                    val enriched = customList.map { custom -> fetched.firstOrNull { it.id == custom.id }?.let { reported ->
                        reported.copy(custom = true, reasoning = reported.reasoning ?: custom.reasoning,
                            note = custom.note ?: reported.note)
                    } ?: custom }
                    val base = fetched.filterNot { baseEntry ->
                        customList.any { it.id == baseEntry.id }
                    }
                    catalogMap[providerId] = enriched + base
                }
            }

        localModels.configs(installed).forEach { config ->
            catalogMap[config.id] = listOf(ModelEntry(config.model, reasoning = false, note = "On-device text chat"))
        }
        catalogMap.keys.filter { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it) }.forEach { catalogMap.remove(it) }
        accounts.accounts.filter { it.connected }.forEach { catalogMap[it.providerId] = it.models }
        catalogMap
    }

    suspend fun catalog(providerId: String): List<ModelEntry> =
        catalogs.first()[providerId].orEmpty()

    suspend fun refreshCatalog(providerId: String, automatic: Boolean = false): String? =
        catalogRefreshLocks.getOrPut(providerId) { Mutex() }.withLock {
            val provider = current().firstOrNull { it.id == providerId } ?: return@withLock "Provider unavailable"
            if (com.androidharness.app.local.LocalModelCatalog.isLocal(providerId)) return@withLock null
            val connection = provider.copy(name = "", model = "", enabled = true)
            val now = System.nanoTime()
            if (automatic && automaticCatalogChecks[connection]?.let { now - it < 300_000_000_000L } == true)
                return@withLock null
            // Cache failures briefly too: switching models must not repeatedly hit an unavailable /models endpoint.
            automaticCatalogChecks[connection] = now
            val key = if (providerId == HarnessProvider.ID) harnessApiKey()
                else provider.effectiveApiKey(apiKey(providerId))
            val pool = if (com.androidharness.app.llm.ProviderHeaders.editable(provider)) keys.providerKeyPool(providerId) else null
            if (pool?.keys?.isNotEmpty() == true && pool.keys.none { it.enabled }) return@withLock "Enable an API key in connection controls."
            val candidates = pool?.let { com.androidharness.app.llm.RotatingKeyProvider.orderedKeys(providerId, it).map { entry -> entry.value } }.orEmpty().ifEmpty { listOf(key) }
            val result = try {
                var response: ModelCatalog.Result = ModelCatalog.Result.Failed("Provider unavailable")
                for (candidate in candidates) {
                    response = ModelCatalog.listModels(provider, candidate)
                    if (response !is ModelCatalog.Result.Failed || response.status !in setOf(401, 402, 403, 429)) break
                }
                response
            }
            catch (e: kotlinx.coroutines.CancellationException) {
                automaticCatalogChecks.remove(connection)
                throw e
            }
            when (result) {
                is ModelCatalog.Result.Models -> {
                    if (result.models.isEmpty()) return@withLock "No model capabilities reported"
                    val currentConnection = current().firstOrNull { it.id == providerId }?.copy(name = "", model = "", enabled = true)
                    if (currentConnection != connection) return@withLock "Provider connection changed. Try again."
                    saveCatalog(providerId, result.models)
                    null
                }
                is ModelCatalog.Result.Failed -> result.message
            }
        }

    suspend fun detectReasoningLevels(config: ProviderConfig): List<String> {
        fun sameConnection(provider: ProviderConfig?) = provider?.let {
            it.type == config.type && it.baseUrl.trimEnd('/') == config.baseUrl.trimEnd('/') && it.headers == config.headers
        } == true
        check(sameConnection(current().firstOrNull { it.id == config.id })) { "Provider connection changed. Reopen thinking settings." }
        check(!com.androidharness.app.llm.ReasoningDefaults.isLocal(config)) { "This model uses native on/off control." }
        refreshCatalog(config.id)?.let { error(it) } // Explicit check always bypasses the automatic refresh cache.
        check(sameConnection(current().firstOrNull { it.id == config.id })) { "Provider connection changed. Reopen thinking settings." }
        val entry = catalog(config.id).firstOrNull { it.id == config.model }
        check(entry?.reasoningEfforts != null) { "This provider does not report reasoning levels for this model. Keep your levels or edit them manually." }
        val levels = com.androidharness.app.agent.ThinkingSpecs.forProvider(config, entry).reportedLevels
        check(!levels.isNullOrEmpty()) { "No adjustable reasoning levels reported for this model." }
        return levels
    }

    suspend fun customModels(providerId: String): List<ModelEntry> {
        val raw = context.providerStore.data.first()[customModelsKey(providerId)] ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ModelEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    suspend fun resolveHarnessConfig(config: ProviderConfig): ProviderConfig {
        if (config.id != HarnessProvider.ID) {
            if (!com.androidharness.app.llm.ProviderHeaders.editable(config)) return config
            val headers = keys.providerHeaders(config.id)
            if (headers.any { it.enabled }) {
                val current = current().firstOrNull { it.id == config.id }
                check(current?.baseUrl == config.baseUrl && current.type == config.type) {
                    "This provider connection changed. Choose its model again before resuming."
                }
            }
            return config.copy(headers = headers)
        }
        providers.first() // A background resume can arrive before the UI starts collecting.
        val custom = customModels(HarnessProvider.ID).map { it.id }.toSet()
        return config.copy(model = HarnessProvider.sanitize(config.model, custom))
    }

    suspend fun addCustomModel(providerId: String, modelId: String, reasoning: Boolean? = null) {
        if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(providerId)) return
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(providerId)) return
        val clean = modelId.trim()
        if (clean.isBlank()) return
        context.providerStore.edit { prefs ->
            val key = customModelsKey(providerId)
            val raw = prefs[key]
            val existing = if (raw == null) emptyList() else runCatching {
                json.decodeFromString<List<ModelEntry>>(raw)
            }.getOrDefault(emptyList())
            val updated = listOf(ModelEntry(clean, reasoning = reasoning, custom = true)) +
                existing.filterNot { it.id == clean }
            prefs[key] = json.encodeToString(ListSerializer(ModelEntry.serializer()), updated)
        }
    }

    suspend fun removeCustomModel(providerId: String, modelId: String) {
        context.providerStore.edit { prefs ->
            val key = customModelsKey(providerId)
            val raw = prefs[key]
            val existing = if (raw == null) emptyList() else runCatching {
                json.decodeFromString<List<ModelEntry>>(raw)
            }.getOrDefault(emptyList())
            val updated = existing.filterNot { it.id == modelId }
            if (updated.isEmpty()) prefs.remove(key)
            else prefs[key] = json.encodeToString(ListSerializer(ModelEntry.serializer()), updated)
        }
    }

    suspend fun saveCatalog(providerId: String, entries: List<ModelEntry>) {
        context.providerStore.edit { prefs ->
            prefs[catalogKey(providerId)] =
                json.encodeToString(ListSerializer(ModelEntry.serializer()), entries)
            if (providerId == HarnessProvider.ID) prefs[harnessCatalogSource] = HarnessProvider.CATALOG_SOURCE
        }
        if (providerId == HarnessProvider.ID) HarnessProvider.restoreModels(entries)
    }

    // Old catalog_harness snapshots came from Zen or the hardcoded pool, not Kilo.
    private val harnessCatalogSource = stringPreferencesKey("harness_catalog_source")
    private fun harnessModels(prefs: Preferences): List<ModelEntry> = HarnessProvider.cachedModels(
        prefs[catalogKey(HarnessProvider.ID)], prefs[harnessCatalogSource],
    )

    suspend fun add(name: String, type: ProviderType, baseUrl: String, model: String, apiKey: String,
        headers: List<com.androidharness.app.llm.ProviderHeader> = emptyList()): ProviderConfig =
        add(name, type, baseUrl, model, ProviderKeyPool(apiKey.trim().takeIf { it.isNotEmpty() }?.let {
            listOf(ProviderApiKey(value = it))
        }.orEmpty()), headers)

    suspend fun add(name: String, type: ProviderType, baseUrl: String, model: String, apiKeys: ProviderKeyPool,
        headers: List<com.androidharness.app.llm.ProviderHeader> = emptyList()): ProviderConfig {
        require(apiKeys.error() == null) { apiKeys.error().orEmpty() }
        val config = ProviderConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            baseUrl = baseUrl.ifBlank { type.defaultBaseUrl },
            model = model,
            headers = com.androidharness.app.llm.ProviderHeaders.normalized(headers),
        )
        keys.putProviderHeaders(config.id, config.headers)
        keys.putProviderControls(config.id, apiKeys, com.androidharness.app.llm.ProviderProxy())
        save(current() + config)
        return config
    }

    suspend fun update(config: ProviderConfig, apiKey: String?) = updateConnection(config) {
        if (apiKey != null) keys.putKey(config.id, apiKey)
    }

    suspend fun update(config: ProviderConfig, apiKeys: ProviderKeyPool) {
        require(apiKeys.error() == null) { apiKeys.error().orEmpty() }
        updateConnection(config) { keys.putProviderControls(config.id, apiKeys, keys.providerProxy(config.id)) }
    }

    private suspend fun updateConnection(config: ProviderConfig, saveKeys: () -> Unit) {
        automaticCatalogChecks.keys.removeIf { it.id == config.id }
        if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id)) return
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(config.id)) return
        if (config.id == HarnessProvider.ID) {
            val custom = customModels(HarnessProvider.ID).map { it.id }.toSet()
            save(current().map { if (it.id == config.id) HarnessProvider.config.copy(model = HarnessProvider.sanitize(config.model, custom)) else it })
            return
        }
        val headers = com.androidharness.app.llm.ProviderHeaders.normalized(config.headers)
        saveKeys()
        keys.putProviderHeaders(config.id, headers)
        save(current().map { if (it.id == config.id) config else it })
    }

    suspend fun controls(config: ProviderConfig): com.androidharness.app.llm.ProviderKeyPool? {
        if (!com.androidharness.app.llm.ProviderHeaders.editable(config)) return null
        val live = current().firstOrNull { it.id == config.id } ?: return null
        check(live.type == config.type && live.baseUrl.trimEnd('/') == config.baseUrl.trimEnd('/')) {
            "Provider connection changed. Choose its model again before continuing."
        }
        return keys.providerKeyPool(config.id)
    }

    suspend fun saveGlobalProxy(proxy: com.androidharness.app.llm.ProviderProxy) {
        keys.putGlobalProviderProxy(proxy)
        automaticCatalogChecks.clear()
        context.providerStore.edit { it[headersRevision] = (it[headersRevision] ?: 0L) + 1L }
    }

    suspend fun saveControls(config: ProviderConfig, pool: com.androidharness.app.llm.ProviderKeyPool?, proxy: com.androidharness.app.llm.ProviderProxy) {
        val live = current().firstOrNull { it.id == config.id }
        check(live != null && live.type == config.type && live.baseUrl == config.baseUrl) { "Provider connection changed. Reopen its controls." }
        check(!com.androidharness.app.local.LocalModelCatalog.isLocal(config.id)) { "On-device models do not use provider controls." }
        keys.putProviderControls(config.id, if (com.androidharness.app.llm.ProviderHeaders.editable(config)) pool else null, proxy)
        automaticCatalogChecks.keys.removeIf { it.id == config.id }
        context.providerStore.edit { it[headersRevision] = (it[headersRevision] ?: 0L) + 1L }
    }

    suspend fun delete(id: String) {
        automaticCatalogChecks.keys.removeIf { it.id == id }
        if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(id)) return
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(id)) {
            localModels.remove(id.removePrefix(com.androidharness.app.local.LocalModelCatalog.PROVIDER_PREFIX))
            return
        }
        if (id == HarnessProvider.ID) return
        keys.removeKey(id)
        keys.putProviderHeaders(id, emptyList())
        context.providerStore.edit {
            it.remove(catalogKey(id))
            it.remove(customModelsKey(id))
        }
        ModelVisibility.get(context).remove(id)
        save(current().filterNot { it.id == id })
    }

    fun apiKeys(providerId: String): ProviderKeyPool = keys.providerKeyPool(providerId)

    fun apiKey(providerId: String): String? = when {
        com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(providerId) ->
            if (chatGpt.state.value.accounts.any { it.providerId == providerId && it.connected }) "managed-oauth" else "builtin-default-key"
        com.androidharness.app.local.LocalModelCatalog.isLocal(providerId) -> "local"
        providerId == HarnessProvider.ID -> HarnessProvider.KEYLESS
        else -> "builtin-default-key"
    }

    /**
     * Zen ended anonymous access to its free models (keyless calls get a 403
     * FreeTierError), so the built-in provider rides on the key saved for any
     * OpenCode-branded provider. Falls back to the keyless sentinel.
     */
    suspend fun harnessApiKey(): String = harnessApiKey(current())

    fun harnessApiKey(candidates: List<ProviderConfig>): String =
        keys.getKey(HarnessProvider.ID)?.takeIf { it.isNotBlank() }
            ?: candidates.firstOrNull { it.id != HarnessProvider.ID && HarnessProvider.isOpenCode(it) }
                ?.let { keys.getKey(it.id) }?.takeIf { it.isNotBlank() }
            ?: HarnessProvider.KEYLESS

    /**
     * Learned wire protocol per Harness model. The first chat request for a
     * model probes chat/completions vs /messages; the winner is stored here
     * so every later request routes directly. Keyed by bare model id so the
     * pin survives catalog refetches.
     */
    val harnessWires: Flow<Map<String, String>> = context.providerStore.data.map { prefs ->
        prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(WIRE_PREFIX) }
            .mapNotNull { (key, value) ->
                val model = key.name.removePrefix(WIRE_PREFIX)
                (value as? String)?.takeIf { it.isNotEmpty() }?.let { model to it }
            }
            .toMap()
    }

    suspend fun wire(model: String): String? = harnessWires.first()[model]

    suspend fun pinWire(model: String, wire: String) {
        context.providerStore.edit { it[stringPreferencesKey(WIRE_PREFIX + model)] = wire }
    }

    private fun catalogKey(providerId: String) = stringPreferencesKey(CATALOG_PREFIX + providerId)
    private fun customModelsKey(providerId: String) = stringPreferencesKey(CUSTOM_MODELS_PREFIX + providerId)

    private suspend fun current(): List<ProviderConfig> = providers.first()

    private suspend fun save(list: List<ProviderConfig>) {
        val raw = json.encodeToString(ListSerializer(ProviderConfig.serializer()), list.filterNot { com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) })
        context.providerStore.edit {
            it[listKey] = raw
            // Header-only edits leave metadata JSON identical but must refresh collected connections.
            it[headersRevision] = (it[headersRevision] ?: 0L) + 1L
        }
    }

    private companion object {
        const val CATALOG_PREFIX = "catalog_"
        const val CUSTOM_MODELS_PREFIX = "custom_models_"
        const val WIRE_PREFIX = "wire_harness_"
    }
}

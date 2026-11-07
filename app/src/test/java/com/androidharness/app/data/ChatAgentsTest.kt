package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatAgentsTest {
    private val agent = ChatAgentProfile(id = "tutor", name = "Study partner", instructions = "Explain with examples.")
    private fun memory(id: String, agentId: String? = null) = MemoryEntity(id, "Prefers examples", createdAt = 1, updatedAt = 1, section = "CHAT", agentId = agentId)

    @Test fun snapshotsRetainKnowledgeSkillsAndModelAfterPresetChanges() {
        val original = agent.copy(model = ChatModelChoice("provider", "model"), persona = "Patient",
            knowledge = listOf(AgentKnowledge("source", "notes.md", "The Moon orbits Earth.")),
            skills = listOf(AgentSkill("explain", "Use examples.")))
        val stored = ConversationConfig.encode(ConversationConfig(ConversationKind.CHAT, agent = original))
        val edited = original.copy(instructions = "Different", knowledge = emptyList(), model = ChatModelChoice("other", "other"))
        assertNotEquals(edited, ConversationConfig.decode(stored).agent)
        assertEquals(original, ConversationConfig.decode(stored).agent)
        assertEquals(listOf(original), ChatAgentProfile.decodeList(ChatAgentProfile.encodeList(listOf(original))))
        assertEquals(original, Json.decodeFromString(ChatAgentProfile.serializer(), Json.encodeToString(ChatAgentProfile.serializer(), original)))
    }

    @Test fun agentToolsAreASubsetOfChatAndCannotGrantCodeOrUnselectedSkills() {
        val tools = ChatAgentPolicy.tools(agent.copy(capabilities = emptySet(), memory = AgentMemory.OFF))
        assertFalse(tools.any { it in setOf("shell", "read_file", "write_file", "task", "skill_view", "remember", "recall", "web_search", "github_api", "view_chat_image") })
        assertTrue(tools.containsAll(setOf("read_chat_text", "search_chats")))
        assertTrue(ChatAgentPolicy.tools(agent).contains("web_search"))
        assertTrue(ChatAgentPolicy.tools(agent.copy(skills = listOf(AgentSkill("test", "Instructions")))).contains("skill_view"))
        assertTrue(ChatAgentPolicy.tools(agent.copy(knowledge = listOf(AgentKnowledge(name = "a", text = "b")))).contains("search_agent_knowledge"))
        assertNull(ConversationConfig().allowedTools)
    }

    @Test fun historyRequiresMatchingAgentOrExplicitSharedAccess() {
        val regular = ConversationConfig(ConversationKind.CHAT)
        val code = ConversationConfig()
        val own = regular.copy(agent = agent)
        val foreign = regular.copy(agent = agent.copy(id = "writer"))
        assertTrue(ChatAgentPolicy.canReadHistory(own, own.copy(draftText = "other")))
        for (target in listOf(regular, code, foreign)) assertFalse(ChatAgentPolicy.canReadHistory(own, target))
        assertFalse(ChatAgentPolicy.canReadHistory(regular, own))
        val shared = own.copy(agent = agent.copy(sharedHistory = true))
        assertTrue(ChatAgentPolicy.canReadHistory(shared, regular))
        assertTrue(ChatAgentPolicy.canReadHistory(shared, code))
        assertFalse(ChatAgentPolicy.canReadHistory(shared, foreign))
    }

    @Test fun privateMemoriesDoNotEnterOrdinaryOrOtherAgentsPrompts() {
        val private = memory("own", "tutor")
        assertTrue(MemoryAccess.belongsTo(private, MemorySection.CHAT, "tutor"))
        assertFalse(MemoryAccess.belongsTo(private, MemorySection.CHAT))
        assertFalse(MemoryAccess.belongsTo(private, MemorySection.CHAT, "writer"))
        assertFalse(MemoryAccess.belongsTo(memory("shared"), MemorySection.CHAT, "tutor"))
        assertFalse(MemoryAccess.belongsTo(private, MemorySection.CODE, "tutor"))
    }

    @Test fun consolidationAndBackupDeduplicationKeepAgentBoundaries() {
        val entries = listOf(memory("a", "tutor"), memory("b", "tutor").copy(text = "Likes astronomy"),
            memory("c", "writer"), memory("d"))
        val plans = MemoryConsolidation.plans(entries)
        assertEquals(1, plans.size)
        assertEquals("tutor", plans.single().agentId)
        val incoming = entries.drop(1).map { it.toBackupMemory() }
        val merged = MemoryBackupMerge.plan(listOf(entries.first()), incoming)
        assertEquals(3, merged.size)
        assertEquals("writer", MemoryBackupMerge.entity(merged.first { it.id == "c" }).agentId)
        assertNull(MemoryBackupMerge.normalize(memory("bad", "tutor").toBackupMemory().copy(section = "CODE")))
    }

    @Test fun knowledgeSearchFindsRelevantSourcesAndBoundsOutput() {
        val sources = listOf(AgentKnowledge("moon", "astronomy.md", "The Moon orbits Earth. ".repeat(150)),
            AgentKnowledge("garden", "garden.txt", "Tomatoes need sunlight."))
        val hits = AgentKnowledgeSearch.search(sources, "Moon orbit")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.file.id == "moon" && it.passage >= 1 && it.text.length <= 1400 })
        assertTrue(hits.size <= 6)
        assertTrue(AgentKnowledgeSearch.search(sources, "Moon", "garden").isEmpty())
        assertTrue(AgentKnowledgeSearch.search(sources, "unmatchedword").isEmpty())
    }

    @Test fun oversizeInvalidAndDuplicatePresetsAreRejected() {
        val invalid = listOf(agent.copy(name = ""), agent.copy(avatar = "bad"), agent.copy(instructions = "x".repeat(8001)),
            agent.copy(starters = List(5) { "hello" }), agent.copy(knowledge = listOf(AgentKnowledge(name = "notes", text = "x".repeat(32001)))),
            agent.copy(skills = listOf(AgentSkill("huge", "x".repeat(8001)))))
        invalid.forEach { assertTrue(runCatching { it.validated() }.isFailure) }
        assertTrue(runCatching { ChatAgentProfile.encodeList(listOf(agent, agent)) }.isFailure)
        assertTrue(runCatching { ChatAgentProfile.encodeList(List(25) { agent.copy(id = "$it") }) }.isFailure)
    }

    @Test fun settingsBackupIncludesProfilesAndFiltersNonportableModelReferences() {
        val settings = AppSettings(chatAgents = listOf(agent.copy(model = ChatModelChoice("provider", "model"))))
        val exported = SettingsBackupPolicy.exportSettings(settings, setOf("provider"))
        assertEquals(settings.chatAgents, exported.chatAgents)
        assertNull(SettingsBackupPolicy.exportSettings(settings, emptySet()).chatAgents.single().model)
        val encoded = Json.encodeToString(SettingsBackupFile.serializer(), SettingsBackupFile(settings = exported))
        assertEquals(settings.chatAgents, Json.decodeFromString(SettingsBackupFile.serializer(), encoded).settings.chatAgents)
        assertTrue(runCatching { SettingsBackupValidation.validate(SettingsBackupFile(settings = settings.copy(chatAgents = listOf(agent.copy(name = ""))))) }.isFailure)
    }

    @Test fun persistentSixDeveloperSpecialistTeamValidatesAndMergesIdempotently() {
        assertEquals(6, PersistentSpecialistTeam.defaultSpecialists.size)
        val encoded = ChatAgentProfile.encodeList(PersistentSpecialistTeam.defaultSpecialists)
        val decoded = ChatAgentProfile.decodeList(encoded)
        assertEquals(6, decoded.size)
        val mergedOnce = PersistentSpecialistTeam.mergePreservingConfigured(emptyList(), "harness", "cohere/north-mini-code:free")
        assertEquals(6, mergedOnce.size)
        val mergedTwice = PersistentSpecialistTeam.mergePreservingConfigured(mergedOnce, "harness", "cohere/north-mini-code:free")
        assertEquals(mergedOnce, mergedTwice)
    }
}

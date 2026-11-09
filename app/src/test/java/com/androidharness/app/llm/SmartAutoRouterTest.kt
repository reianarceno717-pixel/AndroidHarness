package com.androidharness.app.llm

import com.androidharness.app.core.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SmartAutoRouterTest {

    @Test
    fun `classifies image attachment as vision category`() {
        val category = SmartAutoRouter.classify(
            prompt = "What is in this diagram?",
            images = listOf(ImageRef(name = "img1", mime = "image/png", bytes = 1024, width = 100, height = 100)),
        )
        assertEquals(SmartAutoRouter.TaskCategory.VISION, category)
    }

    @Test
    fun `classifies coding and bug fix task as coding category`() {
        val category = SmartAutoRouter.classify(
            prompt = "Fix the NullPointerException in Kotlin AppContainer class and refactor the repository interface with tests",
        )
        assertEquals(SmartAutoRouter.TaskCategory.CODING, category)
    }

    @Test
    fun `classifies quick question as quick general category`() {
        val category = SmartAutoRouter.classify(
            prompt = "What is the capital of France?",
        )
        assertEquals(SmartAutoRouter.TaskCategory.QUICK_GENERAL, category)
    }

    @Test
    fun `routes vision task to valid model`() {
        val current = ProviderConfig("harness", "Harness", ProviderType.OPENAI_COMPAT, "https://opencode.ai/zen/v1", "openai-fast")
        val routed = SmartAutoRouter.routeModel(
            category = SmartAutoRouter.TaskCategory.VISION,
            currentConfig = current,
            availableConfigs = listOf(current),
            catalogs = emptyMap(),
        )
        assertNotNull(routed.model)
    }
}

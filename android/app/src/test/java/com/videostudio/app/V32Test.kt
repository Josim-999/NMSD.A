package com.videostudio.app

import com.anthropic.models.messages.OutputConfig
import com.videostudio.app.ai.ClaudeStudio
import com.videostudio.app.data.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V32Test {
    @Test fun researchPromptAsksForDatedSourcedFacts() {
        val p = Project(id = "t1", topic = "Why Americans are sleeping less")
        val text = ClaudeStudio.researchPrompt(p, LocalDate.of(2026, 11, 27))
        assertTrue(text.contains("Today is 2026-11-27"))
        for (part in listOf("ANGLE:", "NEW:", "STORY:", "FACTS:", "COMPARISONS:", "MYTHS:", "HOOK SEEDS:", "Never invent")) assertTrue(part, text.contains(part))
    }

    @Test fun requestUsesBestModelLiveSearchAndFallbacks() {
        val studio = ClaudeStudio("test-key")
        assertEquals("claude-opus-5-5", studio.model)
        val params = studio.params("sys", "topic", search = true, effort = OutputConfig.Effort.HIGH)
        assertEquals("claude-opus-5-5", params.model().asString())
        val body = params._body().toString() + params._additionalBodyProperties().toString()
        assertTrue(body, body.contains("web_search_20260209") || params.tools().orElse(emptyList()).any { it.isWebSearchTool20260209() })
        assertTrue(params._additionalBodyProperties().containsKey("fallbacks"))
        assertTrue(params._headers().values("anthropic-beta").contains("server-side-fallback-2026-07-01"))
        val plain = studio.params("sys", "topic", search = false, effort = OutputConfig.Effort.HIGH)
        assertTrue(plain.tools().orElse(emptyList()).isEmpty())
    }
}

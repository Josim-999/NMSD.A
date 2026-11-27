package com.videostudio.app

import com.videostudio.app.ai.ClaudeStudio
import com.videostudio.app.data.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
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
        val body = studio.body("sys", "topic", search = true)
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("high", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20260209", tool.getString("type"))
        assertEquals("web_search", tool.getString("name"))
        assertEquals("US", tool.getJSONObject("user_location").getString("country"))
        assertEquals("server-side-fallback-2026-07-01", studio.headers()["anthropic-beta"])
        assertEquals("test-key", studio.headers()["x-api-key"])
        assertTrue(!studio.body("sys", "topic", search = false).has("tools"))
        // pause_turn: the assistant turn is sent back after the user message.
        val resumed = studio.body("s", "u", true, history = org.json.JSONArray().put(org.json.JSONObject().put("role", "assistant").put("content", org.json.JSONArray())))
        assertEquals("assistant", resumed.getJSONArray("messages").getJSONObject(1).getString("role"))
    }
}

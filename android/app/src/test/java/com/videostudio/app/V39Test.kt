package com.videostudio.app

import com.videostudio.app.ai.ContentGate
import com.videostudio.app.ai.Gemini
import com.videostudio.app.ai.MediaFinder
import com.videostudio.app.ai.VisionCheck
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Source
import com.videostudio.app.voice.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.9: the specialist's audit, turned into rules that can never break again. */
class V39Test {
    /** The criticised video, scene by scene. */
    private fun criticised() = Project(id = "bad", topic = "AI agent").apply {
        writer = "built-in writer"
        scenes = mutableListOf(
            Scene("AI agent is not what you think. In 60 seconds, you'll know the part most people miss.", type = "hook", text = "AI agent is not what you think"),
            Scene("The part most people miss.", type = "statement", text = "The part most people miss"),
            Scene("An intelligent agent is an entity that perceives its environment and takes actions.", type = "statement", text = "What an AI agent is"),
            Scene("The AI boom is an ongoing period of rapid progress that started in the 2010s.", type = "stat", text = "2010", value = "2010"),
            Scene("The part most people miss.", type = "statement", text = "The part most people miss"),
            Scene("Generative AI technologies include text and image tools.", type = "list", text = "Quick facts", items = listOf("Generative AI technologies include", "This period is sometimes", "Scale AI, Inc. is")),
            Scene("This period is sometimes contrasted with previous AI winters.", type = "statement", text = "This period is sometimes"),
            Scene("Scale AI, Inc. is an American artificial intelligence infrastructure and software company based in San Francisco.", type = "statement", text = "Originally focused on data annotation"),
            Scene("Now you know the real story behind AI agent. And that is exactly why.", type = "outro", text = "What surprised you most about AI agent?"),
        )
    }

    @Test fun specialistsVideoIsBlockedForEveryReason() {
        val issues = ContentGate.check(criticised())
        val all = issues.joinToString("\n")
        println(all)
        assertTrue("built-in writer", all.contains("without AI"))
        assertTrue("cut-off cards", all.contains("\"Generative AI technologies include\"") && all.contains("\"This period is sometimes\"") && all.contains("\"Scale AI, Inc. is\""))
        assertTrue("dangling ending", all.contains("last line is cut off"))
        assertTrue("duplicate hook filler", all.contains("repeat the same line"))
        assertTrue("corporate bio", all.contains("company-profile"))
        assertTrue("unsourced numbers", all.contains("Numbers without any research"))
    }

    @Test fun topicDriftIsCaught() {
        val p = Project(id = "drift", topic = "AI Agents Are Becoming A Cybersecurity Threat").apply {
            writer = "Gemini studio"
            scenes = mutableListOf(
                Scene("Cybersecurity teams just met a new kind of attacker.", type = "hook", text = "A new kind of attacker"),
                Scene("Scale started by labeling data for self-driving cars.", type = "statement", text = "Labeling data"),
                Scene("Then it moved into government contracts.", type = "statement", text = "Government contracts"),
                Scene("Its founder became the youngest self-made billionaire.", type = "statement", text = "A young billionaire"),
                Scene("Today it sells software to large companies.", type = "statement", text = "Enterprise software"),
                Scene("So what will it build next?", type = "outro", text = "What comes next?"),
            )
        }
        assertTrue(ContentGate.check(p).joinToString().contains("drifts off the topic"))
    }

    @Test fun aCleanSourcedScriptPasses() {
        val p = Project(id = "good", topic = "AI agents and cybersecurity").apply {
            writer = "Gemini 3 Pro studio"
            researchNotes = "FACTS: Google's Big Sleep agent found a real SQLite vulnerability in 2024 | Nov 2024 | Google Project Zero | https://googleprojectzero.blogspot.com"
            sources = listOf(Source("Big Sleep found an SQLite flaw in 2024", "https://googleprojectzero.blogspot.com"))
            scenes = mutableListOf(
                Scene("An AI agent found a security hole that human experts missed.", type = "hook", text = "An AI found the hole"),
                Scene("Google's Big Sleep agent was hunting bugs in the SQLite database engine.", type = "statement", text = "Big Sleep goes hunting"),
                Scene("In 2024, the agent found a real flaw before attackers could use it.", type = "statement", text = "Found before attackers"),
                Scene("That same skill could help criminals, so defenders now race to use agents first.", type = "question", text = "Who uses agents first?"),
                Scene("Security teams now pair human experts with AI agents on every audit.", type = "list", text = "The new playbook", items = listOf("Agents scan code", "Humans confirm", "Fixes ship faster")),
                Scene("So the next hole in your apps might be found by an AI agent before anyone else.", type = "outro", text = "Would you trust an AI agent?"),
            )
        }
        val issues = ContentGate.check(p)
        assertEquals(issues.joinToString("\n"), 0, issues.size)
    }

    @Test fun picturesNeedAFilmableSearchAndAYes() {
        assertTrue(MediaFinder.isAbstract("the part most people miss"))
        assertTrue(!MediaFinder.isAbstract("server room data center"))
        assertTrue(VisionCheck.answer("YES"))
        assertTrue(!VisionCheck.answer("NO, it is a guitar"))
        assertTrue("unclear counts as no", !VisionCheck.answer("Maybe"))
    }

    @Test fun premiumGeminiPrefersProThenFlash() {
        val chain = Gemini.premiumChain(listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-pro-preview", "gemini-pro-latest", "gemini-2.5-flash-lite", "gemma-3-27b-it", "gemini-2.5-flash-image"))
        assertEquals("gemini-pro-latest", chain.first())
        assertTrue(chain.indexOf("gemini-3-pro-preview") < chain.indexOf("gemini-2.5-pro"))
        assertTrue(chain.indexOf("gemini-2.5-pro") < chain.indexOf("gemini-2.5-flash"))
        assertTrue("never lite, gemma or image models for scripts", chain.none { it.contains("lite") || it.startsWith("gemma") || it.contains("image") })
        assertEquals("gemini-2.5-flash-lite", Gemini.visionChain(listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")).first())
    }

    @Test fun acronymsAreSpelledForTheVoiceOnly() {
        assertEquals("Models learn from R-L-H-F and G-P-Ts.", Speech.forVoice("Models learn from RLHF and GPTs."))
        assertEquals("NASA and AI stay as they are.", Speech.forVoice("NASA and AI stay as they are."))
    }
}

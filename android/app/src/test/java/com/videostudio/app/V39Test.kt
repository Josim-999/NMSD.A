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
        // 3.10: softer drift is advice for the editor (not a block); a script that never mentions its topic is blocked.
        assertTrue(ContentGate.advice(p).joinToString().contains("Bring the topic"))
        p.scenes[0] = Scene("A new kind of attacker just showed up.", type = "hook", text = "A new kind of attacker")
        p.title = ""
        assertTrue(ContentGate.check(p).joinToString().contains("never mentions its topic"))
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

    @Test fun ownersAiSubscriptionScriptIsNotWronglyBlocked() {
        val p = Project(id = "subs", topic = "AI Subscription Trap").apply {
            writer = "Gemini 2.5 Pro studio"
            title = "AI Subscription Trap"
            researchNotes = "FACTS: ChatGPT Plus costs 20 dollars a month | 2026 | OpenAI pricing | https://openai.com/chatgpt/pricing"
            scenes = mutableListOf(
                Scene("You might be paying for three AI subscriptions and using one.", type = "hook", text = "Paying for three AIs?"),
                Scene("ChatGPT Plus alone costs 20 dollars every month.", type = "stat", text = "20 dollars a month", value = "$20"),
                Scene("Add Gemini and Claude plans and the bill quietly triples.", type = "statement", text = "The bill triples"),
                Scene("Most of us forget to cancel the trial we started.", type = "statement", text = "Forgotten free trials"),
                Scene("Check your app store today and cancel what you do not use.", type = "list", text = "Do this today", items = listOf("Open subscriptions", "Cancel unused plans")),
                Scene("So before you renew, ask which AI subscription you really need.", type = "outro", text = "What AI tools do you pay for?"),
            )
        }
        val issues = ContentGate.check(p)
        assertEquals(issues.joinToString("\n"), 0, issues.size)
    }

    @Test fun freeTierKeyIsRecognised() {
        val err = """{"error":{"code":429,"message":"You exceeded your current quota","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"50"}]}]}}"""
        val q = Gemini.quotaInfo(err)
        assertTrue(q.daily && q.freeTier)
        val paid = """{"error":{"code":429,"message":"quota","details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel","quotaValue":"1000"}]}]}}"""
        assertTrue(!Gemini.quotaInfo(paid).freeTier)
        assertTrue(Gemini.BILLING_HELP.contains("billing") && Gemini.BILLING_HELP.contains("does NOT cover the API"))
    }

    @Test fun newestGenerationFirstLikeTheOriginalApp() {
        // The owner's key (from the log): the original app used gemini-3.8-flash; 2.5 Pro and omni previews must not lead.
        val names = listOf("gemini-2.5-pro", "gemini-3.8-flash", "gemini-omni-1.1-flash", "gemini-omni-1.2-flash", "gemini-omni-flash-preview", "gemini-2.5-flash", "gemini-3.1-flash-lite")
        val chain = Gemini.premiumChain(names)
        assertEquals("gemini-3.8-flash", chain.first())
        assertTrue(chain.indexOf("gemini-2.5-pro") < chain.indexOf("gemini-2.5-flash"))
        assertTrue("named preview families after the numbered ones", chain.indexOf("gemini-omni-1.2-flash") > chain.indexOf("gemini-2.5-flash"))
        assertTrue(chain.none { it.contains("lite") })
        val withSettings = Gemini.premiumChain(names, listOf("gemini-3.8-flash", "gemini-3.1-flash-lite"))
        assertEquals("gemini-3.8-flash", withSettings.first())
        assertTrue("a newer Pro of the same generation still wins", Gemini.premiumChain(listOf("gemini-3.8-flash", "gemini-3.8-pro")).first() == "gemini-3.8-pro")
    }

    @Test fun quotaDetailNamesTheProject() {
        val err = """{"error":{"code":429,"message":"Quota exceeded for quota metric 'Generate Content API requests per day' of service 'generativelanguage.googleapis.com' for consumer 'project_number:123456789012'.","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"50"}]}]}}"""
        val d = Gemini.quotaDetail(err)
        assertTrue(d, d.contains("FreeTier") && d.contains("limit 50") && d.contains("project 123456789012"))
        assertTrue(Gemini.BILLING_HELP.contains("Billing is per PROJECT"))
    }
}

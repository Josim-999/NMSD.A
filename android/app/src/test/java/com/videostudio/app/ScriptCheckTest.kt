package com.videostudio.app

import com.videostudio.app.ai.ScriptCheck
import com.videostudio.app.data.Scene
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCheckTest {
    private fun s(vararg lines: String) = lines.map { Scene(it) }

    @Test fun aPlaybookScriptPasses() {
        val good = s(
            "This free AI tool edits 10 videos in one minute.",
            "By the end, you'll have the exact prompt.",
            "Most creators spend 3 hours on one Short.",
            "CapCut's AI does it in 60 seconds.",
            "But here's the part nobody mentions.",
            "You need one setting, called Auto Cut.",
            "Turn it on, then paste your script.",
            "It finds 40 cuts and adds captions for you.",
            "I tested it on 12 videos last week.",
            "Watch time went up by 30 percent.",
            "Try it on your next video today.",
            "Your edits take minutes, not hours.",
            "Would you let AI edit your videos?",
        )
        val r = ScriptCheck.check(good, 30)
        assertTrue("score ${r.score}: ${r.issues}", r.good)
    }

    @Test fun aWeakScriptGetsSpecificFixes() {
        val weak = s(
            "Hey guys, in this video we are going to talk about the amazing world of artificial intelligence and how it is a game-changer.",
            "Basically, AI can unlock many things in today's world.",
            "It is really helpful for a lot of people in many different ways.",
            "It is important to note that it keeps getting better over time.",
            "It is something that everyone should learn about soon.",
            "Thanks for watching.",
        )
        val r = ScriptCheck.check(weak, 60)
        assertTrue(r.score < ScriptCheck.PASS)
        val text = r.issues.joinToString(" ")
        assertTrue(text, text.contains("hook has"))
        assertTrue(text, text.contains("hey guys"))
        assertTrue(text, text.contains("game-changer") && text.contains("basically"))
        assertTrue(text, text.contains("question"))
        assertTrue(text, text.contains("words;"))
    }
}

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
            "Here is the exact prompt I paste every time.",
            "Cut the silences, keep every laugh, add bold captions.",
            "It reads your script and matches each cut to a line.",
            "Then it picks music that fits the mood.",
            "Most people skip the second setting, called Smart Zoom.",
            "That one adds 2 zooms every 10 seconds.",
            "Viewers stay because the frame never sits still.",
            "My best Short got 41,000 views this way.",
            "The whole edit took me 4 minutes.",
            "So the tool is not the secret.",
            "The prompt and the zooms are.",
            "Would you let AI edit your videos?",
        )
        val research = "CapCut Auto Cut makes 10 videos in 60 seconds; creators spend 3 hours; 40 cuts; 12 videos tested; 30 percent; 2 zooms every 10 seconds; 41,000 views; 4 minutes"
        val r = ScriptCheck.check(good, 55, research = research)
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
        assertTrue(r.issues.any { it.contains("under 50 s") })
        assertTrue(r.score < ScriptCheck.PASS)
        val text = r.issues.joinToString(" ")
        assertTrue(text, text.contains("hook has"))
        assertTrue(text, text.contains("hey guys"))
        assertTrue(text, text.contains("game-changer") && text.contains("basically"))
        assertTrue(text, text.contains("question"))
        assertTrue(text, text.contains("words;"))
    }

    @Test fun flagsSignOffsAndUnsourcedNumbers() {
        val lines = s("This tool saves 73% of your time.", "You will see why.", "Thanks for watching, subscribe for more.")
        val r = ScriptCheck.check(lines, 55, research = "The tool saves 40% of editing time.")
        val t = r.issues.joinToString(" ")
        assertTrue(t, t.contains("sign-off"))
        assertTrue(t, t.contains("73%"))
        val noSource = ScriptCheck.check(s("It grew 250% in 2024.", "You saw that."), 55)
        assertTrue(noSource.issues.joinToString(" ").contains("without any source"))
    }
}

package com.videostudio.app

import com.videostudio.app.ai.MediaFinder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoSweepLiveTest {
    @Test fun sweep() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        val logs = ArrayList<String>()
        val f = MediaFinder(File("build/logo-sweep").apply { mkdirs() }, log = { logs.add(it) })
        val brands = listOf("OpenAI" to "openai.com", "ChatGPT" to "chatgpt.com", "Google Gemini" to "gemini.google.com", "Microsoft" to "microsoft.com",
            "Apple" to "apple.com", "Amazon" to "amazon.com", "Tesla" to "tesla.com", "Netflix" to "netflix.com", "Nike" to "nike.com", "McDonald's" to "mcdonalds.com",
            "Samsung" to "samsung.com", "Spotify" to "spotify.com", "NASA" to "nasa.gov", "Meta" to "meta.com", "Instagram" to "instagram.com", "TikTok" to "tiktok.com",
            "Claude" to "claude.ai", "Anthropic" to "anthropic.com", "Coca-Cola" to "coca-cola.com", "Starbucks" to "starbucks.com", "Walmart" to "walmart.com", "SpaceX" to "spacex.com",
            "Nvidia" to "nvidia.com", "YouTube" to "youtube.com", "Tesla" to "", "DeepSeek" to "deepseek.com")
        var ok = 0
        for ((b, d) in brands) {
            val t = System.currentTimeMillis()
            val l = runCatching { f.logo(b, d) }.getOrElse { println("ERR $b ${it.message}"); null }
            if (l != null) ok++
            println("SWEEP %-14s %-20s -> %s (%d ms)".format(b, d, l?.javaClass?.simpleName ?: "NONE", System.currentTimeMillis() - t))
        }
        println("SWEEP total $ok/${brands.size}")
        logs.filter { it.startsWith("No verified") }.forEach { println("SWEEPLOG $it") }
    }
}

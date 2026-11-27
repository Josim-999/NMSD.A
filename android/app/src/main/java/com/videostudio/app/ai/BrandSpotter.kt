package com.videostudio.app.ai

import com.videostudio.app.data.Scene

/**
 * Safety net for logos: when a scene names a well-known company, product or app but the script did
 * not mark it ("brand"/"domain"), this marks it with the brand's official domain, so the verified
 * logo appears. Case-sensitive whole-word matches only; names that are also ordinary words
 * ("Apple", "Amazon", "Meta"...) count only mid-sentence, never as the first word.
 */
object BrandSpotter {
    /** Brand name as written -> official domain. Longer names are tried first. */
    val BRANDS: Map<String, String> = linkedMapOf(
        // AI and tech
        "ChatGPT" to "chatgpt.com", "OpenAI" to "openai.com", "Google Gemini" to "gemini.google.com", "Gemini" to "gemini.google.com",
        "Claude" to "claude.ai", "Anthropic" to "anthropic.com", "Microsoft Copilot" to "copilot.microsoft.com", "Copilot" to "copilot.microsoft.com",
        "DeepSeek" to "deepseek.com", "Perplexity" to "perplexity.ai", "Midjourney" to "midjourney.com", "Mistral" to "mistral.ai", "Grok" to "x.ai", "xAI" to "x.ai",
        "Hugging Face" to "huggingface.co", "Nvidia" to "nvidia.com", "NVIDIA" to "nvidia.com", "Intel" to "intel.com", "AMD" to "amd.com", "Qualcomm" to "qualcomm.com",
        "Google" to "google.com", "YouTube" to "youtube.com", "Android" to "android.com", "Gmail" to "gmail.com", "Microsoft" to "microsoft.com", "Windows" to "microsoft.com",
        "Xbox" to "xbox.com", "Apple" to "apple.com", "iPhone" to "apple.com", "iPad" to "apple.com", "MacBook" to "apple.com", "Siri" to "apple.com",
        "Samsung" to "samsung.com", "Sony" to "sony.com", "PlayStation" to "playstation.com", "Nintendo" to "nintendo.com", "Huawei" to "huawei.com", "Xiaomi" to "mi.com",
        "IBM" to "ibm.com", "Oracle" to "oracle.com", "Salesforce" to "salesforce.com", "Adobe" to "adobe.com", "Photoshop" to "adobe.com", "Canva" to "canva.com",
        "Figma" to "figma.com", "Zoom" to "zoom.us", "Slack" to "slack.com", "Dropbox" to "dropbox.com", "GitHub" to "github.com", "Linux" to "linux.org", "Firefox" to "mozilla.org",
        "Tesla" to "tesla.com", "SpaceX" to "spacex.com", "Starlink" to "starlink.com", "NASA" to "nasa.gov", "Boeing" to "boeing.com", "Airbus" to "airbus.com",
        // Social and apps
        "Facebook" to "facebook.com", "Instagram" to "instagram.com", "WhatsApp" to "whatsapp.com", "Threads" to "threads.net", "Meta" to "meta.com", "TikTok" to "tiktok.com",
        "Snapchat" to "snapchat.com", "Reddit" to "reddit.com", "LinkedIn" to "linkedin.com", "Pinterest" to "pinterest.com", "Discord" to "discord.com", "Telegram" to "telegram.org",
        "Twitter" to "x.com", "Twitch" to "twitch.tv", "Spotify" to "spotify.com", "Netflix" to "netflix.com", "Disney+" to "disneyplus.com", "Disney" to "disney.com",
        "Hulu" to "hulu.com", "HBO" to "hbo.com", "Uber" to "uber.com", "Lyft" to "lyft.com", "Airbnb" to "airbnb.com", "DoorDash" to "doordash.com", "Duolingo" to "duolingo.com",
        "Wikipedia" to "wikipedia.org", "PayPal" to "paypal.com", "Venmo" to "venmo.com", "Stripe" to "stripe.com", "Robinhood" to "robinhood.com", "Coinbase" to "coinbase.com",
        "Bitcoin" to "bitcoin.org", "Ethereum" to "ethereum.org", "Shopify" to "shopify.com", "eBay" to "ebay.com", "Etsy" to "etsy.com", "Alibaba" to "alibaba.com", "Temu" to "temu.com", "Shein" to "shein.com",
        // Retail, food, consumer
        "Amazon" to "amazon.com", "Walmart" to "walmart.com", "Costco" to "costco.com", "Target" to "target.com", "IKEA" to "ikea.com", "Best Buy" to "bestbuy.com", "Home Depot" to "homedepot.com",
        "McDonald's" to "mcdonalds.com", "Starbucks" to "starbucks.com", "Coca-Cola" to "coca-cola.com", "Pepsi" to "pepsi.com", "PepsiCo" to "pepsico.com", "Burger King" to "bk.com",
        "KFC" to "kfc.com", "Subway" to "subway.com", "Domino's" to "dominos.com", "Taco Bell" to "tacobell.com", "Chipotle" to "chipotle.com", "Red Bull" to "redbull.com", "Nestlé" to "nestle.com",
        "Nike" to "nike.com", "Adidas" to "adidas.com", "Puma" to "puma.com", "Under Armour" to "underarmour.com", "Lululemon" to "lululemon.com", "Zara" to "zara.com", "H&M" to "hm.com",
        "Rolex" to "rolex.com", "Gucci" to "gucci.com", "Louis Vuitton" to "louisvuitton.com", "Chanel" to "chanel.com", "LEGO" to "lego.com", "Mattel" to "mattel.com",
        // Cars
        "Toyota" to "toyota.com", "Honda" to "honda.com", "Ford" to "ford.com", "Chevrolet" to "chevrolet.com", "BMW" to "bmw.com", "Mercedes-Benz" to "mercedes-benz.com",
        "Audi" to "audi.com", "Volkswagen" to "vw.com", "Porsche" to "porsche.com", "Ferrari" to "ferrari.com", "Lamborghini" to "lamborghini.com", "Hyundai" to "hyundai.com", "Kia" to "kia.com",
        "Nissan" to "nissanusa.com", "Rivian" to "rivian.com", "BYD" to "byd.com",
        // Finance, media, other
        "Visa" to "visa.com", "Mastercard" to "mastercard.com", "American Express" to "americanexpress.com", "JPMorgan" to "jpmorganchase.com", "Goldman Sachs" to "goldmansachs.com",
        "Berkshire Hathaway" to "berkshirehathaway.com", "BlackRock" to "blackrock.com", "FedEx" to "fedex.com", "UPS" to "ups.com", "Delta" to "delta.com", "Shell" to "shell.com",
        "ExxonMobil" to "exxonmobil.com", "Pfizer" to "pfizer.com", "Moderna" to "modernatx.com", "CNN" to "cnn.com", "BBC" to "bbc.com", "ESPN" to "espn.com", "NFL" to "nfl.com", "NBA" to "nba.com",
        "FIFA" to "fifa.com", "Olympics" to "olympics.com", "WHO" to "who.int", "UNICEF" to "unicef.org", "Harvard" to "harvard.edu", "MIT" to "mit.edu", "Stanford" to "stanford.edu",
    )

    /** Names that are also everyday words or people: only mid-sentence, with extra checks. */
    private val AMBIGUOUS = setOf(
        "Apple", "Amazon", "Meta", "Target", "Shell", "Subway", "Visa", "Oracle", "Delta", "Ford", "Puma", "Gemini", "Claude", "Copilot", "Threads",
        "Windows", "Zoom", "Slack", "Discord", "Telegram", "Uber", "Tesla", "Grok", "Android", "Disney", "WHO", "Olympics", "Linux", "Mistral", "Intel",
    )

    /** Marks unmarked brand scenes; returns how many it marked. */
    fun mark(scenes: List<Scene>): Int {
        var n = 0
        for ((i, s) in scenes.withIndex()) {
            if (s.type == "logo" || s.brand.isNotBlank()) continue
            val hit = find(s.text) ?: find(s.say) ?: continue
            s.brand = hit.first
            s.domain = hit.second
            n++
        }
        return n
    }

    /** The first brand named in [text], with its domain. */
    fun find(text: String): Pair<String, String>? {
        if (text.isBlank()) return null
        val sentenceStarts = HashSet<Int>().apply {
            add(0)
            Regex("[.!?:]\\s+").findAll(text).forEach { add(it.range.last + 1) }
        }
        var best: Triple<Int, String, String>? = null
        for ((name, domain) in BRANDS.entries.sortedByDescending { it.key.length }) {
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])")
            for (m in re.findAll(text)) {
                val at = m.range.first
                if (name in AMBIGUOUS) {
                    val first = at in sentenceStarts || text.substring(0, at).trimStart().let { it.isEmpty() }
                    if (first) continue
                    val before = text.substring(0, at).trimEnd()
                    if (name == "Tesla" && before.endsWith("Nikola")) continue
                    if (name == "Ford" && Regex("(Henry|Harrison|Gerald)$").containsMatchIn(before)) continue
                    if (name == "Amazon" && Regex("(?i)\\b(the)$").containsMatchIn(before) && Regex("^Amazon\\s+(river|rainforest|basin|jungle)", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(at))) continue
                    if (name == "Apple" && Regex("^Apple\\s+(pie|tree|juice|seed)", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(at))) continue
                    if (name == "WHO" && text == text.uppercase()) continue
                }
                if (best == null || at < best.first) best = Triple(at, name, domain)
                break
            }
        }
        return best?.let { it.second to it.third }
    }
}

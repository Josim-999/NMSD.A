package com.videostudio.app.ai

import android.graphics.BitmapFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A brand logo: a vector path from Simple Icons (24x24 box) in the brand colour, or an image file. */
sealed class Logo {
    abstract val title: String

    data class Vector(override val title: String, val pathData: String, val color: Int) : Logo()
    data class Raster(override val title: String, val file: File) : Logo()
}

/** A picture found for a scene, with its credit line. */
data class Found(val file: File, val credit: String)

/**
 * Finds free pictures and logos for the script, with no API keys:
 * - logos: Simple Icons (3,000+ brand logos as vectors, with brand colours), then Google's site icon;
 * - photos: Pexels when a key is set, then the Wikipedia image of the named person/place/thing,
 *   then Openverse (Creative Commons photos from Flickr, Wikimedia and others), then Wikimedia Commons.
 * Every network step can fail on its own; the finder just moves to the next source.
 */
class MediaFinder(
    private val cacheDir: File,
    private val pexelsKey: String = "",
    private val log: (String) -> Unit = {},
    private val endpoints: Endpoints = Endpoints(),
    /** Free Pixabay key (optional): HD photos and stock video. */
    private val pixabayKey: String = "",
) {
    /** Base URLs, replaceable in tests. */
    data class Endpoints(
        val simpleIcons: String = "https://cdn.jsdelivr.net/npm/simple-icons@13",
        val favicon: String = "https://www.google.com/s2/favicons",
        val wikipedia: String = "https://en.wikipedia.org/api/rest_v1/page/summary/",
        val openverse: String = "https://api.openverse.org/v1/images/",
        val commons: String = "https://commons.wikimedia.org/w/api.php",
        val nasa: String = "https://images-api.nasa.gov/search",
        val met: String = "https://collectionapi.metmuseum.org/public/collection/v1/",
        val pixabay: String = "https://pixabay.com/api/",
        val wikidata: String = "https://www.wikidata.org/w/api.php",
        val commonsFile: String = "https://commons.wikimedia.org/wiki/Special:FilePath/",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Wikimedia and Openverse ask apps to say who they are; anonymous clients get blocked. */
    private fun get(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    private fun text(url: String): String? = runCatching {
        http.newCall(get(url)).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun download(url: String, out: File, minBytes: Int = 4_000): Boolean = runCatching {
        http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) return@use false
            out.parentFile?.mkdirs()
            out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            out.length() >= minBytes && looksLikeImage(out)
        }
    }.getOrDefault(false)

    private fun looksLikeImage(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth >= 200 && o.outHeight >= 200
    }

    // ---------------- Logos ----------------

    private data class Icon(val slug: String, val title: String, val hex: String, val source: String = "")

    private var index: Map<String, Icon>? = null

    /** Simple Icons' brand list (titles, aliases, colours), cached for 30 days. */
    @Synchronized private fun icons(): Map<String, Icon> {
        index?.let { return it }
        val cache = File(cacheDir, "simple-icons.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 30L * 24 * 3600 * 1000
        val json = if (fresh) cache.readText() else text("${endpoints.simpleIcons}/_data/simple-icons.json")?.also {
            cacheDir.mkdirs()
            cache.writeText(it)
        } ?: if (cache.exists()) cache.readText() else return emptyMap<String, Icon>().also { index = it }
        val arr = runCatching {
            val any = org.json.JSONTokener(json).nextValue()
            if (any is JSONObject) any.getJSONArray("icons") else any as JSONArray
        }.getOrElse { return emptyMap() }
        val map = HashMap<String, Icon>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val icon = Icon(o.optString("slug").ifBlank { slug(title) }, title, o.optString("hex"), o.optString("source"))
            map.putIfAbsent(key(title), icon)
            map.putIfAbsent(icon.slug, icon)
            o.optJSONObject("aliases")?.optJSONArray("aka")?.let { aka ->
                for (j in 0 until aka.length()) map.putIfAbsent(key(aka.optString(j)), icon)
            }
        }
        index = map
        return map
    }

    /**
     * The brand's real logo, or null. Never a guess: a logo is used only when it is verified.
     * 1. Simple Icons (crisp vector): the name must match exactly AND, when the official website is
     *    known, the icon's own source site must be on the same domain.
     * 2. Wikidata (the logo on the brand's Wikidata entry, via Wikimedia Commons): the entry's official
     *    website must match the domain (or, without a domain, its name must match exactly).
     * 3. The site icon of the official domain, only when that domain is confirmed to be the brand's.
     * No match: no logo (the scene shows the name in clean type) instead of a wrong one.
     */
    fun logo(brand: String, domain: String, wiki: String = ""): Logo? {
        if (brand.isBlank() && domain.isBlank()) return null
        val d = cleanHost(domain)
        val reg = registrable(d)
        findIcon(brand, d)?.let { icon ->
            val svg = text("${endpoints.simpleIcons}/icons/${icon.slug}.svg")
            val path = svg?.let { Regex("""\sd="([^"]+)"""").find(it)?.groupValues?.get(1) }
            if (path != null) {
                val color = runCatching { android.graphics.Color.parseColor("#" + icon.hex) }.getOrDefault(android.graphics.Color.WHITE)
                log("Logo: ${icon.title} (Simple Icons, matched ${if (d.isNotBlank()) "name and website" else "exact name"}).")
                return Logo.Vector(icon.title, path, color)
            }
        }
        val wd = runCatching { wikidataLogo(brand, d, wiki) }.getOrNull()
        if (wd != null) return wd
        // Site icon only for a domain we know belongs to the brand.
        val confirmed = when {
            reg.isBlank() -> confirmedDomain ?: ""
            brandMatchesDomain(brand, reg) || confirmedDomain?.let { registrable(it) == reg } == true -> d
            else -> ""
        }
        confirmedDomain = null
        if (confirmed.isNotBlank()) {
            val f = File(cacheDir, "logo_${key(confirmed)}.png")
            val url = endpoints.favicon.toHttpUrl().newBuilder().addQueryParameter("domain", confirmed).addQueryParameter("sz", "256").build().toString()
            val ok = runCatching {
                http.newCall(get(url)).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opt)
                    opt.outWidth >= 96 // Google's "unknown site" globe is 16 px; tiny icons look blurry
                }
            }.getOrDefault(false)
            if (ok) { log("Logo: ${brand.ifBlank { confirmed }} (site icon of $confirmed)."); return Logo.Raster(brand.ifBlank { confirmed }, f) }
        }
        log("No verified logo for \"$brand\"${if (d.isNotBlank()) " ($d)" else ""}: showing the name instead of a guess.")
        return null
    }

    /** A domain Wikidata confirmed for the brand (when the script gave none), for the site-icon step. */
    private var confirmedDomain: String? = null

    private fun findIcon(brand: String, d: String): Icon? {
        val idx = icons()
        val reg = registrable(d)
        val label = reg.substringBefore('.')
        // Exact names only: the brand, the brand with its parent ("Gemini" + google.com -> "Google Gemini"), or its slug.
        val candidates = listOfNotNull(
            key(brand).takeIf { it.isNotEmpty() },
            if (label.isNotBlank() && !key(brand).startsWith(label)) key("$label $brand") else null,
            slug(brand).takeIf { it.isNotEmpty() },
            if (brand.isBlank() && label.isNotBlank()) label else null,
        ).distinct()
        for (k in candidates) {
            val icon = idx[k] ?: continue
            val src = registrable(cleanHost(icon.source))
            if (reg.isNotBlank()) {
                // The icon's own source site must be the brand's site (stops "Gemini" the exchange for Google Gemini).
                if (src.isNotBlank() && src == reg) return icon
                if (src.isBlank() && key(icon.title) == key(brand)) return icon
            } else if (key(icon.title) == key(brand) || k == key(brand)) {
                return icon
            }
        }
        return null
    }

    /** The logo on the brand's Wikidata entry, checked against its official website. */
    private fun wikidataLogo(brand: String, d: String, wiki: String): Logo? {
        val reg = registrable(d)
        val ids = ArrayList<String>()
        // A Wikipedia title from the script points at exactly one entry.
        if (wiki.isNotBlank()) {
            val sum = text(endpoints.wikipedia + java.net.URLEncoder.encode(wiki.trim().replace(' ', '_'), "UTF-8").replace("+", "_"))
            runCatching { JSONObject(sum ?: "").optString("wikibase_item") }.getOrNull()?.takeIf { it.startsWith("Q") }?.let { ids += it }
        }
        if (brand.isNotBlank()) {
            val url = endpoints.wikidata.toHttpUrl().newBuilder().addQueryParameter("action", "wbsearchentities")
                .addQueryParameter("search", brand.take(60)).addQueryParameter("language", "en").addQueryParameter("type", "item")
                .addQueryParameter("limit", "6").addQueryParameter("format", "json").build().toString()
            val res = runCatching { JSONObject(text(url) ?: "{}").optJSONArray("search") }.getOrNull()
            if (res != null) for (i in 0 until res.length()) res.optJSONObject(i)?.optString("id")?.let { if (it !in ids) ids += it }
        }
        if (ids.isEmpty()) return null
        val url = endpoints.wikidata.toHttpUrl().newBuilder().addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", ids.take(7).joinToString("|")).addQueryParameter("props", "claims|labels|aliases")
            .addQueryParameter("languages", "en").addQueryParameter("format", "json").build().toString()
        val entities = runCatching { JSONObject(text(url) ?: "{}").optJSONObject("entities") }.getOrNull() ?: return null
        for (id in ids) {
            val e = entities.optJSONObject(id) ?: continue
            val claims = e.optJSONObject("claims") ?: continue
            val label = e.optJSONObject("labels")?.optJSONObject("en")?.optString("value").orEmpty()
            val aliases = e.optJSONObject("aliases")?.optJSONArray("en")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("value").orEmpty() } }.orEmpty()
            val sites = claimStrings(claims, "P856").map { registrable(cleanHost(it)) }.filter { it.isNotBlank() }
            val nameMatch = (listOf(label) + aliases).any { key(it) == key(brand) }
            val fromWiki = wiki.isNotBlank() && id == ids.first()
            val ok = when {
                reg.isNotBlank() -> reg in sites || (sites.isEmpty() && nameMatch && fromWiki)
                else -> nameMatch && (fromWiki || sites.isNotEmpty())
            }
            if (!ok) continue
            if (reg.isBlank()) confirmedDomain = sites.firstOrNull()
            val file = claimStrings(claims, "P154").firstOrNull() ?: continue
            val f = File(cacheDir, "wdlogo_${key(file)}.png")
            val img = endpoints.commonsFile + java.net.URLEncoder.encode(file.replace(' ', '_'), "UTF-8").replace("+", "_") + "?width=640"
            if (download(img, f, minBytes = 600) || looksLikeLogo(f)) {
                log("Logo: ${label.ifBlank { brand }} (Wikidata, official site ${sites.firstOrNull() ?: "-"}).")
                return Logo.Raster(label.ifBlank { brand }, f)
            }
        }
        return null
    }

    /** Logos are often small or wide; accept anything at least 120 px on its long side. */
    private fun looksLikeLogo(f: File): Boolean {
        if (!f.exists()) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return maxOf(o.outWidth, o.outHeight) >= 120
    }

    private fun claimStrings(claims: JSONObject, prop: String): List<String> {
        val arr = claims.optJSONArray(prop) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")
            (v as? String)?.takeIf { it.isNotBlank() }
        }
    }

    // ---------------- Photos ----------------

    private val used: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Search answers already fetched: extra pictures for a scene reuse them (Openverse allows ~200 searches a day). */
    private val searches = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Best free picture for a scene. [wiki] is a Wikipedia article title (a named person, place,
     * company, object); [query] a visual search phrase.
     */
    fun photo(query: String, wiki: String, out: File, library: String = ""): Found? {
        // Space and science: NASA's public-domain library. Art, history, artefacts: The Met (CC0).
        val q = query.ifBlank { wiki }
        if (library == "nasa" && q.isNotBlank()) nasa(q, out)?.let { return it }
        if (library == "museum" && q.isNotBlank()) met(q, out)?.let { return it }
        if (pexelsKey.isNotBlank() && query.isNotBlank()) {
            runCatching { Stock.pexelsPhoto(pexelsKey, query, out) }.getOrNull()?.let { return Found(out, it) }
        }
        if (wiki.isNotBlank()) wikipedia(wiki, out)?.let { return it }
        if (pixabayKey.isNotBlank() && query.isNotBlank()) pixabay(query, out)?.let { return it }
        if (query.isNotBlank()) {
            openverse(query, out)?.let { return it }
            commons(query, out)?.let { return it }
        }
        // A named thing with no visual query: try its name as the search.
        if (wiki.isNotBlank()) openverse(wiki, out)?.let { return it }
        return null
    }

    /** NASA Image and Video Library: no key, NASA media is generally public domain. */
    private fun nasa(query: String, out: File): Found? {
        val url = endpoints.nasa.toHttpUrl().newBuilder().addQueryParameter("q", query.take(80))
            .addQueryParameter("media_type", "image").build().toString()
        val json = searches.getOrPut("nasa:$url") { text(url) ?: return null }
        val items = runCatching { JSONObject(json).getJSONObject("collection").getJSONArray("items") }.getOrNull() ?: return null
        for (i in 0 until minOf(items.length(), 10)) {
            val item = items.optJSONObject(i) ?: continue
            val data = item.optJSONArray("data")?.optJSONObject(0) ?: continue
            val id = data.optString("nasa_id")
            val thumb = item.optJSONArray("links")?.optJSONObject(0)?.optString("href") ?: continue
            if (id.isBlank() || thumb.isBlank() || !used.add("nasa:$id")) continue
            for (size in listOf("~large", "~medium", "~orig")) {
                if (download(thumb.replace("~thumb", size), out)) {
                    return Found(out, "\"${data.optString("title").take(80)}\", NASA (public domain) (https://images.nasa.gov/details/$id)")
                }
            }
        }
        return null
    }

    /** The Met's Open Access collection: no key, public-domain (CC0) pictures of art and history. */
    private fun met(query: String, out: File): Found? {
        val url = (endpoints.met + "search").toHttpUrl().newBuilder().addQueryParameter("hasImages", "true")
            .addQueryParameter("q", query.take(80)).build().toString()
        val json = searches.getOrPut("met:$url") { text(url) ?: return null }
        val ids = runCatching { JSONObject(json).optJSONArray("objectIDs") }.getOrNull() ?: return null
        for (i in 0 until minOf(ids.length(), 8)) {
            val id = ids.optLong(i)
            if (!used.add("met:$id")) continue
            val body = text(endpoints.met + "objects/$id") ?: continue
            val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (!o.optBoolean("isPublicDomain")) continue
            val img = o.optString("primaryImage").ifBlank { o.optString("primaryImageSmall") }
            if (img.isBlank()) continue
            if (download(img, out)) {
                return Found(out, "\"${o.optString("title").take(80)}\", The Metropolitan Museum of Art (CC0) (${o.optString("objectURL")})")
            }
        }
        return null
    }

    private fun pixabayUrl(path: String, query: String, extra: Map<String, String>): String {
        val b = (endpoints.pixabay + path).toHttpUrl().newBuilder().addQueryParameter("key", pixabayKey)
            .addQueryParameter("q", query.take(100)).addQueryParameter("safesearch", "true").addQueryParameter("per_page", "12")
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    /** Pixabay photos (free key): portrait first. Pixabay asks apps to download, not hotlink: we do. */
    private fun pixabay(query: String, out: File): Found? {
        for (orientation in listOf("vertical", "all")) {
            val url = pixabayUrl("", query, mapOf("image_type" to "photo", "orientation" to orientation))
            val json = searches.getOrPut("pixabay:$url") { text(url) ?: return null }
            val hits = runCatching { JSONObject(json).getJSONArray("hits") }.getOrNull() ?: continue
            for (i in 0 until hits.length()) {
                val h = hits.optJSONObject(i) ?: continue
                if (relevance(query, h.optString("tags")) < minRelevance(query)) continue
                if (!used.add("pixabay:${h.optLong("id")}")) continue
                val img = h.optString("largeImageURL").ifBlank { h.optString("webformatURL") }
                if (img.isNotBlank() && download(img, out)) {
                    return Found(out, "Image by ${h.optString("user")} from Pixabay (${h.optString("pageURL")})")
                }
            }
        }
        return null
    }

    /** A Pixabay stock video clip (free key), portrait when there is one. Returns the credit line. */
    fun pixabayVideo(query: String, out: File, minSeconds: Double): String? {
        if (pixabayKey.isBlank() || query.isBlank()) return null
        val url = pixabayUrl("videos/", query, emptyMap())
        val hits = runCatching { JSONObject(text(url) ?: return null).getJSONArray("hits") }.getOrNull() ?: return null
        data class Pick(val hit: JSONObject, val url: String, val portrait: Boolean)
        val picks = (0 until hits.length()).mapNotNull { i ->
            val h = hits.optJSONObject(i) ?: return@mapNotNull null
            if (h.optDouble("duration", 0.0) < minSeconds * 0.8) return@mapNotNull null
            // The clip must be about the scene (same rule as photos).
            if (relevance(query, h.optString("tags")) < minRelevance(query)) return@mapNotNull null
            val v = h.optJSONObject("videos") ?: return@mapNotNull null
            // Smallest file with at least 540 px on its short side: fast to download and decode, sharp enough behind text.
            val file = listOf("tiny", "small", "medium", "large").mapNotNull { v.optJSONObject(it) }
                .firstOrNull { it.optString("url").isNotBlank() && minOf(it.optInt("width"), it.optInt("height")) >= 540 }
                ?: return@mapNotNull null
            Pick(h, file.optString("url"), file.optInt("height") > file.optInt("width"))
        }.sortedWith(compareByDescending<Pick> { relevance(query, it.hit.optString("tags")) }.thenByDescending { it.portrait })
        for (p in picks.take(3)) {
            val ok = runCatching {
                http.newCall(get(p.url)).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                    out.length() > 50_000
                }
            }.getOrDefault(false)
            if (ok) return "Video by ${p.hit.optString("user")} from Pixabay (${p.hit.optString("pageURL")})"
        }
        return null
    }

    private fun wikipedia(title: String, out: File): Found? {
        val t = title.trim().replace(' ', '_')
        val json = text(endpoints.wikipedia + java.net.URLEncoder.encode(t, "UTF-8").replace("+", "_")) ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optString("type") == "disambiguation") return null
        val img = o.optJSONObject("originalimage") ?: o.optJSONObject("thumbnail") ?: return null
        val src = img.optString("source").ifBlank { return null }
        if (src.endsWith(".svg", true) || src.contains(".svg?", true)) return null
        if (!used.add(src.substringBefore('?'))) return null
        for (u in wikiSizes(src)) {
            if (download(u, out)) return Found(out, "Image: Wikipedia / Wikimedia Commons (${o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page") ?: t})")
        }
        return null
    }

    private fun openverse(q: String, out: File): Found? {
        for (aspect in listOf("tall", "")) {
            val b = endpoints.openverse.toHttpUrl().newBuilder()
                .addQueryParameter("q", q.take(80))
                .addQueryParameter("page_size", "12")
                // Commercial use AND modification allowed: excludes NonCommercial and NoDerivatives
                // licences, since the video crops and zooms the picture and may be monetized.
                .addQueryParameter("license_type", "commercial,modification")
                .addQueryParameter("mature", "false")
            if (aspect.isNotEmpty()) b.addQueryParameter("aspect_ratio", aspect)
            val url = b.build().toString()
            val json = searches[url] ?: text(url)?.also { searches[url] = it } ?: continue
            val results = runCatching { JSONObject(json).getJSONArray("results") }.getOrNull() ?: continue
            // Only pictures whose title or tags mention the subject, the closest match first.
            val list = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { it.optInt("width") >= 640 || it.optInt("height") >= 900 }
                .map { r ->
                    val tags = r.optJSONArray("tags")?.let { a -> (0 until a.length()).joinToString(" ") { a.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty()
                    r to relevance(q, r.optString("title") + " " + tags)
                }
                .filter { it.second >= minRelevance(q) }
                .sortedWith(compareByDescending<Pair<JSONObject, Int>> { it.second }.thenByDescending { score(it.first.optInt("width"), it.first.optInt("height")) })
                .map { it.first }
            for (r in list) {
                val id = r.optString("id")
                if (!used.add("ov:$id")) continue
                // Openverse's own copy first (always reachable), then the original.
                val urls = listOf("${endpoints.openverse}$id/thumb/?full_size=true", r.optString("url"))
                for (u in urls.filter { it.startsWith("http") }) {
                    if (download(u, out)) {
                        val by = r.optString("creator").ifBlank { "unknown" }
                        return Found(out, "\"${r.optString("title")}\" by $by, ${r.optString("license").uppercase()} ${r.optString("license_version")} via Openverse (${r.optString("foreign_landing_url")})")
                    }
                }
            }
        }
        return null
    }

    private fun commons(q: String, out: File): Found? {
        val url = endpoints.commons.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search").addQueryParameter("gsrnamespace", "6")
            .addQueryParameter("gsrsearch", "$q filetype:bitmap").addQueryParameter("gsrlimit", "8")
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|size")
            .addQueryParameter("iiurlwidth", "1280")
            .build().toString()
        val json = text(url) ?: return null
        val pages = runCatching { JSONObject(json).getJSONObject("query").getJSONArray("pages") }.getOrNull() ?: return null
        val infos = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }
            .mapNotNull { p -> p.optJSONArray("imageinfo")?.optJSONObject(0)?.let { p.optString("title") to it } }
            .filter { relevance(q, it.first) >= minRelevance(q) }
            .sortedWith(compareByDescending<Pair<String, JSONObject>> { relevance(q, it.first) }.thenByDescending { score(it.second.optInt("width"), it.second.optInt("height")) })
        for ((title, info) in infos) {
            val u = info.optString("thumburl").ifBlank { info.optString("url") }
            if (!used.add(u)) continue
            if (download(u, out)) return Found(out, "$title, Wikimedia Commons (${info.optString("descriptionurl")})")
        }
        return null
    }

    companion object {
        const val USER_AGENT = "VideoStudioApp/1.6 (https://github.com/Josim-999/NMSD.A; Android)"

        /** Prefers big, portrait-ish pictures (they fill a 9:16 frame with less cropping). */
        fun score(w: Int, h: Int): Double {
            if (w <= 0 || h <= 0) return 0.0
            val ratio = h.toDouble() / w
            val shape = 1.0 - kotlin.math.abs(ratio - 1.4).coerceAtMost(1.2) / 1.2
            val size = (minOf(w, h) / 1080.0).coerceAtMost(1.0)
            return shape * 0.6 + size * 0.4
        }

        /** Wikimedia image at 1280 px through upload.wikimedia.org (standard thumbnail size), then as given. */
        fun wikiSizes(src: String): List<String> {
            val clean = src.substringBefore('?').replace("://thumb.wikimedia.org/", "://upload.wikimedia.org/")
            val sized = if ("/thumb/" in clean) clean.replace(Regex("/\\d+px-"), "/1280px-") else {
                // Original file: build its 1280 px thumbnail path.
                val m = Regex("""(https://upload\.wikimedia\.org/wikipedia/\w+)/(\w)/(\w\w)/([^/]+)$""").find(clean)
                if (m != null) "${m.groupValues[1]}/thumb/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}/1280px-${m.groupValues[4]}" else clean
            }
            return listOf(sized, clean).distinct()
        }

        /** Simple Icons' slug rule (titleToSlug). */
        fun slug(title: String): String {
            val t = title.lowercase(Locale.ROOT)
                .replace("+", "plus").replace(".", "dot").replace("&", "and")
                .replace("đ", "d").replace("ħ", "h").replace("ı", "i").replace("ĸ", "k")
                .replace("ŀ", "l").replace("ł", "l").replace("ß", "ss").replace("ŧ", "t")
            return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")
        }

        /** Loose matching key: lowercase letters and digits only. */
        fun key(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

        private val STOP = setOf("the", "and", "for", "with", "from", "into", "your", "you", "this", "that", "how", "why", "what", "photo", "image", "picture")

        private val TALK_STOP = setOf("about", "after", "again", "also", "because", "been", "before", "being", "could", "does", "doing", "every", "even",
            "have", "here", "just", "like", "more", "most", "much", "only", "other", "over", "really", "same", "some", "than", "their", "them", "then", "there",
            "these", "they", "thing", "things", "those", "through", "very", "want", "were", "when", "where", "which", "while", "will", "would", "years", "year",
            "actually", "almost", "always", "never", "nothing", "something", "everything", "people", "percent", "million", "billion", "single", "today", "right")

        /** A short picture search for a spoken line: its 3 most concrete words, else the topic. */
        fun keywords(text: String, topic: String): String {
            val words = text.split(Regex("[^\\p{L}]+")).filter { it.length >= 4 && it.lowercase(Locale.ROOT) !in TALK_STOP && it.lowercase(Locale.ROOT) !in STOP }
            val pick = words.sortedByDescending { it.length }.take(3)
            return if (pick.size >= 2) pick.joinToString(" ").lowercase(Locale.ROOT)
            else (pick + topic.split(Regex("[^\\p{L}]+")).filter { it.length >= 4 }.take(3)).distinct().take(3).joinToString(" ").lowercase(Locale.ROOT)
        }

        /** Meaningful words of a search, singular-ish ("robots" -> "robot"). */
        fun keywords(q: String): List<String> = q.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP }.map { it.removeSuffix("s") }.distinct()

        /** How many of the search's words a picture's title/tags contain. */
        fun relevance(q: String, text: String): Int {
            val t = text.lowercase(Locale.ROOT)
            return keywords(q).count { t.contains(it) }
        }

        /** One word must match for a short search, half of them for a long one. */
        fun minRelevance(q: String): Int { val n = keywords(q).size; return if (n <= 2) minOf(1, n) else (n + 1) / 2 }

        /** "https://www.Gemini.Google.com/app" -> "gemini.google.com". */
        fun cleanHost(s: String): String = s.lowercase(Locale.ROOT).trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").substringBefore('/').substringBefore('?').substringBefore(':')

        private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu")

        /** The part of a host a company actually owns: "gemini.google.com" -> "google.com", "bbc.co.uk" -> "bbc.co.uk". */
        fun registrable(host: String): String {
            val parts = host.split('.').filter { it.isNotBlank() }
            if (parts.size < 2) return host
            val n = if (parts.size >= 3 && parts[parts.size - 2] in SECOND_LEVEL && parts.last().length == 2) 3 else 2
            return parts.takeLast(n).joinToString(".")
        }

        /** "OpenAI" and "openai.com": the brand's own name is the domain's name. */
        fun brandMatchesDomain(brand: String, registrable: String): Boolean {
            val b = key(brand)
            val l = key(registrable.substringBefore('.'))
            return b.isNotEmpty() && l.length >= 3 && (b == l || b.startsWith(l) || l.startsWith(b))
        }
    }
}

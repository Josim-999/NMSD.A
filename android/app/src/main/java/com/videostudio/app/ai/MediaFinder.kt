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

    private data class Icon(val slug: String, val title: String, val hex: String)

    private var index: Map<String, Icon>? = null

    /** Simple Icons' brand list (titles, aliases, colours), cached for 30 days. */
    private fun icons(): Map<String, Icon> {
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
            val icon = Icon(o.optString("slug").ifBlank { slug(title) }, title, o.optString("hex"))
            map.putIfAbsent(key(title), icon)
            map.putIfAbsent(icon.slug, icon)
            o.optJSONObject("aliases")?.optJSONArray("aka")?.let { aka ->
                for (j in 0 until aka.length()) map.putIfAbsent(key(aka.optString(j)), icon)
            }
        }
        index = map
        return map
    }

    /** Logo for [brand] (website [domain] helps). Null when no source has one. */
    fun logo(brand: String, domain: String): Logo? {
        if (brand.isBlank() && domain.isBlank()) return null
        val cleanDomain = domain.lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
        val idx = icons()
        val candidates = listOf(key(brand), slug(brand), key(cleanDomain.substringBefore('.')), key(brand.substringBefore(' ')))
            .filter { it.isNotEmpty() }
        for (k in candidates) {
            val icon = idx[k] ?: continue
            val svg = text("${endpoints.simpleIcons}/icons/${icon.slug}.svg") ?: continue
            val d = Regex("""\sd="([^"]+)"""").find(svg)?.groupValues?.get(1) ?: continue
            val color = runCatching { android.graphics.Color.parseColor("#" + icon.hex) }.getOrDefault(android.graphics.Color.WHITE)
            return Logo.Vector(icon.title, d, color)
        }
        // Google's site icon (up to 256 px). Unknown sites get a tiny globe, which the size check rejects.
        val site = cleanDomain.ifBlank { guessDomain(brand) }
        if (site.isNotBlank()) {
            val f = File(cacheDir, "logo_${key(site)}.png")
            val url = endpoints.favicon.toHttpUrl().newBuilder().addQueryParameter("domain", site).addQueryParameter("sz", "256").build().toString()
            val ok = runCatching {
                http.newCall(get(url)).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opt)
                    opt.outWidth >= 64
                }
            }.getOrDefault(false)
            if (ok) return Logo.Raster(brand.ifBlank { site }, f)
        }
        return null
    }

    // ---------------- Photos ----------------

    private val used = HashSet<String>()

    /** Search answers already fetched: extra pictures for a scene reuse them (Openverse allows ~200 searches a day). */
    private val searches = HashMap<String, String>()

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
            val v = h.optJSONObject("videos") ?: return@mapNotNull null
            val file = listOf("large", "medium").mapNotNull { v.optJSONObject(it) }
                .firstOrNull { it.optString("url").isNotBlank() && maxOf(it.optInt("width"), it.optInt("height")) in 960..2160 }
                ?: return@mapNotNull null
            Pick(h, file.optString("url"), file.optInt("height") > file.optInt("width"))
        }.sortedByDescending { it.portrait }
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
            val list = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { it.optInt("width") >= 640 || it.optInt("height") >= 900 }
                .sortedByDescending { score(it.optInt("width"), it.optInt("height")) }
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
            .sortedByDescending { score(it.second.optInt("width"), it.second.optInt("height")) }
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

        private fun guessDomain(brand: String): String = key(brand).takeIf { it.length >= 2 }?.let { "$it.com" } ?: ""
    }
}

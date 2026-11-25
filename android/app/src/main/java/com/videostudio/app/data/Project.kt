package com.videostudio.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Status {
    const val NEW = "new"
    const val SCRIPTED = "scripted"
    const val RENDERED = "rendered"
    const val POSTED = "posted"
}

data class Source(val claim: String, val url: String)

data class Upload(val platform: String, val url: String, val visibility: String, val at: Long)

data class Scene(
    var say: String,
    var type: String = "statement",
    var text: String = "",
    var emoji: String = "",
    var value: String = "",
    var label: String = "",
    var items: List<String> = emptyList(),
    var left: String = "",
    var leftText: String = "",
    var right: String = "",
    var rightText: String = "",
    var highlight: String = "",
    var query: String = "",
    /** Company, product or app named in this scene; its logo is shown. */
    var brand: String = "",
    /** The brand's website, e.g. "nvidia.com" (finds the logo when the name alone does not). */
    var domain: String = "",
    /** English Wikipedia article title of the person, place or thing shown, for a real photo. */
    var wiki: String = "",
    /** The one spoken word that gets a zoom punch, colour and a hit sound. */
    var emphasis: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("say", say).put("type", type).put("text", text).put("emoji", emoji)
        .put("value", value).put("label", label).put("items", JSONArray(items))
        .put("left", left).put("leftText", leftText).put("right", right).put("rightText", rightText)
        .put("highlight", highlight).put("query", query)
        .put("brand", brand).put("domain", domain).put("wiki", wiki).put("emphasis", emphasis)

    companion object {
        /** Reads both the stored shape and the model's {"say", "visual": {...}} shape. */
        fun fromJson(o: JSONObject): Scene {
            val v = o.optJSONObject("visual") ?: o
            return Scene(
                say = o.optString("say"),
                type = v.optString("type", "statement").ifBlank { "statement" },
                text = v.optString("text"),
                emoji = v.optString("emoji"),
                value = v.optString("value"),
                label = v.optString("label"),
                items = strings(v.optJSONArray("items")),
                left = v.optString("left"),
                leftText = v.optString("leftText"),
                right = v.optString("right"),
                rightText = v.optString("rightText"),
                highlight = v.optString("highlight"),
                query = v.optString("query"),
                brand = v.optString("brand").ifBlank { o.optString("brand") },
                domain = v.optString("domain").ifBlank { o.optString("domain") },
                wiki = v.optString("wiki").ifBlank { o.optString("wiki") },
                emphasis = o.optString("emphasis").ifBlank { v.optString("emphasis") },
            )
        }
    }
}

data class Project(
    val id: String,
    var topic: String,
    var audience: String = "",
    var lengthSec: Int = 45,
    var language: String = "English",
    var voice: String = "en-US-AndrewNeural",
    var palette: String = "electric",
    var research: Boolean = true,
    var status: String = Status.NEW,
    var title: String = "",
    var researchNotes: String = "",
    var scenes: MutableList<Scene> = ArrayList(),
    var sources: List<Source> = emptyList(),
    var ytTitle: String = "",
    var description: String = "",
    var hashtags: List<String> = emptyList(),
    var titleOptions: List<String> = emptyList(),
    var commentQuestion: String = "",
    var uploads: MutableList<Upload> = ArrayList(),
    var error: String = "",
    var voiceUsed: String = "",
    var durationSec: Double = 0.0,
    /** The creator's own view, worked into the script (original insight, not a template). */
    var take: String = "",
    /** Mood of the music and pacing, chosen by the script writer: energetic, calm, dramatic, playful. */
    var mood: String = "",
    /** Visual style of this video (captions, transitions, background); -1 = not chosen yet. */
    var style: Int = -1,
    val created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
) {
    fun dir(root: File) = File(root, id)
    fun video(root: File) = File(dir(root), "video.mp4")
    fun thumb(root: File) = File(dir(root), "thumb.jpg")
    fun displayTitle(): String = title.ifBlank { topic }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", Migrations.CURRENT)
        .put("id", id).put("topic", topic).put("audience", audience).put("lengthSec", lengthSec)
        .put("language", language).put("voice", voice).put("palette", palette).put("research", research)
        .put("status", status).put("title", title).put("researchNotes", researchNotes)
        .put("scenes", JSONArray(scenes.map { it.toJson() }))
        .put("sources", JSONArray(sources.map { JSONObject().put("claim", it.claim).put("url", it.url) }))
        .put("ytTitle", ytTitle).put("description", description)
        .put("hashtags", JSONArray(hashtags)).put("titleOptions", JSONArray(titleOptions))
        .put("commentQuestion", commentQuestion)
        .put("uploads", JSONArray(uploads.map {
            JSONObject().put("platform", it.platform).put("url", it.url).put("visibility", it.visibility).put("at", it.at)
        }))
        .put("error", error).put("voiceUsed", voiceUsed).put("durationSec", durationSec)
        .put("take", take).put("mood", mood).put("style", style)
        .put("created", created).put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject) = Project(
            id = o.getString("id"),
            topic = o.optString("topic"),
            audience = o.optString("audience"),
            lengthSec = o.optInt("lengthSec", 45),
            language = o.optString("language", "English"),
            voice = o.optString("voice", "en-US-AndrewNeural"),
            palette = o.optString("palette", "electric"),
            research = o.optBoolean("research", true),
            status = o.optString("status", Status.NEW),
            title = o.optString("title"),
            researchNotes = o.optString("researchNotes"),
            scenes = objects(o.optJSONArray("scenes")).map { Scene.fromJson(it) }.toMutableList(),
            sources = objects(o.optJSONArray("sources")).map { Source(it.optString("claim"), it.optString("url")) },
            ytTitle = o.optString("ytTitle"),
            description = o.optString("description"),
            hashtags = strings(o.optJSONArray("hashtags")),
            titleOptions = strings(o.optJSONArray("titleOptions")),
            commentQuestion = o.optString("commentQuestion"),
            uploads = objects(o.optJSONArray("uploads")).map {
                Upload(it.optString("platform"), it.optString("url"), it.optString("visibility"), it.optLong("at"))
            }.toMutableList(),
            error = o.optString("error"),
            voiceUsed = o.optString("voiceUsed"),
            durationSec = o.optDouble("durationSec", 0.0),
            take = o.optString("take"),
            mood = o.optString("mood"),
            style = o.optInt("style", -1),
            created = o.optLong("created", System.currentTimeMillis()),
            updated = o.optLong("updated", System.currentTimeMillis()),
        )
    }
}

fun strings(a: JSONArray?): List<String> {
    if (a == null) return emptyList()
    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
}

fun objects(a: JSONArray?): List<JSONObject> {
    if (a == null) return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

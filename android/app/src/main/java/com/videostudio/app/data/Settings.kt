package com.videostudio.app.data

import android.content.Context
import android.content.SharedPreferences

/** Everything the user sets up once. Stored only on this phone, in the app's private storage. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = p.getString(key, def) ?: def
    private fun put(key: String, value: String) = p.edit().putString(key, value).apply()

    var geminiKey: String
        get() = str("gemini_key")
        set(v) = put("gemini_key", v.trim())

    var scriptModel: String
        get() = str("script_model", "gemini-3.1-flash-lite")
        set(v) = put("script_model", v.trim())

    var researchModel: String
        get() = str("research_model", "gemini-3.8-flash")
        set(v) = put("research_model", v.trim())

    var pexelsKey: String
        get() = str("pexels_key")
        set(v) = put("pexels_key", v.trim())

    // ---- Direct YouTube upload (advanced: your own Google Cloud OAuth client) ----

    var youtubeClientId: String
        get() = str("yt_client_id")
        set(v) = put("yt_client_id", v.trim())

    var youtubeClientSecret: String
        get() = str("yt_client_secret")
        set(v) = put("yt_client_secret", v.trim())

    var youtubeRefreshToken: String
        get() = str("yt_refresh")
        set(v) = put("yt_refresh", v)

    var youtubeAccessToken: String
        get() = str("yt_access")
        set(v) = put("yt_access", v)

    var youtubeAccessExpires: Long
        get() = p.getLong("yt_access_exp", 0L)
        set(v) = p.edit().putLong("yt_access_exp", v).apply()

    var youtubeChannelName: String
        get() = str("yt_channel")
        set(v) = put("yt_channel", v)

    fun signOutYouTube() {
        p.edit().remove("yt_refresh").remove("yt_access").remove("yt_access_exp").remove("yt_channel").apply()
    }

    // ---- One-tap posting to every platform (Upload-Post connected accounts) ----

    /** API key from the Upload-Post dashboard. The only thing the user ever pastes. */
    var socialApiKey: String
        get() = str("social_api_key")
        set(v) = put("social_api_key", v.trim())

    /** Name of the Upload-Post profile the social accounts are linked to. Created automatically. */
    var socialProfile: String
        get() = str("social_profile", "videostudio")
        set(v) = put("social_profile", v.trim().ifBlank { "videostudio" })

    /** Platforms linked to the profile, as last read from Upload-Post. */
    var socialConnected: List<String>
        get() = str("social_connected").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = put("social_connected", v.joinToString(","))

    /** Display names of the linked accounts, "platform=name" pairs. */
    var socialAccountNames: Map<String, String>
        get() = str("social_names").split('\n').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        set(v) = put("social_names", v.entries.joinToString("\n") { "${it.key}=${it.value.replace('\n', ' ')}" })

    /** Platforms "Post everywhere" sends to by default. Empty means every connected one. */
    var socialTargets: List<String>
        get() = str("social_targets").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = put("social_targets", v.joinToString(","))

    fun signOutSocial() {
        p.edit().remove("social_connected").remove("social_names").remove("social_targets").apply()
    }

    // ---- Voice, look and publishing defaults ----

    var voice: String
        get() = str("voice", "en-US-AndrewNeural")
        set(v) = put("voice", v)

    var voiceRate: String
        get() = str("voice_rate", "+8%")
        set(v) = put("voice_rate", v)

    var language: String
        get() = str("language", "English")
        set(v) = put("language", v)

    var seriesName: String
        get() = str("series_name")
        set(v) = put("series_name", v)

    var seriesPromise: String
        get() = str("series_promise", "One surprising idea, explained in under a minute.")
        set(v) = put("series_promise", v)

    var palette: String
        get() = str("palette", "electric")
        set(v) = put("palette", v)

    var quality: String
        get() = str("quality", "1080")
        set(v) = put("quality", v)

    var defaultHashtags: String
        get() = str("hashtags", "ai shorts learnai tech")
        set(v) = put("hashtags", v)

    var aiLabel: Boolean
        get() = p.getBoolean("ai_label", true)
        set(v) = p.edit().putBoolean("ai_label", v).apply()

    var aiLabelText: String
        get() = str("ai_label_text", "Made with AI tools (script and voice). Facts are sourced below.")
        set(v) = put("ai_label_text", v)

    var defaultVisibility: String
        get() = str("visibility", "private")
        set(v) = put("visibility", v)
}

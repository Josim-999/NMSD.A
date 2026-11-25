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

    /** Apps "Post to all" opens, in order. Empty means the main four that are installed. */
    var postTargets: List<String>
        get() = str("post_targets").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = put("post_targets", v.joinToString(","))

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

    /** 60 frames a second: smoother motion graphics, about twice the render time. */
    var smooth60: Boolean
        get() = p.getBoolean("fps60", false)
        set(v) = p.edit().putBoolean("fps60", v).apply()

    /** Soft background music made on the phone, ducked under the voice. */
    var music: Boolean
        get() = p.getBoolean("music", true)
        set(v) = p.edit().putBoolean("music", v).apply()

    /** Whoosh on scene changes. */
    var soundEffects: Boolean
        get() = p.getBoolean("sfx", true)
        set(v) = p.edit().putBoolean("sfx", v).apply()

    /** Style of the previous video, so the next one looks different. */
    var lastStyle: Int
        get() = p.getInt("last_style", -1)
        set(v) = p.edit().putInt("last_style", v).apply()

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

    /** Try every free model on the key (Gemini Flash, then Gemma) when the usual ones run out. */
    var autoSwitchModels: Boolean
        get() = p.getBoolean("auto_switch_models", true)
        set(v) = p.edit().putBoolean("auto_switch_models", v).apply()

    /** Free Pixabay key (optional): HD photos and vertical stock video. */
    var pixabayKey: String
        get() = str("pixabay_key", "")
        set(v) = put("pixabay_key", v.trim())

    /** Time zone of the audience for scheduled posts (IANA id). */
    var scheduleZone: String
        get() = str("schedule_zone", "America/New_York")
        set(v) = put("schedule_zone", v)

    /** Last chosen posting time, minutes after midnight in [scheduleZone]. */
    var scheduleMinutes: Int
        get() = p.getInt("schedule_minutes", 19 * 60)
        set(v) = p.edit().putInt("schedule_minutes", v).apply()

    // Backup AI for scripts when Gemini's free quota is used up (all optional, all free).
    var groqKey: String
        get() = str("groq_key", "")
        set(v) = put("groq_key", v.trim())
    var openRouterKey: String
        get() = str("openrouter_key", "")
        set(v) = put("openrouter_key", v.trim())
    var mistralKey: String
        get() = str("mistral_key", "")
        set(v) = put("mistral_key", v.trim())
    var nvidiaKey: String
        get() = str("nvidia_key", "")
        set(v) = put("nvidia_key", v.trim())
    /** A keyless public AI as the very last resort. */
    var keylessBackup: Boolean
        get() = p.getBoolean("keyless_backup", true)
        set(v) = p.edit().putBoolean("keyless_backup", v).apply()

    var zaiKey: String
        get() = str("zai_key", "")
        set(v) = put("zai_key", v.trim())
    var alibabaKey: String
        get() = str("alibaba_key", "")
        set(v) = put("alibaba_key", v.trim())
    var siliconFlowKey: String
        get() = str("siliconflow_key", "")
        set(v) = put("siliconflow_key", v.trim())
    var cloudflareAccount: String
        get() = str("cloudflare_account", "")
        set(v) = put("cloudflare_account", v.trim())
    var cloudflareToken: String
        get() = str("cloudflare_token", "")
        set(v) = put("cloudflare_token", v.trim())
    var pollinationsKey: String
        get() = str("pollinations_key", "")
        set(v) = put("pollinations_key", v.trim())

    fun aiKeys() = com.videostudio.app.ai.BackupAi.Keys(
        zai = zaiKey, nvidia = nvidiaKey, groq = groqKey, alibaba = alibabaKey, mistral = mistralKey,
        openRouter = openRouterKey, siliconFlow = siliconFlowKey, cloudflareAccount = cloudflareAccount,
        cloudflareToken = cloudflareToken, pollinations = pollinationsKey, keyless = keylessBackup,
    )

    /** "mix" (all looks), "glass" (liquid glass only) or "classic" (dark looks only). */
    var lookMode: String
        get() = str("look_mode", "mix")
        set(v) = put("look_mode", v)
}

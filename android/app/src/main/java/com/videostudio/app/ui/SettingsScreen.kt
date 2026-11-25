package com.videostudio.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videostudio.app.App
import com.videostudio.app.upload.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: App, onBack: () -> Unit) {
    val s = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Posting") {
                Hint(
                    "Post to all uses the apps on this phone (TikTok, Instagram, YouTube, Facebook, X, Threads, LinkedIn, WhatsApp). " +
                        "You are already signed in there, so there is nothing to connect and no keys. Pick the apps on each video's Video & upload tab."
                )
            }

            Section("Gemini (research and script)") {
                Hint("Free key: aistudio.google.com/apikey → Create API key. Paste it here.")
                TextButton(onClick = { open("https://aistudio.google.com/apikey") }) { Text("Open Google AI Studio") }
                SecretField("Gemini API key", s.geminiKey) { s.geminiKey = it }
                Spacer(Modifier.height(6.dp))
                PlainField("Script model", s.scriptModel) { s.scriptModel = it }
                Spacer(Modifier.height(6.dp))
                PlainField("Research model (uses Google Search)", s.researchModel) { s.researchModel = it }
                Spacer(Modifier.height(6.dp))
                var auto by remember { mutableStateOf(s.autoSwitchModels) }
                ToggleRow(
                    "Auto-switch AI models",
                    "When a model's free limit runs out, the app moves to the next free model on your key: newer Gemini Flash models, then Gemma (much bigger free limit). If every one is used up, the script is written again by itself after the midnight-Pacific reset. Free tier only: no extra cost.",
                    auto,
                ) { auto = it; s.autoSwitchModels = it }
            }

            Section("Backup AI for scripts (free)") {
                Hint("Scripts never stop: the app tries every free AI below and switches instantly when one runs out. Each key is free (sign in, create a key, paste it). Add as many as you can; Z.ai and NVIDIA have the most free room.")
                Spacer(Modifier.height(6.dp))
                val statuses = remember { com.videostudio.app.ai.BackupAi.all(s.aiKeys()).associate { it.id to com.videostudio.app.ai.BackupAi.status(it) } }
                @Composable
                fun KeyRow(id: String, title: String, page: String, value: String, onSet: (String) -> Unit) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        val st = statuses[id] ?: ""
                        Text(st, fontSize = 12.sp, color = if (st == "ready") Good else Muted)
                    }
                    TextButton(onClick = { open(page) }) { Text("Get a free key") }
                    SecretField("$title key", value, onSet)
                }
                KeyRow("zai", "Z.ai GLM — free Chinese models, the most room", "https://z.ai/manage-apikey/apikey-list", s.zaiKey) { s.zaiKey = it }
                KeyRow("nvidia", "NVIDIA — big free models (phone check)", "https://build.nvidia.com", s.nvidiaKey) { s.nvidiaKey = it }
                KeyRow("groq", "Groq — very fast, ~40 a day per model", "https://console.groq.com/keys", s.groqKey) { s.groqKey = it }
                KeyRow("alibaba", "Alibaba Qwen — 1M free tokens per model (Singapore region)", "https://modelstudio.console.alibabacloud.com/", s.alibabaKey) { s.alibabaKey = it }
                Hint("Alibaba: choose the Singapore region and turn on \"Free quota only\" so nothing is ever charged.")
                KeyRow("mistral", "Mistral — free plan", "https://console.mistral.ai/api-keys", s.mistralKey) { s.mistralKey = it }
                KeyRow("openrouter", "OpenRouter — free models, ~50 a day", "https://openrouter.ai/keys", s.openRouterKey) { s.openRouterKey = it }
                KeyRow("siliconflow", "SiliconFlow — free small Qwen/GLM models", "https://cloud.siliconflow.com/account/ak", s.siliconFlowKey) { s.siliconFlowKey = it }
                Spacer(Modifier.height(8.dp))
                Text("Cloudflare Workers AI — ~20-40 a day", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { open("https://dash.cloudflare.com/profile/api-tokens") }) { Text("Get a free token (Workers AI)") }
                PlainField("Cloudflare account ID", s.cloudflareAccount) { s.cloudflareAccount = it }
                SecretField("Cloudflare API token", s.cloudflareToken) { s.cloudflareToken = it }
                KeyRow("pollinations", "Pollinations — optional key", "https://enter.pollinations.ai", s.pollinationsKey) { s.pollinationsKey = it }
                Spacer(Modifier.height(8.dp))
                var keyless by remember { mutableStateOf(s.keylessBackup) }
                ToggleRow(
                    "Try Pollinations without a key",
                    "A last resort that may not answer (Pollinations now prefers a key). The script topic is sent to it.",
                    keyless,
                ) { keyless = it; s.keylessBackup = it }
                Hint("Order tried: Z.ai → NVIDIA → Gemini (all free models) → Groq → Alibaba → Mistral → OpenRouter → SiliconFlow → Cloudflare → Pollinations. Used-up services rest by themselves and come back. No billing is ever turned on.")
            }

            Section("Stock photos (optional)") {
                Hint("Optional. A Pexels key (if you already have one) lets photo scenes play real stock video clips.")
                Spacer(Modifier.height(6.dp))
                SecretField("Pexels API key", s.pexelsKey) { s.pexelsKey = it }
                Spacer(Modifier.height(10.dp))
                Hint("Pixabay (free key at pixabay.com/api/docs, sign in, the key is shown on that page): millions of HD photos and vertical stock videos. Pexels has paused new keys, so Pixabay is the one to get.")
                TextButton(onClick = { open("https://pixabay.com/api/docs/") }) { Text("Open Pixabay API page") }
                SecretField("Pixabay API key", s.pixabayKey) { s.pixabayKey = it }
                Spacer(Modifier.height(6.dp))
                Hint("Without any key, pictures still come from Wikipedia, Wikimedia Commons, NASA, The Met museum and Openverse.")
            }

            Section("Channel format") {
                PlainField("Series name (badge on the first scene)", s.seriesName) { s.seriesName = it }
                Spacer(Modifier.height(6.dp))
                PlainField("Series promise", s.seriesPromise) { s.seriesPromise = it }
                Spacer(Modifier.height(6.dp))
                PlainField("Hashtags you always like (no #)", s.defaultHashtags) { s.defaultHashtags = it }
                Spacer(Modifier.height(10.dp))
                var ai by remember { mutableStateOf(s.aiLabel) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI label")
                        Hint("Adds the line below to descriptions and sets the AI-made flag on YouTube, TikTok and Facebook.")
                    }
                    Switch(ai, { ai = it; s.aiLabel = it })
                }
                Spacer(Modifier.height(6.dp))
                PlainField("AI label text", s.aiLabelText) { s.aiLabelText = it }
            }

            Section("Voice and video") {
                Text("Voice speed")
                var rate by remember { mutableStateOf(s.voiceRate) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in listOf("-5%", "+0%", "+8%", "+15%", "+25%")) {
                        FilterChip(selected = rate == r, onClick = { rate = r; s.voiceRate = r }, label = { Text(r) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Video quality")
                var q by remember { mutableStateOf(s.quality) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = q == "1080", onClick = { q = "1080"; s.quality = "1080" }, label = { Text("1080p (best)") })
                    FilterChip(selected = q == "720", onClick = { q = "720"; s.quality = "720" }, label = { Text("720p (faster)") })
                }
                Spacer(Modifier.height(8.dp))
                var smooth by remember { mutableStateOf(s.smooth60) }
                ToggleRow("Smooth motion (60 fps)", "Silkier animations and transitions. Rendering takes about twice as long.", smooth) {
                    smooth = it; s.smooth60 = it
                }
                var music by remember { mutableStateOf(s.music) }
                ToggleRow("Background music", "A soft music bed made on the phone (no copyright), lowered automatically while the voice speaks.", music) {
                    music = it; s.music = it
                }
                var sfx by remember { mutableStateOf(s.soundEffects) }
                ToggleRow("Sound effects", "A light whoosh on every scene change.", sfx) { sfx = it; s.soundEffects = it }
                Spacer(Modifier.height(10.dp))
                Text("Video look")
                var lookMode by remember { mutableStateOf(s.lookMode) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in listOf("mix" to "Mix of all", "glass" to "Liquid glass", "classic" to "Classic dark")) {
                        FilterChip(selected = lookMode == id, onClick = { lookMode = id; s.lookMode = id }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                Hint("Liquid glass: light frosted-UI cards, dark/light theme switches, blur-in captions, cursor clicks and pop-up notifications. Every video still differs from the last.")
            }

            YouTubeSettings(app)

            Section("Get monetized") {
                Hint("Every video is checked against YouTube's rules before you post (see \"YouTube check\" on the Video tab). What YouTube asks for:")
                Spacer(Modifier.height(6.dp))
                for (step in com.videostudio.app.compliance.YouTubeCheck.YPP_STEPS) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("•", color = Accent, modifier = Modifier.width(16.dp))
                        Text(step, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }

            Section("About") {
                Text("Video Studio ${com.videostudio.app.BuildConfig.VERSION_NAME} (build ${com.videostudio.app.BuildConfig.VERSION_CODE})")
                Spacer(Modifier.height(6.dp))
                Hint(
                    "Keys are stored only on this phone, in the app's private storage. Videos are made on the phone: only the script requests (Gemini), " +
                        "the voice (Microsoft) and the apps you post with use the internet."
                )
                Spacer(Modifier.height(6.dp))
                Hint("Free resources used: Google Noto Animated Emoji (CC BY 4.0), fonts Anton, Bebas Neue, Archivo Black, Poppins and Inter (SIL Open Font License), Lottie (Apache 2.0), Google ML Kit subject segmentation, pictures from Wikipedia, Wikimedia Commons, NASA, The Met and Openverse.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun YouTubeSettings(app: App) {
    val s = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val yt = remember { YouTube(s) }
    var signedIn by remember { mutableStateOf(yt.signedIn) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Section("Advanced: YouTube direct upload (optional)") {
        Hint(
            "Not needed: Post to all already opens the YouTube app. Only for developers with their own Google Cloud project: Google Cloud Console → " +
                "APIs & Services → enable \"YouTube Data API v3\", then Google Auth Platform → Clients → Create client, type \"Desktop app\". " +
                "Paste its client ID and secret."
        )
        Spacer(Modifier.height(6.dp))
        PlainField("OAuth client ID (…apps.googleusercontent.com)", s.youtubeClientId) { s.youtubeClientId = it }
        Spacer(Modifier.height(6.dp))
        SecretField("OAuth client secret (GOCSPX-…)", s.youtubeClientSecret) { s.youtubeClientSecret = it }
        Spacer(Modifier.height(8.dp))
        Hint(
            "Common problems, already handled with clear messages: \"Access blocked\" → add your Gmail under Google Auth Platform → Audience → Test users. " +
                "Sign-in ends after 7 days → press \"Publish app\" on the same page. Videos stuck as private → Google locks uploads from " +
                "unverified projects to private until the project passes YouTube's audit."
        )
        Spacer(Modifier.height(8.dp))
        if (signedIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("Signed in", Good)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { s.signOutYouTube(); signedIn = false }) { Text("Sign out") }
            }
        } else {
            OutlinedButton(
                enabled = !signingIn,
                onClick = {
                    error = yt.configProblem()
                    if (error != null) return@OutlinedButton
                    signingIn = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { yt.signIn(ctx as Activity) } }
                        signingIn = false
                        signedIn = yt.signedIn
                        r.onSuccess { Toast.makeText(ctx, "Signed in to YouTube", Toast.LENGTH_SHORT).show() }
                            .onFailure { error = it.message ?: "Sign-in failed" }
                    }
                },
            ) { Text(if (signingIn) "Waiting for Google…" else "Sign in with Google") }
            if (signingIn) {
                Hint("Pick your account, press Continue on Google's page, then come back here. Stuck? Cancel and try again.")
                TextButton(onClick = { yt.cancelSignIn() }) { Text("Cancel sign-in", color = Danger) }
            }
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Hint(it, Danger)
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Hint(hint)
        }
        Switch(value, onChange)
    }
}

/** A text field that saves on every change. */
@Composable
private fun PlainField(label: String, initial: String, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(v, { v = it; onSave(it) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun SecretField(label: String, initial: String, onSave: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        v, { v = it; onSave(it) }, Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
    )
}

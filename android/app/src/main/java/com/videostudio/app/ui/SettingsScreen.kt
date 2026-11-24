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
                Hint("If a model name stops working, the app falls back to gemini-2.5-flash.")
            }

            Section("Stock photos (optional)") {
                Hint("Free key from pexels.com/api. With it, photo scenes play real stock video clips full-screen (or a photo if no clip fits). Without it, they show a big emoji.")
                Spacer(Modifier.height(6.dp))
                SecretField("Pexels API key", s.pexelsKey) { s.pexelsKey = it }
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
            }

            YouTubeSettings(app)

            Section("About") {
                Text("Video Studio ${com.videostudio.app.BuildConfig.VERSION_NAME} (build ${com.videostudio.app.BuildConfig.VERSION_CODE})")
                Spacer(Modifier.height(6.dp))
                Hint(
                    "Keys are stored only on this phone, in the app's private storage. Videos are made on the phone: only the script requests (Gemini), " +
                        "the voice (Microsoft) and the apps you post with use the internet."
                )
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

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
import com.videostudio.app.MainActivity
import com.videostudio.app.upload.SocialAccount
import com.videostudio.app.upload.SocialPost
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
            ConnectedAccounts(app)

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

            YouTubeSettings(app)

            Section("Stock photos (optional)") {
                Hint("Free key from pexels.com/api. Without it, photo scenes show a big emoji instead.")
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
            }

            Section("About") {
                Hint(
                    "Keys are stored only on this phone, in the app's private storage. Videos are made on the phone: only the script requests (Gemini), " +
                        "the voice (Microsoft) and uploads use the internet. Posting to several networks at once goes through Upload-Post, which holds " +
                        "the approved app registrations with each network."
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The one-time "Zapier-style" setup: paste one key, tap Connect, log in to each network. */
@Composable
private fun ConnectedAccounts(app: App) {
    val s = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val social = remember { SocialPost(s) }
    var key by remember { mutableStateOf(s.socialApiKey) }
    var accounts by remember { mutableStateOf(s.socialConnected.map { SocialAccount(it, s.socialAccountNames[it] ?: SocialPost.name(it)) }) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val returned by MainActivity.returns.collectAsState()

    fun refresh() {
        if (!social.configured) return
        working = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { social.refreshAccounts() } }
            working = false
            r.onSuccess { accounts = it; message = null }.onFailure { message = it.message }
        }
    }

    // Back from the connect page (videostudio://connected) or first open: show what is linked now.
    LaunchedEffect(returned) { refresh() }

    Section("Connected accounts — post everywhere with one tap") {
        Hint(
            "Connect TikTok, Instagram, YouTube, Facebook, X, LinkedIn, Threads and Bluesky once. After that, every video goes to all of them " +
                "with a single tap. No Google Cloud, no API setup, no developer accounts."
        )
        Spacer(Modifier.height(10.dp))
        if (!social.configured) {
            Text("1. Create a free Upload-Post account", fontWeight = FontWeight.Bold)
            Hint("It is the service that holds the approved connections to each network (free plan: 10 uploads a month).")
            TextButton(onClick = { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(SocialPost.SIGNUP_URL)) }) {
                Text("Open upload-post.com")
            }
            Text("2. Copy your API key there (API Keys → Create) and paste it here", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
        }
        SecretField("Upload-Post API key", key) {
            key = it
            s.socialApiKey = it
        }
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = key.isNotBlank() && !working,
            onClick = {
                working = true
                message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { social.connectLink() } }
                    working = false
                    r.onSuccess { url -> CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url)) }
                        .onFailure { message = it.message }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (accounts.isEmpty()) "Connect accounts" else "Connect more accounts") }

        if (working) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Hint("Talking to Upload-Post…")
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Hint(it, Danger)
        }
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Connected", fontWeight = FontWeight.Bold)
            for (a in accounts) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Pill("✓", Good)
                    Spacer(Modifier.width(8.dp))
                    Text(SocialPost.name(a.platform), Modifier.weight(1f))
                    if (a.name != SocialPost.name(a.platform)) Hint(a.name)
                }
            }
        } else if (social.configured && !working) {
            Spacer(Modifier.height(8.dp))
            Hint("No accounts connected yet. Tap Connect accounts, log in to each network, then come back.")
        }
        if (social.configured) {
            Row {
                TextButton(onClick = { refresh() }) { Text("Refresh") }
                TextButton(onClick = {
                    s.socialApiKey = ""
                    s.signOutSocial()
                    key = ""
                    accounts = emptyList()
                }) { Text("Remove key", color = Danger) }
            }
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

    Section("YouTube direct upload (advanced, optional)") {
        Hint(
            "Not needed if you use Connected accounts above. This route uses your own Google Cloud project: Google Cloud Console → " +
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
                "unverified projects to private until the project passes YouTube's audit; Connected accounts above avoids this."
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

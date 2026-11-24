package com.videostudio.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videostudio.app.App
import com.videostudio.app.MainActivity
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.upload.Share
import com.videostudio.app.upload.SocialPost
import com.videostudio.app.upload.YouTube
import com.videostudio.app.work.JobState
import com.videostudio.app.work.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TYPES = listOf("hook", "statement", "stat", "list", "steps", "compare", "emoji", "question", "photo", "outro")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(app: App, id: String, onBack: () -> Unit, onSettings: () -> Unit) {
    val projects by app.store.projects.collectAsState()
    val jobs by app.jobs.state.collectAsState()
    val p = projects.firstOrNull { it.id == id }
    val job = jobs[id]
    var tab by rememberSaveable { mutableIntStateOf(if (p != null && p.video(app.store.root).exists()) 1 else 0) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(p?.displayTitle() ?: "", maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (p != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
    ) { pad ->
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Hint("This video was deleted.") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Accent) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Script") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Video & upload") })
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (job != null) JobCard(app, job)
                if (job?.running != true && p.error.isNotBlank()) {
                    Section("Something went wrong") {
                        SelectionContainer { Text(p.error, color = Danger) }
                        if (p.error.contains("Settings", true)) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onSettings) { Text("Open Settings") }
                        }
                    }
                }
                if (tab == 0) ScriptTab(app, p, job) { tab = 1 } else VideoTab(app, p, job, onSettings)
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmDelete && p != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this video?") },
            text = { Text("The script, voice and video file are removed from the app. Videos already posted stay online.") },
            confirmButton = {
                TextButton(onClick = {
                    app.jobs.cancel(id)
                    app.store.delete(id)
                    confirmDelete = false
                    onBack()
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JobCard(app: App, job: JobState) {
    Section(if (job.running) job.step else if (job.error != null) "Failed" else job.step) {
        if (job.running) {
            val prog = job.progress
            if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth(), color = Accent)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
            Spacer(Modifier.height(8.dp))
        }
        for (line in job.log.takeLast(8)) {
            Text(
                line, fontSize = 13.sp,
                color = when {
                    line.startsWith("Error") || line.startsWith("✕") -> Danger
                    line.startsWith("✓") -> Good
                    else -> Muted
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (job.running) TextButton(onClick = { app.jobs.cancel(job.projectId) }) { Text("Stop") }
            else TextButton(onClick = { app.jobs.clear(job.projectId) }) { Text("Hide") }
        }
    }
}

@Composable
private fun ScriptTab(app: App, p: Project, job: JobState?, toVideo: () -> Unit) {
    val busy = job?.running == true
    // Local, editable copy of the scenes; saved with "Save changes".
    val scenes = remember(p.id, p.updated) { mutableStateListOf<Scene>().apply { addAll(p.scenes.map { it.copy() }) } }
    var dirty by remember(p.id, p.updated) { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }

    if (p.scenes.isEmpty()) {
        Section("No script yet") {
            Hint(if (busy) "The script is being written…" else "Tap below to research and write the script.")
            if (!busy) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { app.jobs.start(p.id, "script") { it.writeScript(p) } }) { Text("Write the script") }
            }
        }
        return
    }

    val words = p.scenes.sumOf { it.say.split(Regex("\\s+")).size }
    Hint("${p.scenes.size} scenes · $words words · about ${(words / 2.9).toInt()} s")

    Button(
        onClick = {
            if (dirty) save(app, p, scenes)
            app.jobs.start(p.id, "render") { it.render(app.store.get(p.id) ?: p) }
            toVideo()
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(if (p.video(app.store.root).exists()) "Make the video again" else "Make the video", fontWeight = FontWeight.Bold) }
    if (p.video(app.store.root).exists()) {
        TextButton(onClick = toVideo) { Text("Watch the current video →") }
    }

    scenes.forEachIndexed { i, s ->
        Section("Scene ${i + 1}") {
            OutlinedTextField(
                s.say, { v -> scenes[i] = s.copy(say = v); dirty = true },
                Modifier.fillMaxWidth(), label = { Text("Spoken line") }, enabled = !busy,
            )
            Spacer(Modifier.height(6.dp))
            SceneFields(s, !busy) { changed -> scenes[i] = changed; dirty = true }
        }
    }
    if (dirty) {
        Button(onClick = { save(app, p, scenes); dirty = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
    }

    Section("Ask the AI to change it") {
        OutlinedTextField(
            feedback, { feedback = it }, Modifier.fillMaxWidth(),
            label = { Text("What should change?") },
            placeholder = { Text("e.g. make the hook scarier, shorter, more examples") },
            enabled = !busy,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (dirty) save(app, p, scenes)
                val f = feedback
                app.jobs.start(p.id, "script") { it.writeScript(app.store.get(p.id) ?: p, f) }
                feedback = ""
            },
            enabled = !busy && feedback.isNotBlank(),
        ) { Text("Rewrite") }
    }

    if (p.sources.isNotEmpty()) {
        Section("Sources") {
            SelectionContainer {
                Text(p.sources.joinToString("\n") { "• ${it.claim}\n  ${it.url}" }, fontSize = 13.sp, color = Muted)
            }
        }
    }
}

private fun save(app: App, p: Project, scenes: List<Scene>) {
    val fresh = app.store.get(p.id) ?: p
    fresh.scenes = scenes.map { it.copy() }.toMutableList()
    app.store.save(fresh)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneFields(s: Scene, enabled: Boolean, onChange: (Scene) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in TYPES) {
            FilterChip(selected = s.type == t, onClick = { onChange(s.copy(type = t)) }, label = { Text(t, fontSize = 12.sp) }, enabled = enabled)
        }
    }
    @Composable
    fun field(label: String, value: String, set: (String) -> Scene) =
        OutlinedTextField(value, { onChange(set(it)) }, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, singleLine = true)

    when (s.type) {
        "hook" -> { field("Text on screen", s.text) { s.copy(text = it) }; field("Emoji", s.emoji) { s.copy(emoji = it) } }
        "stat" -> { field("Number on screen", s.value) { s.copy(value = it) }; field("Title", s.label) { s.copy(label = it) } }
        "list", "steps" -> {
            field("Title", s.text) { s.copy(text = it) }
            field("Items (separate with |)", s.items.joinToString(" | ")) { v -> s.copy(items = v.split("|").map { it.trim() }.filter { it.isNotEmpty() }) }
        }
        "compare" -> {
            field("Left label", s.left) { s.copy(left = it) }
            field("Left text", s.leftText) { s.copy(leftText = it) }
            field("Right label", s.right) { s.copy(right = it) }
            field("Right text", s.rightText) { s.copy(rightText = it) }
        }
        "emoji", "outro" -> { field("Text on screen", s.text) { s.copy(text = it) }; field("Emoji", s.emoji) { s.copy(emoji = it) } }
        "question" -> field("Question on screen", s.text) { s.copy(text = it) }
        "photo" -> { field("Photo search", s.query) { s.copy(query = it) }; field("Text on screen", s.text) { s.copy(text = it) } }
        else -> { field("Text on screen", s.text) { s.copy(text = it) }; field("Highlight word", s.highlight) { s.copy(highlight = it) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.VideoTab(app: App, p: Project, job: JobState?, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = app.settings
    val busy = job?.running == true
    val video = p.video(app.store.root)
    val thumb = rememberImage(p.thumb(app.store.root), 720)

    if (!video.exists()) {
        Section("No video yet") {
            Hint(if (busy) "Working on it…" else "Open the Script tab and tap Make the video.")
        }
        return
    }

    Box(
        Modifier
            .fillMaxWidth(0.62f)
            .align(Alignment.CenterHorizontally)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(18.dp))
            .background(Surface2)
            .clickable {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Share.uri(ctx, video), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(i) }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.width(64.dp).height(64.dp))
    }
    Hint(String.format("Tap the video to play. %.0f seconds.", p.durationSec), Muted)

    // ---- Text that goes with the post ----
    var title by remember(p.id) { mutableStateOf(p.ytTitle.ifBlank { p.displayTitle() }) }
    var description by remember(p.id) { mutableStateOf(p.description) }
    var tags by remember(p.id) { mutableStateOf(p.hashtags.joinToString(" ")) }
    var visibility by rememberSaveable { mutableStateOf(s.defaultVisibility) }
    var aiLabel by remember { mutableStateOf(s.aiLabel) }

    fun saveText() {
        val fresh = app.store.get(p.id) ?: p
        fresh.ytTitle = title.trim()
        fresh.description = description.trim()
        fresh.hashtags = tags.split(Regex("[\\s,]+")).map { it.removePrefix("#") }.filter { it.isNotBlank() }
        app.store.save(fresh)
        s.defaultVisibility = visibility
        s.aiLabel = aiLabel
    }

    Section("Title, description, hashtags") {
        OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Title (${title.length}/100)") })
        if (p.titleOptions.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in p.titleOptions) FilterChip(selected = t == title, onClick = { title = t }, label = { Text(t, fontSize = 12.sp, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 3)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Hashtags (no #)") })
        Spacer(Modifier.height(10.dp))
        Text("Who can see it")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (v in listOf("private", "unlisted", "public")) {
                FilterChip(selected = visibility == v, onClick = { visibility = v }, label = { Text(v.replaceFirstChar { it.uppercase() }) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI label")
                Hint("Marks the post as AI-made where the network supports it.")
            }
            Switch(aiLabel, { aiLabel = it })
        }
    }

    // ---- One tap: every connected account ----
    PostEverywhere(app, p, busy, onSettings) { platforms ->
        saveText()
        val v = visibility
        app.jobs.start(p.id, "post") { it.postEverywhere(app.store.get(p.id) ?: p, platforms, v) }
    }

    // ---- Share through the apps on this phone ----
    Section("Share with an app on this phone") {
        Hint("Opens the app with the video attached. The caption is copied: paste it in the caption box.")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in Share.targets) {
                OutlinedButton(onClick = {
                    saveText()
                    val fresh = app.store.get(p.id) ?: p
                    val caption = Pipeline.publishText(fresh, s, forYouTube = false)
                    if (Share.shareTo(ctx, t, video, caption)) toast(ctx, "Caption copied — paste it in ${t.name}")
                    else toast(ctx, "${t.name} is not installed on this phone.")
                }) { Text(t.name) }
            }
            OutlinedButton(onClick = {
                saveText()
                Share.shareAny(ctx, video, Pipeline.publishText(app.store.get(p.id) ?: p, s))
            }) { Text("Other…") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                saveText()
                Share.copy(ctx, "caption", Pipeline.publishText(app.store.get(p.id) ?: p, s))
                toast(ctx, "Caption copied")
            }) { Text("Copy caption") }
            TextButton(onClick = {
                scope.launch {
                    val where = withContext(Dispatchers.IO) { runCatching { Share.saveToGallery(ctx, video, p.displayTitle()) } }
                    toast(ctx, where.fold({ "Saved to $it" }, { "Could not save: ${it.message}" }))
                }
            }) { Text("Save to gallery") }
        }
    }

    // ---- Direct YouTube upload (advanced) ----
    YouTubeDirect(app, busy, onSettings) {
        saveText()
        val v = visibility
        app.jobs.start(p.id, "upload") { it.uploadYouTube(app.store.get(p.id) ?: p, v) }
    }

    if (p.uploads.isNotEmpty()) {
        Section("Posted") {
            for (u in p.uploads.asReversed()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("${SocialPost.name(u.platform)} · ${u.visibility}", Modifier.weight(1f), fontSize = 14.sp)
                    if (u.url.startsWith("http")) {
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) } }) { Text("Open") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostEverywhere(app: App, p: Project, busy: Boolean, onSettings: () -> Unit, onPost: (List<String>) -> Unit) {
    val s = app.settings
    val social = remember { SocialPost(s) }
    var connected by remember { mutableStateOf(s.socialConnected) }
    val names = remember(connected) { s.socialAccountNames }
    val chosen = remember(connected) {
        mutableStateListOf<String>().apply { addAll(s.socialTargets.filter { it in connected }.ifEmpty { connected }) }
    }
    val returned by MainActivity.returns.collectAsState()
    // Coming back from the connect page, or opening the screen: read the linked accounts again.
    LaunchedEffect(returned) {
        if (social.configured) {
            withContext(Dispatchers.IO) { runCatching { social.refreshAccounts() } }.onSuccess { list ->
                connected = list.map { it.platform }
            }
        }
    }

    Section("Post everywhere") {
        if (!social.configured) {
            Hint("Connect TikTok, Instagram, YouTube, Facebook, X, LinkedIn and more once, then post this video to all of them with one tap. No Google Cloud setup, no developer accounts.")
            Spacer(Modifier.height(10.dp))
            Button(onClick = onSettings) { Text("Connect accounts") }
            return@Section
        }
        if (connected.isEmpty()) {
            Hint("No accounts connected yet. Open Settings → Connected accounts and tap Connect accounts.")
            Spacer(Modifier.height(10.dp))
            Button(onClick = onSettings) { Text("Connect accounts") }
            return@Section
        }
        val posted = p.uploads.map { it.platform }.toSet()
        for (key in connected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { if (key in chosen) chosen.remove(key) else chosen.add(key) },
            ) {
                Checkbox(key in chosen, { if (it) chosen.add(key) else chosen.remove(key) }, enabled = !busy)
                Column(Modifier.weight(1f)) {
                    Text(SocialPost.name(key))
                    val n = names[key]
                    if (!n.isNullOrBlank() && n != SocialPost.name(key)) Hint(n)
                }
                if (key in posted) Pill("posted", Good)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                s.socialTargets = chosen.toList()
                onPost(connected.filter { it in chosen })
            },
            enabled = !busy && chosen.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(
                if (chosen.size == connected.size) "Post to all ${chosen.size}" else "Post to ${chosen.size}",
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Hint("Instagram and TikTok usually take a minute or two to publish. You can leave the app; a notification shows the progress.")
    }
}

@Composable
private fun YouTubeDirect(app: App, busy: Boolean, onSettings: () -> Unit, onUpload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val yt = remember { YouTube(app.settings) }
    var signedIn by remember { mutableStateOf(yt.signedIn) }
    var signingIn by remember { mutableStateOf(false) }
    // Only show the direct route to people who set it up (or have not set up one-tap posting).
    if (yt.configProblem() != null && SocialPost(app.settings).configured) return

    Section("YouTube (direct, with your own Google Cloud)") {
        val problem = yt.configProblem()
        when {
            problem != null -> {
                Hint(problem)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSettings) { Text("Open Settings") }
            }
            !signedIn -> {
                Hint("Sign in once with the Google account that owns your channel.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !signingIn,
                    onClick = {
                        signingIn = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { yt.signIn(ctx as Activity) } }
                            signingIn = false
                            signedIn = yt.signedIn
                            r.exceptionOrNull()?.let { toast(ctx, it.message ?: "Sign-in failed") }
                        }
                    },
                ) { Text(if (signingIn) "Waiting for Google…" else "Sign in with Google") }
            }
            else -> {
                Button(onClick = onUpload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Upload to YouTube") }
            }
        }
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

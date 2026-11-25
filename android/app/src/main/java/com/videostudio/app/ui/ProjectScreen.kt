package com.videostudio.app.ui

import android.app.Activity
import com.videostudio.app.compliance.YouTubeCheck
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
                if (tab == 0) ScriptTab(app, p, job) { tab = 1 } else VideoTab(app, p, job)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(
            if (p.style >= 0) "Look: ${com.videostudio.app.render.Look.of(p.style).description}"
            else "Look: chosen when you make the video (different from your last video)",
            Muted,
        )
    }
    TextButton(enabled = !busy, onClick = {
        val fresh = app.store.get(p.id) ?: p
        fresh.style = com.videostudio.app.render.Look.pick((System.nanoTime() % 100000).toInt(), fresh.style.takeIf { it >= 0 } ?: app.settings.lastStyle)
        app.settings.lastStyle = fresh.style
        app.store.save(fresh)
    }) { Text("Shuffle look (then make the video again)") }

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
private fun ColumnScope.VideoTab(app: App, p: Project, job: JobState?) {
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
    var aiLabel by remember { mutableStateOf(s.aiLabel) }

    fun saveText() {
        val fresh = app.store.get(p.id) ?: p
        fresh.ytTitle = title.trim()
        fresh.description = description.trim()
        fresh.hashtags = tags.split(Regex("[\\s,]+")).map { it.removePrefix("#") }.filter { it.isNotBlank() }
        app.store.save(fresh)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI label")
                Hint("Adds the AI line from Settings to the caption.")
            }
            Switch(aiLabel, { aiLabel = it })
        }
    }

    // ---- YouTube rules, checked before posting (monetization) ----
    YouTubeCheckPanel(app, p, aiLabel) { saveText() }

    // ---- One button: every app on this phone, one after another ----
    PostToAll(app, p) {
        saveText()
        app.store.get(p.id) ?: p
    }

    Section("More") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                saveText()
                Share.shareAny(ctx, video, Pipeline.publishText(app.store.get(p.id) ?: p, s, credits = Pipeline.credits(p.dir(app.store.root))))
            }) { Text("Share to another app…") }
            TextButton(onClick = {
                saveText()
                Share.copy(ctx, "caption", Pipeline.publishText(app.store.get(p.id) ?: p, s, credits = Pipeline.credits(p.dir(app.store.root))))
                toast(ctx, "Caption copied")
            }) { Text("Copy caption") }
        }
        TextButton(onClick = {
            scope.launch {
                val where = withContext(Dispatchers.IO) { runCatching { Share.saveToGallery(ctx, video, p.displayTitle()) } }
                toast(ctx, where.fold({ "Saved to $it" }, { "Could not save: ${it.message}" }))
            }
        }) { Text("Save to gallery") }
    }

    // ---- Direct YouTube upload: only for people who set up their own Google Cloud client ----
    YouTubeDirect(app, busy) { visibility ->
        saveText()
        app.jobs.start(p.id, "upload") { it.uploadYouTube(app.store.get(p.id) ?: p, visibility) }
    }

    if (p.uploads.isNotEmpty()) {
        Section("Posted") {
            for (u in p.uploads.asReversed()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("${Share.name(u.platform)} · ${u.visibility}", Modifier.weight(1f), fontSize = 14.sp)
                    if (u.url.startsWith("http")) {
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) } }) { Text("Open") }
                    }
                }
            }
        }
    }
}

/**
 * "Post to all": opens TikTok, Instagram, YouTube, Facebook... one after another with the video
 * attached and the caption copied. Coming back to this screen moves on to the next app.
 */
@Composable
private fun PostToAll(app: App, p: Project, freshProject: () -> Project) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = app.settings
    val state by app.postAll.state.collectAsState()
    val active = state?.takeIf { it.projectId == p.id }
    var preparing by remember { mutableStateOf(false) }

    if (active != null) {
        PostAllProgress(app, active)
        return
    }

    val installed = remember { Share.targets.filter { Share.isInstalled(ctx, it.key) }.map { it.key }.toSet() }
    val chosen = remember {
        val saved = s.postTargets.filter { it in installed }
        mutableStateListOf<String>().apply { addAll(saved.ifEmpty { Share.MAIN.filter { it in installed } }) }
    }
    val posted = p.uploads.map { it.platform }.toSet()

    Section("Post to all your apps") {
        Hint("Uses the apps on this phone, where you are already signed in. Nothing to set up. Each app opens with the video ready and the caption copied; tap Post there, come back, and the next app opens.")
        Spacer(Modifier.height(8.dp))
        for (t in Share.targets) {
            val has = t.key in installed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = has) { if (t.key in chosen) chosen.remove(t.key) else chosen.add(t.key) },
            ) {
                Checkbox(t.key in chosen, { if (it) chosen.add(t.key) else chosen.remove(t.key) }, enabled = has)
                Text(t.name, Modifier.weight(1f), color = if (has) Color.White else Muted)
                when {
                    !has -> Hint("not installed")
                    t.key in posted -> Pill("posted", Good)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Keep the order of the list, whatever order the boxes were ticked in.
        val ordered = Share.targets.map { it.key }.filter { it in chosen }
        Button(
            onClick = {
                s.postTargets = ordered
                val fresh = freshProject()
                preparing = true
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        runCatching { Share.galleryUri(ctx, fresh.video(app.store.root), fresh.displayTitle()) }
                    }
                    preparing = false
                    uri.onSuccess {
                        app.postAll.start(
                            fresh.id, ordered, it,
                            caption = Pipeline.publishText(fresh, s, forYouTube = false, credits = Pipeline.credits(fresh.dir(app.store.root))),
                            title = fresh.ytTitle.ifBlank { fresh.displayTitle() },
                        )
                        app.postAll.openCurrent(ctx)
                    }.onFailure { toast(ctx, "Could not prepare the video: ${it.message}") }
                }
            },
            enabled = ordered.isNotEmpty() && !preparing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(
                when {
                    preparing -> "Preparing the video…"
                    ordered.isEmpty() -> if (installed.isEmpty()) "Install TikTok, Instagram, YouTube or Facebook" else "Pick at least one app"
                    else -> "Post to all ${ordered.size}"
                },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The rules that decide monetization, checked for this video, with the two declarations YouTube asks for. */
@Composable
private fun YouTubeCheckPanel(app: App, p: Project, aiLabel: Boolean, saveText: () -> Unit) {
    var kids by remember(p.id) { mutableStateOf(p.madeForKids) }
    var paid by remember(p.id) { mutableStateOf(p.paidPromotion) }
    val all by app.store.projects.collectAsState()
    val credits = remember(p.id, p.updated) { Pipeline.credits(p.dir(app.store.root)) }
    val probe = p.copy(madeForKids = kids, paidPromotion = paid)
    val items = remember(all, kids, paid, aiLabel, p.updated) { YouTubeCheck.check(probe, all, credits, aiLabel) }
    val warnings = items.count { it.level == YouTubeCheck.Level.WARN }

    fun store(change: (Project) -> Unit) {
        saveText()
        val fresh = app.store.get(p.id) ?: p
        change(fresh)
        app.store.save(fresh)
    }

    Section("YouTube check") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (warnings == 0) "Ready: follows the rules for monetization" else "$warnings to look at before posting",
                Modifier.weight(1f), fontWeight = FontWeight.Bold,
                color = if (warnings == 0) Good else Accent,
            )
        }
        Spacer(Modifier.height(8.dp))
        for (it in items) {
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(
                    when (it.level) { YouTubeCheck.Level.OK -> "✓"; YouTubeCheck.Level.WARN -> "!"; YouTubeCheck.Level.INFO -> "i" },
                    color = when (it.level) { YouTubeCheck.Level.OK -> Good; YouTubeCheck.Level.WARN -> Accent; YouTubeCheck.Level.INFO -> Muted },
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(it.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Hint(it.detail)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Made for kids")
                Hint("Only if children are the main audience (YouTube and US law require an honest answer).")
            }
            Switch(kids, { v -> kids = v; store { it.madeForKids = v } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Paid promotion")
                Hint("A brand paid or gave you something for this video.")
            }
            Switch(paid, { v -> paid = v; store { it.paidPromotion = v } })
        }
    }
}

@Composable
private fun PostAllProgress(app: App, st: com.videostudio.app.upload.PostAllState) {
    val ctx = LocalContext.current
    val current = st.current
    var countdown by remember(st.index, st.waiting) { mutableIntStateOf(3) }

    // Back from one app: the next one opens by itself after a short countdown.
    LaunchedEffect(st.index, st.waiting) {
        if (current == null || st.waiting) return@LaunchedEffect
        while (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        }
        app.postAll.openCurrent(ctx)
    }

    Section(if (st.finished) "All done" else "Posting to all your apps") {
        for ((i, key) in st.targets.withIndex()) {
            val (mark, color) = when {
                key in st.done -> "✓" to Good
                key in st.failed -> "✕" to Danger
                key in st.skipped -> "–" to Muted
                i == st.index -> "▶" to Accent
                else -> "•" to Muted
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(mark, color = color, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp))
                Text(Share.name(key), Modifier.weight(1f), color = if (i == st.index && !st.finished) Color.White else Muted)
                when {
                    key in st.done -> Hint("opened ✓", Good)
                    key in st.failed -> Hint("could not open", Danger)
                    key in st.skipped -> Hint("skipped")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            st.finished -> {
                Hint("Video Studio opened ${st.done.size} app${if (st.done.size == 1) "" else "s"} with your video. The video is also in your gallery (Movies/Video Studio).")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.postAll.stop() }) { Text("Close") }
                    if (st.done.isNotEmpty()) {
                        TextButton(onClick = { app.postAll.retryLast(ctx) }) { Text("Open ${Share.name(st.done.last())} again") }
                    }
                }
            }
            st.waiting && current != null -> {
                Text("Finish in ${Share.name(current)}, then come back here.", fontWeight = FontWeight.Bold)
                Share.target(current)?.let { Hint(it.hint) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.postAll.openCurrent(ctx) }) { Text("Open ${Share.name(current)} again") }
                    TextButton(onClick = { app.postAll.skip() }) { Text("Skip") }
                }
            }
            current != null -> {
                Text("Opening ${Share.name(current)} in $countdown…", fontWeight = FontWeight.Bold)
                Share.target(current)?.let { Hint(it.hint) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.postAll.openCurrent(ctx) }) { Text("Open now") }
                    TextButton(onClick = { app.postAll.skip() }) { Text("Skip") }
                    TextButton(onClick = { app.postAll.stop() }) { Text("Stop", color = Danger) }
                }
                if (st.done.isNotEmpty()) {
                    TextButton(onClick = { app.postAll.retryLast(ctx) }) { Text("Didn't post in ${Share.name(st.done.last())}? Open it again") }
                }
            }
        }
    }
}

@Composable
private fun YouTubeDirect(app: App, busy: Boolean, onUpload: (String) -> Unit) {
    val yt = remember { YouTube(app.settings) }
    // Hidden unless the user set up their own Google Cloud client and signed in (Settings → Advanced).
    if (yt.configProblem() != null || !yt.signedIn) return
    var visibility by rememberSaveable { mutableStateOf(app.settings.defaultVisibility) }
    Section("YouTube direct upload (advanced)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (v in listOf("private", "unlisted", "public")) {
                FilterChip(selected = visibility == v, onClick = { visibility = v }, label = { Text(v.replaceFirstChar { it.uppercase() }) })
            }
        }
        Button(
            onClick = { app.settings.defaultVisibility = visibility; onUpload(visibility) },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Upload to YouTube") }
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

package com.videostudio.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videostudio.app.App
import com.videostudio.app.data.Project
import com.videostudio.app.data.Status
import com.videostudio.app.upload.SocialPost
import com.videostudio.app.work.JobState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: App, onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val projects by app.store.projects.collectAsState()
    val jobs by app.jobs.state.collectAsState()
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Video Studio", fontWeight = FontWeight.Black) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New video", fontWeight = FontWeight.Bold) },
                containerColor = Accent,
                contentColor = androidx.compose.ui.graphics.Color.Black,
            )
        },
    ) { pad ->
        if (projects.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎬", fontSize = 64.sp)
                Spacer(Modifier.height(12.dp))
                Text("Make your first Short", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Hint(
                    if (app.settings.geminiKey.isBlank())
                        "First add your free Gemini API key in Settings (top right). Then tap New video, type a topic, and the app researches, writes, voices and renders it."
                    else
                        "Tap New video, type a topic, and the app researches, writes, voices and renders it, then posts it to TikTok, Instagram, YouTube, Facebook and more with one tap."
                )
                if (!SocialPost(app.settings).configured) {
                    Spacer(Modifier.height(16.dp))
                    Hint("Tip: connect your social accounts once in Settings → Connected accounts, and every video can go everywhere with one tap.", Accent2)
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.id }) { p -> ProjectRow(app, p, jobs[p.id]) { onOpen(p.id) } }
            }
        }
    }
}

@Composable
private fun ProjectRow(app: App, p: Project, job: JobState?, onClick: () -> Unit) {
    val thumb = rememberImage(p.thumb(app.store.root), 200)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 54.dp, height = 96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(p.scenes.firstOrNull()?.emoji?.ifBlank { null } ?: "🎞️", fontSize = 24.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.displayTitle(), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    job?.running == true -> Pill(job.step, Accent2)
                    p.error.isNotBlank() -> Pill("Needs attention", Danger)
                    p.status == Status.POSTED -> Pill("Posted", Good)
                    p.status == Status.RENDERED -> Pill("Video ready", Accent)
                    p.status == Status.SCRIPTED -> Pill("Script ready", Accent2)
                    else -> Pill("New", Muted)
                }
                if (p.durationSec > 0) {
                    Spacer(Modifier.width(8.dp))
                    Hint(String.format("%.0f s", p.durationSec))
                }
                val posted = p.uploads.map { it.platform }.distinct()
                if (posted.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Hint(posted.joinToString(" · ") { SocialPost.name(it) }, Good)
                }
            }
            if (job?.running == true) {
                Spacer(Modifier.height(8.dp))
                val prog = job.progress
                if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth(), color = Accent)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
            }
        }
    }
}

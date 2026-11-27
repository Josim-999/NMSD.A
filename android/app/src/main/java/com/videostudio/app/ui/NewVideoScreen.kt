package com.videostudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videostudio.app.App
import com.videostudio.app.data.Project
import com.videostudio.app.render.Palettes
import com.videostudio.app.voice.Voices

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewVideoScreen(app: App, onBack: () -> Unit, onCreated: (String) -> Unit, onSettings: () -> Unit) {
    val s = app.settings
    var topic by rememberSaveable { mutableStateOf("") }
    var audience by rememberSaveable { mutableStateOf("") }
    var take by rememberSaveable { mutableStateOf("") }
    var length by rememberSaveable { mutableIntStateOf(60) }
    var language by rememberSaveable { mutableStateOf(s.language) }
    var voice by rememberSaveable { mutableStateOf(Voices.find(s.voice).let { if (it.language == s.language) it.id else Voices.forLanguage(s.language).first().id }) }
    var palette by rememberSaveable { mutableStateOf(s.palette) }
    var research by rememberSaveable { mutableStateOf(true) }
    val needsKey = remember { s.geminiKey.isBlank() }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("New video", fontWeight = FontWeight.Black) },
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
            if (needsKey) {
                Section("Gemini key needed") {
                    Hint("The app uses Google's Gemini to research and write. Add your free API key first.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onSettings) { Text("Open Settings") }
                }
            }
            Section("What is the video about?") {
                OutlinedTextField(
                    topic, { topic = it }, Modifier.fillMaxWidth(),
                    label = { Text("Topic") },
                    placeholder = { Text("e.g. Why your phone battery dies faster in the cold") },
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    audience, { audience = it }, Modifier.fillMaxWidth(),
                    label = { Text("Audience (optional)") },
                    placeholder = { Text("e.g. beginners learning AI") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    take, { take = it }, Modifier.fillMaxWidth(),
                    label = { Text("Your take (optional)") },
                    placeholder = { Text("e.g. I think this is overhyped, because…") },
                    minLines = 2,
                )
                Hint("One sentence of your own opinion. It goes into the script, and original views keep a channel monetizable.")
            }
            Section("Length") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sec in listOf(50, 55, 60)) {
                        FilterChip(selected = length == sec, onClick = { length = sec }, label = { Text("$sec s") })
                    }
                }
            }
            Section("Voice") {
                Dropdown("Language", language, Voices.languages) {
                    language = it
                    voice = Voices.forLanguage(it).first().id
                }
                Spacer(Modifier.height(8.dp))
                val options = Voices.forLanguage(language)
                Dropdown("Voice", Voices.find(voice).label, options.map { it.label }) { label ->
                    voice = options.first { it.label == label }.id
                }
            }
            Section("Look") {
                Hint("Auto: a different colour palette every video (recommended). Or pick one to fix it for this video.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { palette = Palettes.AUTO }) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(Brush.sweepGradient(Palettes.all.map { Color(it.accent) }))
                                .border(if (palette == Palettes.AUTO) 3.dp else 1.dp, if (palette == Palettes.AUTO) Accent else Surface2, CircleShape),
                        )
                        Text("Auto", color = if (palette == Palettes.AUTO) Accent else Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                    for (pal in Palettes.all) {
                        val selected = pal.id == palette
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { palette = pal.id }) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Brush.verticalGradient(listOf(Color(pal.bgTop), Color(pal.bgBottom))))
                                    .border(if (selected) 3.dp else 1.dp, if (selected) Color(pal.accent) else Surface2, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(16.dp).clip(CircleShape).background(Color(pal.accent)))
                            }
                            Text(pal.name, color = if (selected) Accent else Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Section("Research") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Research with Google Search")
                        Hint("Finds real facts and sources first. Slower, but the script is accurate.")
                    }
                    Switch(research, { research = it })
                }
            }
            Button(
                onClick = {
                    val p = Project(
                        id = app.store.newId(topic),
                        topic = topic.trim(),
                        audience = audience.trim(),
                        lengthSec = length,
                        language = language,
                        voice = voice,
                        palette = palette,
                        research = research,
                        take = take.trim(),
                    )
                    app.store.save(p)
                    s.language = language
                    s.voice = voice
                    s.palette = palette
                    // One tap: script, then straight on to the finished video (Settings → "Make the video right after the script").
                    val auto = s.autoMake
                    app.jobs.start(p.id, "script") {
                        it.writeScript(p)
                        if (auto) app.store.get(p.id)?.takeIf { q -> q.scenes.isNotEmpty() }?.let { q -> it.render(q) }
                    }
                    onCreated(p.id)
                },
                enabled = topic.isNotBlank() && s.geminiKey.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Write the script", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false })
        }
    }
}

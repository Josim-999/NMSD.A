package com.videostudio.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One folder per video under [root], each with a project.json. */
class ProjectStore(val root: File) {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    init {
        root.mkdirs()
        reload()
    }

    @Synchronized
    fun reload() {
        val list = (root.listFiles() ?: emptyArray()).mapNotNull { dir ->
            val f = File(dir, "project.json")
            if (!f.exists()) null else runCatching { Project.fromJson(JSONObject(f.readText())) }.getOrNull()
        }
        _projects.value = list.sortedByDescending { it.created }
    }

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(p: Project) {
        p.updated = System.currentTimeMillis()
        val dir = p.dir(root)
        dir.mkdirs()
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(p.toJson().toString(1))
        tmp.renameTo(File(dir, "project.json"))
        // Publish a copy so screens holding the old object see a change.
        val copy = Project.fromJson(p.toJson())
        _projects.value = (_projects.value.filter { it.id != p.id } + copy).sortedByDescending { it.created }
    }

    @Synchronized
    fun delete(id: String) {
        File(root, id).deleteRecursively()
        _projects.value = _projects.value.filter { it.id != id }
    }

    fun newId(topic: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val slug = topic.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "video" }
        var id = "$date-$slug"
        var n = 2
        while (File(root, id).exists()) id = "$date-$slug-${n++}"
        return id
    }
}

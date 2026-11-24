package com.videostudio.app.work

import android.content.Context
import android.content.Intent
import com.videostudio.app.data.ProjectStore
import com.videostudio.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JobState(
    val projectId: String,
    val kind: String,
    val step: String = "Starting",
    val progress: Float? = null,
    val log: List<String> = emptyList(),
    val running: Boolean = true,
    val error: String? = null,
    val cancelRequested: Boolean = false,
)

/** Long work (script, render, upload) runs here, one job per video, kept alive by [JobService]. */
class Jobs(private val context: Context, private val store: ProjectStore, private val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<Map<String, JobState>>(emptyMap())
    val state: StateFlow<Map<String, JobState>> = _state

    val anyRunning: Boolean get() = _state.value.values.any { it.running }

    fun isRunning(id: String) = _state.value[id]?.running == true

    private fun edit(id: String, f: (JobState) -> JobState) = _state.update { m ->
        val j = m[id] ?: return@update m
        m + (id to f(j))
    }

    fun cancel(id: String) = edit(id) { it.copy(step = "Stopping…", cancelRequested = true) }

    fun clear(id: String) = _state.update { m -> if (m[id]?.running == true) m else m - id }

    fun start(projectId: String, kind: String, work: (Pipeline) -> Unit) {
        if (isRunning(projectId)) return
        _state.update { it + (projectId to JobState(projectId, kind)) }
        startService()
        scope.launch {
            val pipeline = Pipeline(
                context, store, settings,
                log = { line -> edit(projectId) { it.copy(log = (it.log + line).takeLast(200)) } },
                step = { s, p -> edit(projectId) { it.copy(step = s, progress = p) } },
                cancelled = { _state.value[projectId]?.cancelRequested == true },
            )
            try {
                work(pipeline)
                val stopped = _state.value[projectId]?.cancelRequested == true
                edit(projectId) { it.copy(step = if (stopped) "Stopped" else "Done", progress = null, running = false, cancelRequested = false) }
            } catch (t: Throwable) {
                val stopped = _state.value[projectId]?.cancelRequested == true
                val msg = if (stopped) null else (t.message ?: t.toString())
                if (msg != null) store.get(projectId)?.let { p ->
                    p.error = msg
                    store.save(p)
                }
                edit(projectId) {
                    it.copy(
                        step = if (stopped) "Stopped" else "Failed",
                        progress = null,
                        log = it.log + (if (msg != null) "Error: $msg" else "Stopped."),
                        running = false,
                        error = msg,
                        cancelRequested = false,
                    )
                }
            }
        }
    }

    private fun startService() {
        runCatching { context.startForegroundService(Intent(context, JobService::class.java)) }
    }
}

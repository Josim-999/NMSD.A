package com.videostudio.app.upload

import android.content.Context
import android.net.Uri
import com.videostudio.app.data.ProjectStore
import com.videostudio.app.data.Status
import com.videostudio.app.data.Upload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where "Post to all" is: which apps, which one is open now, which are finished. */
data class PostAllState(
    val projectId: String,
    val targets: List<String>,
    val index: Int = 0,
    /** The current app was opened and we are waiting for the user to come back. */
    val waiting: Boolean = false,
    val done: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
) {
    val current: String? get() = targets.getOrNull(index)
    val finished: Boolean get() = index >= targets.size
}

/**
 * "Post to all" with the apps already on the phone: opens each one in turn with the video attached
 * and the caption on the clipboard. When the user comes back to Video Studio the next app is due.
 * Nothing to set up: the user is already signed in to those apps.
 */
class PostAll(private val store: ProjectStore) {
    private val _state = MutableStateFlow<PostAllState?>(null)
    val state: StateFlow<PostAllState?> = _state

    private var video: Uri? = null
    private var caption = ""
    private var title = ""
    /** A caption made for each app (hashtag limits, length, style). */
    private var captions: Map<String, String> = emptyMap()

    fun start(projectId: String, targets: List<String>, video: Uri, caption: String, title: String, captions: Map<String, String> = emptyMap()) {
        this.video = video
        this.caption = caption
        this.title = title
        this.captions = captions
        _state.value = PostAllState(projectId, targets)
    }

    /** Opens the current app. Moves past apps that are missing or refuse to open. */
    fun openCurrent(context: Context) {
        while (true) {
            val s = _state.value ?: return
            val key = s.current ?: return
            val target = Share.target(key)
            val v = video
            val text = captions[key] ?: if (key == "youtube") title else caption
            if (target != null && v != null && Share.open(context, target, v, text, title)) {
                _state.value = s.copy(waiting = true)
                return
            }
            _state.value = s.copy(index = s.index + 1, failed = s.failed + key, waiting = false)
        }
    }

    /** Called when Video Studio is back on screen: the app we opened is finished. */
    fun onReturn() {
        val s = _state.value ?: return
        if (!s.waiting) return
        val key = s.current ?: return
        record(s.projectId, key)
        _state.value = s.copy(index = s.index + 1, waiting = false, done = s.done + key)
    }

    fun skip() {
        val s = _state.value ?: return
        val key = s.current ?: return
        _state.value = s.copy(index = s.index + 1, waiting = false, skipped = s.skipped + key)
    }

    /** Opens the previous app again (it did not post, or the user changed their mind). */
    fun retryLast(context: Context) {
        val s = _state.value ?: return
        val last = s.done.lastOrNull() ?: return
        _state.value = s.copy(index = s.targets.indexOf(last), done = s.done.dropLast(1), waiting = false)
        openCurrent(context)
    }

    fun stop() {
        _state.value = null
    }

    private fun record(projectId: String, key: String) {
        val p = store.get(projectId) ?: return
        p.uploads.add(Upload(key, "", "shared from phone", System.currentTimeMillis()))
        p.status = Status.POSTED
        store.save(p)
    }
}

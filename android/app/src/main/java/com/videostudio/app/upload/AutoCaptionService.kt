package com.videostudio.app.upload

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types the right caption into TikTok, Instagram, Facebook (and the YouTube app's title) when
 * "Post to all" opens them, because those apps ignore captions handed over with a shared video.
 * It only fills an EMPTY caption box, only in the app Video Studio just opened, only once, and it
 * never presses Post. The user switches it on once in Android's accessibility settings.
 */
class AutoCaptionService : AccessibilityService() {
    private var lastCheck = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pending = AutoCaption.pending ?: return
        if (System.currentTimeMillis() > pending.until) { AutoCaption.pending = null; return }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in pending.packages) return
        val now = System.currentTimeMillis()
        if (now - lastCheck < 250) return
        lastCheck = now
        val root = rootInActiveWindow ?: return
        val field = AutoCaption.findCaptionField(root) ?: return
        val current = field.text?.toString().orEmpty()
        val hint = field.hintText?.toString().orEmpty()
        if (current.isNotBlank() && current != hint) { AutoCaption.pending = null; return } // the user already typed
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pending.text) }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            AutoCaption.filled = pending.key
            AutoCaption.pending = null
        }
    }

    override fun onInterrupt() {}
}

object AutoCaption {
    data class Pending(val key: String, val packages: Set<String>, val text: String, val until: Long)

    @Volatile var pending: Pending? = null
    /** The last app a caption was typed into (shown on the Post-to-all screen). */
    @Volatile var filled: String? = null

    private val WORDS = listOf(
        "caption", "description", "describe", "write", "say something", "what's on your mind", "add a title",
        "title", "add text", "your short", "about this",
    )

    fun arm(key: String, packages: List<String>, text: String) {
        pending = Pending(key, packages.toSet(), text, System.currentTimeMillis() + 10 * 60_000L)
        filled = null
    }

    /** The app's caption box: an editable, visible field whose hint or id says caption/description. */
    fun findCaptionField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 1500) {
            val n = stack.removeLast(); seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            if (!n.isEditable || !n.isVisibleToUser || n.isPassword) continue
            val score = score(
                n.hintText?.toString().orEmpty(), n.text?.toString().orEmpty(),
                n.contentDescription?.toString().orEmpty(), n.viewIdResourceName.orEmpty(), n.isMultiLine,
            )
            if (score > bestScore) { best = n; bestScore = score }
        }
        return best
    }

    /** How sure we are that a field is a caption box (0 = not at all). Search boxes never qualify. */
    fun score(hint: String, text: String, desc: String, id: String, multiLine: Boolean): Int {
        val all = "$hint $desc $id ${if (text.length < 60) text else ""}".lowercase()
        if (listOf("search", "comment", "reply", "message", "password", "email", "phone").any { all.contains(it) }) return 0
        val hits = WORDS.count { all.contains(it) }
        if (hits == 0) return 0
        return hits * 10 + if (multiLine) 5 else 0
    }

    fun isEnabled(context: Context): Boolean {
        val me = ComponentName(context, AutoCaptionService::class.java).flattenToString()
        val list = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

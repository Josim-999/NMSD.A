package com.videostudio.app.upload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** An app on the phone the video can be handed to. [key] is also what an Upload's platform holds. */
data class ShareTarget(val key: String, val name: String, val packages: List<String>, val hint: String)

/** Hands the video to the apps on the phone, where the user is already signed in. No keys, no accounts. */
object Share {
    val targets = listOf(
        ShareTarget(
            "tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
            "Tap Next: the caption is typed in for you (or long-press the text box → Paste), then Post."
        ),
        ShareTarget(
            "instagram", "Instagram", listOf("com.instagram.android"),
            "Choose Reels, tap Next: the caption is typed in for you (or long-press → Paste), then Share."
        ),
        ShareTarget(
            "youtube", "YouTube", listOf("com.google.android.youtube"),
            "It opens as a Short. Paste the title (long-press → Paste). Audience: choose the answer from the YouTube check. Altered content: No (unless it shows realistic fake events). Then Upload."
        ),
        ShareTarget(
            "facebook", "Facebook", listOf("com.facebook.katana", "com.facebook.lite"),
            "Choose Reel: the caption is typed in for you (or paste it), then Share."
        ),
        ShareTarget("x", "X", listOf("com.twitter.android"), "The caption is typed in for you (or paste it), then Post."),
        ShareTarget("threads", "Threads", listOf("com.instagram.barcelona"), "The caption is typed in for you (or paste it), then Post."),
        ShareTarget("linkedin", "LinkedIn", listOf("com.linkedin.android"), "The caption is typed in for you (or paste it), then Post."),
        ShareTarget("whatsapp", "WhatsApp", listOf("com.whatsapp"), "Choose My status or a chat, then Send."),
    )

    /** Where "Post to all" goes by default. */
    val MAIN = listOf("tiktok", "instagram", "youtube", "facebook")

    fun target(key: String) = targets.firstOrNull { it.key == key }

    fun name(key: String) = target(key)?.name ?: when (key) {
        "youtube_app" -> "YouTube"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun installedPackage(context: Context, target: ShareTarget): String? = target.packages.firstOrNull {
        runCatching { context.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false)
    }

    fun isInstalled(context: Context, key: String) = target(key)?.let { installedPackage(context, it) } != null

    /**
     * Opens [target] with the video attached and the text it needs on the clipboard.
     * [video] is best a gallery (MediaStore) Uri: every app accepts those.
     * Returns false when the app is not installed or refused to open.
     */
    fun open(context: Context, target: ShareTarget, video: Uri, caption: String, title: String): Boolean {
        val pkg = installedPackage(context, target) ?: return false
        // The caption passed in is already made for this app (for YouTube it is the title line).
        copy(context, "caption", caption)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, video)
            clipData = ClipData.newRawUri("video", video)
            putExtra(Intent.EXTRA_TEXT, caption)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun shareAny(context: Context, video: File, caption: String) {
        copy(context, "caption", caption)
        val u = uri(context, video)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, u)
            clipData = ClipData.newRawUri("video", u)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * The video as a gallery item, saved once per render. Sharing a gallery Uri works with every app,
     * and the video is in Movies/Video Studio if an app wants it picked by hand.
     */
    fun galleryUri(context: Context, video: File, name: String): Uri {
        val prefs = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
        val key = video.absolutePath + "|" + video.lastModified() + "|" + video.length()
        prefs.getString(key, null)?.let { saved ->
            val u = Uri.parse(saved)
            val alive = runCatching { context.contentResolver.openInputStream(u)?.use { true } ?: false }.getOrDefault(false)
            if (alive) return u
        }
        val u = saveToGalleryUri(context, video, name)
        prefs.edit().putString(key, u.toString()).apply()
        return u
    }

    /** Copies the video to Movies/Video Studio. Returns where it went, for a message. */
    fun saveToGallery(context: Context, video: File, name: String): String {
        saveToGalleryUri(context, video, name)
        return "Movies/Video Studio/" + fileName(name)
    }

    private fun fileName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "video" } + ".mp4"

    private fun saveToGalleryUri(context: Context, video: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName(name))
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Video Studio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("the gallery refused the file")
            resolver.openOutputStream(uri)!!.use { out -> video.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        // Android 8-9: app-specific Movies folder, shared through our FileProvider.
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Video Studio")
        dir.mkdirs()
        val dest = File(dir, fileName(name))
        video.copyTo(dest, overwrite = true)
        return uri(context, video)
    }
}

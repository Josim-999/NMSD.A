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

data class ShareTarget(val key: String, val name: String, val packages: List<String>)

/** Hands the video to an app on the phone, with the caption on the clipboard. */
object Share {
    val targets = listOf(
        ShareTarget("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")),
        ShareTarget("instagram", "Instagram", listOf("com.instagram.android")),
        ShareTarget("facebook", "Facebook", listOf("com.facebook.katana", "com.facebook.lite")),
        ShareTarget("youtube_app", "YouTube app", listOf("com.google.android.youtube")),
        ShareTarget("x", "X", listOf("com.twitter.android")),
        ShareTarget("threads", "Threads", listOf("com.instagram.barcelona")),
        ShareTarget("linkedin", "LinkedIn", listOf("com.linkedin.android")),
        ShareTarget("whatsapp", "WhatsApp", listOf("com.whatsapp")),
    )

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun installedPackage(context: Context, target: ShareTarget): String? = target.packages.firstOrNull {
        runCatching { context.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false)
    }

    /** Opens [target] with the video attached. False when that app is not installed. */
    fun shareTo(context: Context, target: ShareTarget, video: File, caption: String): Boolean {
        val pkg = installedPackage(context, target) ?: return false
        copy(context, "caption", caption)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri(context, video))
            putExtra(Intent.EXTRA_TEXT, caption)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    fun shareAny(context: Context, video: File, caption: String) {
        copy(context, "caption", caption)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri(context, video))
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies the video to Movies/Video Studio. Returns where it went. */
    fun saveToGallery(context: Context, video: File, name: String): String {
        val fileName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
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
            return "Movies/Video Studio/$fileName"
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Video Studio")
        dir.mkdirs()
        val dest = File(dir, fileName)
        video.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }
}

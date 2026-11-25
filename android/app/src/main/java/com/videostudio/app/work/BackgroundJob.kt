package com.videostudio.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import com.videostudio.app.App
import com.videostudio.app.MainActivity
import com.videostudio.app.ai.Gemini
import com.videostudio.app.ai.GeminiQuotaError
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Work Android runs for us even when the app is closed:
 * - "script": writes a script again after Gemini's free quotas reset (midnight Pacific time);
 * - "upload": uploads a scheduled video to YouTube as soon as the phone is online.
 * Jobs survive a restart of the phone.
 */
class BackgroundJob : android.app.job.JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(EXTRA_PROJECT) ?: return false
        val kind = params.extras.getString(EXTRA_KIND) ?: return false
        val tries = params.extras.getInt(EXTRA_TRIES, 0)
        val app = application as App
        thread(name = "background-$kind") {
            val p = app.store.get(id)
            var reschedule = false
            if (p != null) {
                val lines = ArrayList<String>()
                val pipeline = Pipeline(this, app.store, app.settings, log = { lines.add(it) }, step = { _, _ -> }, cancelled = { false })
                try {
                    when (kind) {
                        KIND_SCRIPT -> {
                            pipeline.writeScript(p)
                            notify(this, id, "Script ready: ${p.displayTitle()}", "Open it to make the video.")
                        }
                        KIND_UPLOAD -> {
                            pipeline.uploadScheduled(p)
                            notify(this, id, "Scheduled on YouTube: ${p.displayTitle()}", "It goes public at the time you chose.")
                        }
                    }
                } catch (e: GeminiQuotaError) {
                    reschedule = tries < 3
                } catch (e: Exception) {
                    // A network drop during an upload: try again later (Android waits for a connection).
                    reschedule = kind == KIND_UPLOAD && tries < 5
                    if (!reschedule) notify(this, id, "Could not finish: ${p.displayTitle()}", e.message ?: e.toString())
                }
            }
            if (reschedule && p != null) {
                if (kind == KIND_SCRIPT) scheduleScript(this, id, tries + 1) else scheduleUpload(this, id, tries + 1)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val EXTRA_PROJECT = "project"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TRIES = "tries"
        const val KIND_SCRIPT = "script"
        const val KIND_UPLOAD = "upload"
        private const val CHANNEL = "background"

        private fun jobId(id: String, kind: String) = (kind + id).hashCode() and 0x3fffffff

        private fun schedule(context: Context, id: String, kind: String, tries: Int, delayMs: Long) {
            val extras = PersistableBundle().apply {
                putString(EXTRA_PROJECT, id); putString(EXTRA_KIND, kind); putInt(EXTRA_TRIES, tries)
            }
            val info = JobInfo.Builder(jobId(id, kind), ComponentName(context, BackgroundJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMs.coerceAtLeast(0))
                .setPersisted(true)
                .setExtras(extras)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        }

        /** Writes the script again a few minutes after the free quotas reset. Returns when, for the message. */
        fun scheduleScript(context: Context, id: String, tries: Int = 0): Long {
            val at = Gemini.nextReset() + 5 * 60_000L
            schedule(context, id, KIND_SCRIPT, tries, at - System.currentTimeMillis())
            return at
        }

        /** Uploads as soon as there is a connection (after a back-off when it is a retry). */
        fun scheduleUpload(context: Context, id: String, tries: Int = 0) =
            schedule(context, id, KIND_UPLOAD, tries, if (tries == 0) 0 else 60_000L * tries * tries)

        fun cancel(context: Context, id: String, kind: String) =
            context.getSystemService(JobScheduler::class.java).cancel(jobId(id, kind))

        fun isScheduled(context: Context, id: String, kind: String) =
            context.getSystemService(JobScheduler::class.java).getPendingJob(jobId(id, kind)) != null

        fun localTime(ms: Long): String =
            DateTimeFormatter.ofPattern("EEE h:mm a").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

        fun notify(context: Context, id: String, title: String, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Finished in the background", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, id.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                nm.notify(
                    jobId(id, title).let { it }, NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle(title).setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(open).setAutoCancel(true).build()
                )
            }
        }
    }
}

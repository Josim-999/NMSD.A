package com.videostudio.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.videostudio.app.App
import com.videostudio.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the app alive with a progress notification while any job runs. */
class JobService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Video jobs", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String, progress: Float?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Video Studio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        if (progress != null) b.setProgress(100, (progress * 100).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, ID, notification("Working…", null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videostudio:jobs")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        }
        if (watcher != null) return START_NOT_STICKY
        val jobs = (application as App).jobs
        watcher = scope.launch {
            jobs.state.collectLatest { map ->
                val running = map.values.filter { it.running }
                if (running.isEmpty()) {
                    ServiceCompat.stopForeground(this@JobService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val first = running.first()
                    val more = if (running.size > 1) " (+${running.size - 1} more)" else ""
                    getSystemService(NotificationManager::class.java).notify(ID, notification(first.step + more, first.progress))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "jobs"
        const val ID = 42
    }
}

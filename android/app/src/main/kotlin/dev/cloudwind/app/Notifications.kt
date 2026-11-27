package dev.cloudwind.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object Notifications {
    private const val CHANNEL = "backup"
    private const val ID = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup progress", NotificationManager.IMPORTANCE_LOW))
    }

    private fun build(context: Context, text: String, done: Int, total: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cloudwind")
            .setContentText(if (total > 0) "$text (${done + 1}/$total)" else text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()
    }

    fun foreground(context: Context, text: String, done: Int, total: Int) =
        ForegroundInfo(ID, build(context, text, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

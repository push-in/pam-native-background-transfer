package dev.pam.backgroundtransfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo

internal object TransferNotifications {
    private const val CHANNEL = "dev.pam.background-transfer"
    private const val RESULT_CHANNEL = "dev.pam.background-transfer.results"

    fun notificationId(id: String) = (id.hashCode() and 0x3fffffff).coerceAtLeast(1)

    fun foreground(context: Context, id: String, kind: Int, spec: NotificationSpec, transferred: Long, total: Long): ForegroundInfo {
        ensureChannel(context, CHANNEL, spec.channel ?: "Transfers", NotificationManager.IMPORTANCE_LOW)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(spec.title)
            .setSmallIcon(if (kind == 1) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        spec.text?.let(builder::setContentText)
        launchIntent(context)?.let(builder::setContentIntent)
        if (spec.progress) {
            val indeterminate = total <= 0
            val percent = if (indeterminate) 0 else ((transferred * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, indeterminate)
        }
        val notification: Notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(id), notification)
        }
    }

    /** Posts the optional completion/failure notification when the app may post notifications. */
    fun result(context: Context, id: String, spec: NotificationSpec, success: Boolean, message: String) {
        val title = (if (success) spec.completed else spec.failed) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context, RESULT_CHANNEL, spec.channel ?: "Transfers", NotificationManager.IMPORTANCE_DEFAULT)
        val builder = NotificationCompat.Builder(context, RESULT_CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
        if (!success && message.isNotBlank()) builder.setContentText(message.take(200))
        launchIntent(context)?.let(builder::setContentIntent)
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(id) + 1, builder.build()) }
    }

    private fun ensureChannel(context: Context, id: String, name: String, importance: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun launchIntent(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
}

package kr.co.tesla.cameraalert.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kr.co.tesla.cameraalert.MainActivity

/** A silent, dismissible per-camera notice; connection status has its own service channel. */
object CameraAlertNotification {
    // New ID means the silent policy is applied even on devices with the old high-priority channel.
    const val CHANNEL = "camera_alert_events_silent"
    const val NOTIFICATION_ID = 1002
    const val DISMISS_ACTION = "dismiss_camera_alert"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "안전 안내", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, text: String) {
        ensureChannel(context)
        val dismiss = PendingIntent.getService(context, 2, Intent(context, CameraMonitorService::class.java)
            .setAction(DISMISS_ACTION), PendingIntent.FLAG_IMMUTABLE)
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val alert = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("과속·안전 안내")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setSilent(true)
            .setAutoCancel(false)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "알림 닫기", dismiss)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, alert)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

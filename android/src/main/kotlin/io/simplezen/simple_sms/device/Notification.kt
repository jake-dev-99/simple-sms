package io.simplezen.simple_sms.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class Notification(val context: Context) {
    companion object {
        private const val CHANNEL_ID = "simple_sms_channel"
        private const val CHANNEL_NAME = "Messages"
        private const val CHANNEL_DESCRIPTION = "SMS and MMS message notifications"
        private var channelCreated = false
    }

    private fun ensureChannel() {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        channelCreated = true
    }

    fun showSimpleNotification(title: String, body: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationId = System.currentTimeMillis().toInt()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

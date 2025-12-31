package io.simplezen.simple_sms.device

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import io.flutter.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.simplezen.simple_sms.SimpleSmsPlugin

class Notification(val context: Context, private val activity: Activity) : MethodCallHandler {

        fun createConversationNotificationChannel(context: Context) {
                // Define the channel ID and name
                val channelId = "conversation_channel_id"
                val channelName = "Conversations"
                val channelDescription = "Notifications for conversations"

                // Set the importance level to HIGH for conversations
                val importance = NotificationManager.IMPORTANCE_HIGH

                // Create the notification channel
                val channel =
                        NotificationChannel(channelId, channelName, importance).apply {
                                description = channelDescription
                                // Mark the channel as conversation-specific
                                setConversationId(
                                        "conversation_group",
                                        "conversation_channel_id"
                                )
                        }

                // Register the channel with the system
                val notificationManager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as
                                NotificationManager
                notificationManager.createNotificationChannel(channel)
        }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                val notificationId = call.argument<Int>("notificationId") ?: 0
                val channelId = call.argument<String>("channelId") ?: ""
                val conversationId = call.argument<String>("conversationId") ?: ""
                val personKey = call.argument<String?>("personKey") ?: ""
                val shortLabel = call.argument<String>("shortLabel") ?: ""
                val longLabel = call.argument<String>("longLabel") ?: ""
                val body = call.argument<String>("body") ?: ""
                val shortcutIntent = call.argument<String>("shortcutIntent") ?: ""
                val personName = call.argument<String>("personName") ?: ""
                val personIcon = call.argument<Int>("personIcon") ?: 0
                Log.d("Notification", " <<< Creating Notification")

                // Create a Person object for the conversation participant
                val person =
                        Person.Builder()
                                .setName(personName)
                                .setKey(personKey)
                                // .setIcon(IconCompat.createWithResource(context, personIcon))
                                .build()

                // Create a LocusId for the conversation
                val locusId = LocusIdCompat("conversation_locus_id")

                // Create the shortcut
                val shortcut =
                        ShortcutInfoCompat.Builder(context, conversationId)
                                .setShortLabel(shortLabel)
                                .setLongLabel(longLabel)
                                .setIsConversation()
                                .setPerson(person)
                                .setLocusId(locusId)
                                .setIntent(
                                        Intent(
                                                        context,
                                                        SimpleSmsPlugin::class.java
                                                ) // Replace with your activity
                                                .setAction(Intent.ACTION_VIEW)
                                                .putExtra("conversation_id", conversationId)
                                )
                                .build()

                // Publish the shortcut
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

                // Create the notification using MessagingStyle
                val messagingStyle =
                        NotificationCompat.MessagingStyle(person)
                                .setConversationTitle("New Message from ${person.name}")
                                .addMessage(
                                        "Hello!\n$body",
                                        System.currentTimeMillis(),
                                        person
                                ) // TODO: Replace with message body

                // Build the notification
                val notificationBuilder =
                        NotificationCompat.Builder(context, channelId)
                                .setShortcutId(shortcut.id)
                                .setContentIntent(
                                        shortcut.intent.let {
                                                PendingIntent.getActivity(
                                                        context,
                                                        0,
                                                        it,
                                                        PendingIntent.FLAG_IMMUTABLE
                                                )
                                        }
                                )

                // Show the notification
                val notificationManager = NotificationManagerCompat.from(context)
                Log.d("Notification", " <<< notificationId: $notificationId")
                notificationManager.notify(notificationId, notificationBuilder.build())
        }
}

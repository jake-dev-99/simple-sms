package io.simplezen.simple_sms.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.mms.pdu_alt.PduPersister.toIsoString
import io.simplezen.simple_sms.models.MmsObject
import io.simplezen.simple_sms.models.MmsPart
import java.io.File
import java.util.concurrent.Executors

// Inbound MMS Messages
class InboundMmsHandler() : BroadcastReceiver() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == "android.provider.Telephony.WAP_PUSH_DELIVER"
            && intent.type == "application/vnd.wap.mms-message"
        ) {
            val pdu = intent.getByteArrayExtra("data") ?: return

            // Parse NotificationInd from PDU
            val notification = PduParser(pdu, true).parse()
            if (notification !is NotificationInd) {
                Log.e("InboundMmsHandler", "Failed to parse MMS NotificationInd PDU!")
                return
            }

            val extras : Bundle? = intent.extras
            val extraStr = extras?.keySet()
                ?.joinToString(separator = "\n", prefix = "Intent String:\n", postfix = "\n") {
                    val value = extras.get(it)
                    if (value is ByteArray) {
                        "$it - ${String(value)}"
                    } else {
                        "$it - $value"
                    }
                }

            Log.d("TestInboundMms.onReceive", "Received Inbound MMS")
            Log.d("TestInboundMms.onReceive", "--------------------------------------")
            Log.d("TestInboundMms.onReceive", "Intent action: ${intent.action}")
            Log.d("TestInboundMms.onReceive", "Intent type: ${intent.type}")
            Log.d("TestInboundMms.onReceive", "Intent extras: ${intent.extras}")
            Log.d("TestInboundMms.onReceive", "Intent extraString: $extraStr")
            Log.d("TestInboundMms.onReceive", "Intent data: ${intent.data}")
            Log.d("TestInboundMms.onReceive", "Intent scheme: ${intent.scheme}")
            Log.d("TestInboundMms.onReceive", "Intent component: ${intent.component}")
            Log.d("TestInboundMms.onReceive", "Intent flags: ${intent.flags}")
            Log.d("TestInboundMms.onReceive", "Intent categories: ${intent.categories}")
            Log.d("TestInboundMms.onReceive", "Intent package: ${intent.`package`}")
            Log.d("TestInboundMms.onReceive", "Intent source bounds: ${intent.sourceBounds}")
            Log.d("TestInboundMms.onReceive", "Intent identifier: ${intent.identifier}")
            Log.d("TestInboundMms.onReceive", "Intent clipData: ${intent.clipData}")
            Log.d("TestInboundMms.onReceive", "Intent package: ${intent.`package`}")
            Log.d("TestInboundMms.onReceive", "Intent dataString: ${intent.dataString}")
            Log.d("TestInboundMms.onReceive", "--------------------------------------")
            Log.d("TestInboundMms.onReceive", "Notification contentLocation: ${notification.contentLocation}")
            Log.d("TestInboundMms.onReceive", "Notification from: ${notification.from?.string ?: ""}")
            Log.d("TestInboundMms.onReceive", "Notification mmsVersion: ${notification.mmsVersion}")
            Log.d("TestInboundMms.onReceive", "Notification transactionId: ${toIsoString(notification.transactionId)}")
            Log.d("TestInboundMms.onReceive", "Notification messageClass: ${String(notification.messageClass)}")
            Log.d("TestInboundMms.onReceive", "Notification messageSize: ${notification.messageSize}")
            Log.d("TestInboundMms.onReceive", "Notification subject: ${notification.subject}")
            Log.d("TestInboundMms.onReceive", "Notification deliveryReport: ${notification.deliveryReport}")
            Log.d("TestInboundMms.onReceive", "Notification expiry: ${notification.expiry}")
            Log.d("TestInboundMms.onReceive", "Notification contentClass: ${notification.contentClass}")
            Log.d("TestInboundMms.onReceive", "Notification from: ${notification.from?.string ?: ""}")
            Log.d("TestInboundMms.onReceive", "Notification javaClass: ${notification.javaClass}")
            Log.d("TestInboundMms.onReceive", "--------------------------------------")

            val contentLocation : ByteArray = notification.contentLocation
            if (contentLocation.isEmpty()) {
                Log.e("InboundMmsHandler", "No contentLocation in MMS NotificationInd PDU!")
                return
            }

            val transactionId = notification.transactionId
            if (transactionId.isEmpty()) {
                Log.e("InboundMmsHandler", "No transactionId in MMS NotificationInd PDU!")
                return
            }

            val contentUri = try {
                String(contentLocation, Charsets.UTF_8) // UTF-8 is safest per MMS spec
            } catch (e: Exception) {
                Log.e("InboundMmsHandler", "Failed to parse contentLocation: $e")
                return
            }

            val transactionIdStr = try {
                String(transactionId, Charsets.UTF_8) // UTF-8 is safest per MMS spec
            } catch (e: Exception) {
                Log.e("InboundMmsHandler", "Failed to parse contentLocation: $e")
                return
            }

            if (contentUri.isBlank()) {
                Log.e("InboundMmsHandler", "contentUri is blank after decoding!")
                return
            }

            // Run off main thread
            Executors.newSingleThreadExecutor().execute {
                startMmsDownload(context, contentUri.trim() + transactionIdStr)
            }

        } else {
            Log.w("TestInboundMms.onReceive", " <<< Received unexpected action: ${intent.action}")
            Log.w("TestInboundMms.onReceive", "--------------------------------------")
            Log.w("TestInboundMms.onReceive", "Intent action: ${intent.action}")
            Log.w("TestInboundMms.onReceive", "Intent type: ${intent.type}")
            Log.w("TestInboundMms.onReceive", "Intent extras: ${intent.extras}")
            Log.w("TestInboundMms.onReceive", "Intent data: ${intent.data}")
            Log.w("TestInboundMms.onReceive", "Intent scheme: ${intent.scheme}")
            Log.w("TestInboundMms.onReceive", "Intent component: ${intent.component}")
            Log.w("TestInboundMms.onReceive", "Intent flags: ${intent.flags}")
            Log.w("TestInboundMms.onReceive", "Intent categories: ${intent.categories}")
            Log.w("TestInboundMms.onReceive", "Intent package: ${intent.`package`}")
            Log.w("TestInboundMms.onReceive", "Intent source bounds: ${intent.sourceBounds}")
            Log.w("TestInboundMms.onReceive", "Intent identifier: ${intent.identifier}")
            Log.w("TestInboundMms.onReceive", "Intent clipData: ${intent.clipData}")
            Log.w("TestInboundMms.onReceive", "Intent package: ${intent.`package`}")
            Log.w("TestInboundMms.onReceive", "Intent dataString: ${intent.dataString}")
            Log.w("TestInboundMms.onReceive", "--------------------------------------")
        }
    }

    private fun startMmsDownload(context: Context, contentUri: String) {
        // Temp file for MMS PDU (FileProvider must be set up in manifest)
        Log.d("startMmsDownload", "--------------------------------------")
        Log.d("startMmsDownload", "Starting MMS download")
        Log.d("startMmsDownload", "Content location URL: $contentUri")
        val tempMmsFile = createTempMmsFile(context)
        Log.d("startMmsDownload", "Temp MMS file: $tempMmsFile")
        val contentFileUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            tempMmsFile
        )
        Log.d("startMmsDownload", "Content URI: $contentFileUri")

        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, MmsDownloadReceiver::class.java).apply {
                Log.d("startMmsDownload", "Intent Content URI: $contentFileUri")
                putExtra("contentFileUri", contentFileUri.toString())
                putExtra("contentUri", contentUri.toString())
                setClass(context, MmsDownloadReceiver::class.java)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d("startMmsDownload", "PendingIntent: $pi")

        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.downloadMultimediaMessage(
            context,
            contentUri,
            contentFileUri,
            null,  // No config overrides needed
            pi
        )
        Log.d("startMmsDownload", "MMS download started")
    }

    private fun createTempMmsFile(context: Context): File {
        // This file will receive the MMS PDU via downloadMultimediaMessage
        return File.createTempFile("mmsdownload", ".dat", context.cacheDir)
    }
}

class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val contentFileUri: Uri? = intent.extras?.getString("contentFileUri")?.toUri()
        if (contentFileUri == null) {
            Log.e("MmsDownloadReceiver.onReceive", " >>> No downloaded MMS content URI!")
            return
        }

        val contentUri: Uri? = intent.extras?.getString("contentUri")?.toUri()
        if (contentUri == null) {
            Log.e("MmsDownloadReceiver.onReceive", "Missing provider download uri - ignoring")
        }

        Telephony.Mms.RETRIEVE_STATUS

        try {
            val pduBytes =
                context.contentResolver.openInputStream(contentFileUri)?.use { it.readBytes() }

            if (pduBytes != null) {
                val pdu = PduParser(pduBytes, true).parse()
                if (pdu is RetrieveConf) {

                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    // Prime Addrs to generate Thread ID
                    val selfNumbers = getSelfNumbers(context)

                    fun cleanAddress(address: String?): String? =
                        address?.let { formatNumber(context, it) }?.takeIf { it.isNotBlank() && it !in selfNumbers }

                    val ccList = pdu.cc?.mapNotNull { cleanAddress(it.string) }?.toSet() ?: emptySet()
                    val toList = pdu.to?.mapNotNull { cleanAddress(it.string) }?.toSet() ?: emptySet()
                    val sender = cleanAddress(pdu.from?.string) ?: ""
                    val participants = (ccList + toList + if (sender.isNotBlank()) setOf(sender) else emptySet())
                        .toSet()

                    // Prime Text Only, and Message Size
                    var textOnly = true
                    var messageSize = 0L
                    for(i in 0 until pdu.body.partsNum) {
                        val part = pdu.body.getPart(i)
                        messageSize += part.data.size
                        if (part.contentType != null && String(part.contentType) != ContentType.TEXT_PLAIN) {
                            textOnly = false
                        }
                    }

                    // Insert MMS
                    val subscriptionId = SmsManager.getDefaultSmsSubscriptionId()
                    val threadId = Telephony.Threads.getOrCreateThreadId(context, participants)

                    val mmsObj = MmsObject.pduToMms(context, pdu, threadId, textOnly, messageSize, subscriptionId)
                    val newMms = MmsDatabaseWriter.insertMms(context, mmsObj).toMutableMap()
                    val newMmsId = newMms["_id"].toString().toLong()

                    // Insert Addrs
                    val charset = pdu.from.characterSet
                    val newAddrs = MmsDatabaseWriter
                        .insertMmsAddrs(context, newMmsId, ccList, toList, sender, charset)
                    newMms.put("recipients", newAddrs)

                    // Insert Parts
                    val pduParts = mutableListOf<MmsPart>()
                    for(i in 0 until pdu.body.partsNum) {
                        val part = MmsPart.pduPartToMmsPart(context, i, pdu.body.getPart(i))
                        pduParts.add(part)
                    }
//                    pduParts.forEach { it.saveMmsPart(context, newMmsId) }
                    val newParts = MmsDatabaseWriter.insertMmsParts(context, newMmsId, pduParts)
                    newMms.put("parts", newParts)

                    // Transfer Message to Dart
                    Log.d("MmsDownloadReceiver.onReceive", " >>> MMS saved to DB")
                    Log.d("MmsDownloadReceiver.onReceive", " >>> Transferring MMS to Dart App")
                    InboundMessaging(context).transferInboundMessage(MessageType.MMS, newMms)

                } else {
                    Log.e(
                        "MmsDownloadReceiver.onReceive",
                        " >>> Unable to parse downloaded MMS PDU"
                    )
                }
            }

            // Clean up temp file
            context.contentResolver.delete(contentFileUri, null, null)
        } catch (e: Exception) {
            Log.e("MmsDownloadReceiver.onReceive", " >>> Error processing downloaded MMS: $e", e)
        }
    }
}

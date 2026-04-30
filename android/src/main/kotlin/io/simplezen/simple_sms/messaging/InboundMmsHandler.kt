package io.simplezen.simple_sms.messaging

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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

            Log.d("InboundMmsHandler", "Received MMS notification, size: ${notification.messageSize}")

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

            // Subscription id of the receiving SIM. The Telephony broadcast
            // populates this via the legacy "subscription" extra. Falls back
            // to the default SMS subscription when the carrier delivers no
            // SIM attribution (typical on single-SIM devices).
            val subId: Int = intent.getIntExtra(
                "subscription",
                SmsManager.getDefaultSmsSubscriptionId()
            )

            val expirySeconds: Long = notification.expiry

            // Run off main thread.
            //
            // NOTE on `contentUri.trim() + transactionIdStr`: this concat is
            // a known defect (S0 #2 in the audit) and corrupts the MMSC URL
            // on some carriers. Step 2 of the port plan rewrites this whole
            // receive flow against `ReceiveMmsMessageAction`; the concat is
            // preserved here unchanged so Step 1 stays scoped to column-write
            // correctness.
            Executors.newSingleThreadExecutor().execute {
                startMmsDownload(
                    context,
                    contentUri.trim() + transactionIdStr,
                    transactionIdStr,
                    subId,
                    expirySeconds,
                )
            }

        } else {
            Log.w("InboundMmsHandler", "Received unexpected action: ${intent.action}")
        }
    }

    private fun startMmsDownload(
        context: Context,
        contentUri: String,
        transactionId: String,
        subId: Int,
        expirySeconds: Long,
    ) {
        Log.d("InboundMmsHandler", "Starting MMS download from: $contentUri")
        val tempMmsFile = createTempMmsFile(context)
        val contentFileUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            tempMmsFile
        )

        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, MmsDownloadReceiver::class.java).apply {
                putExtra("contentFileUri", contentFileUri.toString())
                putExtra("contentUri", contentUri.toString())
                putExtra("transactionId", transactionId)
                putExtra("subId", subId)
                putExtra("expirySeconds", expirySeconds)
                setClass(context, MmsDownloadReceiver::class.java)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.downloadMultimediaMessage(
            context,
            contentUri,
            contentFileUri,
            null,
            pi
        )
        Log.d("InboundMmsHandler", "MMS download initiated")
    }

    private fun createTempMmsFile(context: Context): File {
        // This file will receive the MMS PDU via downloadMultimediaMessage
        return File.createTempFile("mmsdownload", ".dat", context.cacheDir)
    }
}

class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val contentFileUri: Uri? = extras?.getString("contentFileUri")?.toUri()
        if (contentFileUri == null) {
            Log.e(TAG, "No downloaded MMS content URI in broadcast extras")
            return
        }

        val transactionId: String = extras.getString("transactionId").orEmpty()
        val subId: Int = extras.getInt("subId", SmsManager.getDefaultSmsSubscriptionId())
        val expirySeconds: Long = extras.getLong("expirySeconds", -1L)

        try {
            val pduBytes = context.contentResolver
                .openInputStream(contentFileUri)
                ?.use { it.readBytes() }

            if (pduBytes == null) {
                Log.e(TAG, "Failed to read downloaded MMS PDU bytes from $contentFileUri")
                return
            }

            val pdu = PduParser(pduBytes, true).parse()
            if (pdu !is RetrieveConf) {
                Log.e(
                    TAG,
                    "Downloaded PDU is not a RetrieveConf (got ${pdu?.javaClass?.simpleName})"
                )
                return
            }

            // Hand off to the canonical persister. PduPersister handles all
            // column writes (Mms row, addresses, parts including _data
            // streams, thread-id derivation). The persister overlays
            // local DATE / TRANSACTION_ID / EXPIRY post-insert per AOSP
            // MmsUtils.insertReceivedMmsMessage.
            val persisted = InboundMmsPersister.persistRetrievedMms(
                context = context,
                retrieveConf = pdu,
                transactionId = transactionId,
                subId = subId,
                expirySeconds = expirySeconds,
            )

            // Shape for the Dart bridge: the existing transferInboundMessage
            // contract expects the Mms row map with `parts` and `recipients`
            // attached as nested lists.
            val payload = persisted.mmsRow.apply {
                put("parts", persisted.partRows)
                put("recipients", persisted.addrRows)
            }
            Log.d(TAG, "MMS persisted at ${persisted.uri}; transferring to Dart")
            InboundMessaging(context).transferInboundMessage(MessageType.MMS, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing downloaded MMS: $e", e)
        } finally {
            // Always clean up the temp file, even when persist or parse
            // threw. The previous structure left orphans on every parse
            // failure (S1 #9).
            try {
                context.contentResolver.delete(contentFileUri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp MMS file at $contentFileUri", e)
            }
        }
    }

    companion object {
        private const val TAG = "MmsDownloadReceiver"
    }
}

package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WAP_PUSH receiver for inbound MMS.
 *
 * Lifecycle (mirrors AOSP `ReceiveMmsMessageAction`):
 *   1. Carrier delivers `android.provider.Telephony.WAP_PUSH_DELIVER`
 *      with a `NotificationInd` PDU in the `data` extra.
 *   2. We parse the NotificationInd, extract `contentLocation`,
 *      `transactionId`, and `expiry`.
 *   3. We allocate a temp file under our FileProvider authority,
 *      construct a result `PendingIntent` for [MmsDownloadReceiver],
 *      and call `SmsManager.downloadMultimediaMessage` to fetch the
 *      RetrieveConf from the MMSC.
 *   4. SmsManager broadcasts the result; [MmsDownloadReceiver] picks
 *      it up and persists the message via [InboundMmsPersister].
 *
 * Receiver hardening (audit defects S1 #7, #10, #11, #12, #15):
 *   - Each WAP_PUSH gets a unique PendingIntent requestCode derived
 *     from the transaction id, so concurrent inbound MMS no longer
 *     collide on the same PendingIntent slot.
 *   - `goAsync()` keeps the process alive across the dispatch
 *     boundary, so Android can't kill us between `onReceive` returning
 *     and the executor running.
 *   - Single shared executor across all inbound MMS — no per-receive
 *     `newSingleThreadExecutor()` thread leak.
 *   - Only `WAP_PUSH_DELIVER` is registered — `WAP_PUSH_RECEIVED`
 *     fires for ALL apps including the default, but we have no
 *     authority to ack the carrier from the RECEIVED path, so its
 *     filter was removed from the manifest.
 *   - The result `PendingIntent` uses `FLAG_MUTABLE | FLAG_ONE_SHOT` so
 *     SmsManager can populate `EXTRA_MMS_HTTP_STATUS` on Android 12+
 *     while the PendingIntent is invalidated after a single fire.
 */
class InboundMmsHandler() : BroadcastReceiver() {

    companion object {
        private const val TAG = "InboundMmsHandler"

        // Single shared executor across all inbound MMS. Lazily
        // initialized on first use; never shut down — this object
        // lives for the lifetime of the receiver process.
        private val sharedExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "InboundMmsHandler-Executor").apply { isDaemon = true }
            }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> handleDeliver(context, intent)
            else -> Log.w(TAG, "Received unexpected action: ${intent.action}")
        }
    }

    private fun handleDeliver(context: Context, intent: Intent) {
        if (intent.type != "application/vnd.wap.mms-message") {
            Log.w(TAG, "WAP_PUSH_DELIVER with unexpected type: ${intent.type}")
            return
        }

        val pdu = intent.getByteArrayExtra("data") ?: run {
            Log.e(TAG, "WAP_PUSH_DELIVER missing 'data' extra")
            return
        }

        val notification = PduParser(pdu, true).parse()
        if (notification !is NotificationInd) {
            Log.e(TAG, "Failed to parse MMS NotificationInd PDU")
            return
        }

        Log.d(TAG, "Received MMS notification, size=${notification.messageSize}")

        val contentLocationBytes: ByteArray = notification.contentLocation
        if (contentLocationBytes.isEmpty()) {
            Log.e(TAG, "No contentLocation in MMS NotificationInd PDU")
            return
        }

        val transactionIdBytes = notification.transactionId
        if (transactionIdBytes.isEmpty()) {
            Log.e(TAG, "No transactionId in MMS NotificationInd PDU")
            return
        }

        val contentUri = String(contentLocationBytes, Charsets.UTF_8).trim()
        if (contentUri.isBlank()) {
            Log.e(TAG, "contentUri is blank after decoding")
            return
        }

        val transactionIdStr = String(transactionIdBytes, Charsets.UTF_8)

        val subId: Int = intent.getIntExtra(
            "subscription",
            SmsManager.getDefaultSmsSubscriptionId()
        )

        val expirySeconds: Long = notification.expiry

        // Use goAsync() so the process survives long enough to invoke
        // SmsManager.downloadMultimediaMessage. Without this, Android
        // can kill the process between onReceive() returning and the
        // executor's task running, dropping the carrier ack window.
        val pendingResult = goAsync()
        sharedExecutor.execute {
            try {
                startMmsDownload(
                    context = context,
                    // S0 #2: pass the contentLocation URL VERBATIM. The
                    // previous implementation appended `transactionIdStr`
                    // to the URL, corrupting MMSC requests on carriers
                    // that interpret query suffixes (notably T-Mobile).
                    // SmsManager.downloadMultimediaMessage already wires
                    // X-Mms-Transaction-Id into the request headers
                    // internally — manual concat is incorrect.
                    contentUri = contentUri,
                    transactionId = transactionIdStr,
                    subId = subId,
                    expirySeconds = expirySeconds,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startMmsDownload(
        context: Context,
        contentUri: String,
        transactionId: String,
        subId: Int,
        expirySeconds: Long,
    ) {
        Log.d(TAG, "Starting MMS download from: $contentUri (txn=$transactionId, sub=$subId)")
        val tempMmsFile = createTempMmsFile(context)
        val contentFileUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            tempMmsFile
        )

        // S1 #7: unique requestCode per inbound MMS. Two near-
        // simultaneous WAP_PUSHes used to collide on requestCode=0,
        // overwriting each other's extras under FLAG_UPDATE_CURRENT.
        val requestCode = transactionId.hashCode()

        // S1 #15: FLAG_MUTABLE so SmsManager.downloadMultimediaMessage
        // can populate EXTRA_MMS_HTTP_STATUS and EXTRA_MMS_DATA on
        // Android 12+. AOSP MmsService uses the mutable variant for
        // this exact pattern.
        //
        // FLAG_ONE_SHOT pairs with FLAG_MUTABLE as defense in depth:
        // once SmsManager fires the result broadcast, the PendingIntent
        // is invalidated, so a mutable PendingIntent can't be retained
        // or reused by another component. AOSP's RetrieveTransaction
        // uses the same flag combination.
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MmsDownloadReceiver::class.java).apply {
                putExtra("contentFileUri", contentFileUri.toString())
                putExtra("contentUri", contentUri)
                putExtra("transactionId", transactionId)
                putExtra("subId", subId)
                putExtra("expirySeconds", expirySeconds)
                setClass(context, MmsDownloadReceiver::class.java)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_MUTABLE or
                PendingIntent.FLAG_ONE_SHOT
        )

        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.downloadMultimediaMessage(
            context,
            contentUri,
            contentFileUri,
            null,
            pi
        )
        Log.d(TAG, "MMS download initiated")
    }

    private fun createTempMmsFile(context: Context): File =
        File.createTempFile("mmsdownload", ".dat", context.cacheDir)
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

        // Check the SmsManager.downloadMultimediaMessage result BEFORE
        // attempting to parse. Pre-fix, a failed download (network
        // unavailable, MMSC HTTP 4xx/5xx, APN misconfiguration) wrote
        // an empty or error-payload temp file and we'd hit
        //   "Downloaded PDU is not a RetrieveConf (got null)"
        // every time, with no diagnostic for what actually failed.
        //
        // - `getResultCode()` is set to Activity.RESULT_OK on success,
        //   otherwise a SmsManager.MMS_ERROR_* sentinel.
        // - `EXTRA_MMS_HTTP_STATUS` is populated on Android 7.1+ with
        //   the underlying HTTP status when the failure was HTTP-level.
        val resultCode = resultCode
        // Telephony.Mms.Intents.EXTRA_MMS_HTTP_STATUS — accessed via the
        // string literal so the build doesn't tie its compileSdk to the
        // API 25+ visibility of the constant.
        val httpStatus = extras.getInt(
            "android.provider.extra.MMS_HTTP_STATUS",
            -1
        )
        if (resultCode != Activity.RESULT_OK) {
            Log.e(
                TAG,
                "MMS download failed: resultCode=$resultCode httpStatus=$httpStatus " +
                    "txn=$transactionId sub=$subId. " +
                    "Common causes: MMS APN not configured for this SIM; " +
                    "mobile data or roaming disabled; carrier MMSC unreachable. " +
                    "Skipping parse; temp file will be cleaned up."
            )
            // Bail before parsing — the temp file either contains an
            // error response from the MMSC or is empty.
            try {
                context.contentResolver.delete(contentFileUri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp MMS file at $contentFileUri", e)
            }
            return
        }

        try {
            val pduBytes = context.contentResolver
                .openInputStream(contentFileUri)
                ?.use { it.readBytes() }

            if (pduBytes == null || pduBytes.isEmpty()) {
                Log.e(
                    TAG,
                    "Downloaded MMS PDU is empty (resultCode=$resultCode " +
                        "httpStatus=$httpStatus). Carrier returned no body — likely a " +
                        "stub MMSC response. Skipping."
                )
                return
            }

            val pdu = PduParser(pduBytes, true).parse()
            if (pdu !is RetrieveConf) {
                Log.e(
                    TAG,
                    "Downloaded PDU is not a RetrieveConf (got " +
                        "${pdu?.javaClass?.simpleName} from ${pduBytes.size} bytes; " +
                        "resultCode=$resultCode httpStatus=$httpStatus)"
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

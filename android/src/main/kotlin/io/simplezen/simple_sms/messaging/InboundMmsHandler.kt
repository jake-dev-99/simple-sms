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
 * Fill in Verizon's *template* MMSC content-location.
 *
 * Verizon's MMSC sends a NotificationInd whose `contentLocation` ends with an
 * empty `?message-id=` query parameter the client is expected to fill in, e.g.
 * `http://63.59.x.x/servlets/mms?message-id=`. Without a value the MMSC returns
 * an error (or never produces a result broadcast on some Android versions). We
 * append the WAP-PUSH `transactionId` — the only message-unique token our PDU
 * surface exposes (NotificationInd carries no X-Mms-Message-ID header in
 * `pdu_alt`); Verizon parses the query loosely and uses any unique value to
 * disambiguate pending downloads.
 *
 * Narrow heuristic: only touch URLs that look like a Verizon template (end with
 * `?message-id=`, or a bare `=`). Carriers that send a complete URL (T-Mobile,
 * AT&T, Google Fi) are unaffected — they either don't match, or their MMSC
 * ignores the trailing token. (Removing this concat — audit S0 #2 / PR #59, on
 * the theoretical grounds it would corrupt T-Mobile — empirically broke Verizon
 * inbound MMS; this is the restored, scoped form.)
 */
internal fun resolveVerizonDownloadUrl(contentLocation: String, transactionId: String): String =
    if (contentLocation.endsWith("?message-id=") || contentLocation.endsWith("=")) {
        contentLocation + transactionId
    } else {
        contentLocation
    }

/**
 * The WAP-push broadcast actions [InboundMmsHandler] is registered for. Routing
 * is a pure, unit-tested function rather than inline `when` logic (mirrors
 * [resolveVerizonDownloadUrl]) so the de-dup decision below is a visible, tested
 * choice (UNFY-161).
 */
internal enum class WapAction {
    /** `WAP_PUSH_DELIVER` — the default-app authoritative path: download + persist. */
    Deliver,

    /**
     * `WAP_PUSH_RECEIVED` — a public broadcast (any app with BROADCAST_WAP_PUSH).
     * An *expected* duplicate of the DELIVER path, deliberately ignored (de-dup),
     * **not** an error. See the [InboundMmsHandler] KDoc for the full rationale.
     */
    ReceivedIgnored,

    /** Any other action — a real registration/routing bug worth a warning. */
    Unexpected,
}

internal fun classifyWapPushAction(action: String?): WapAction = when (action) {
    "android.provider.Telephony.WAP_PUSH_DELIVER" -> WapAction.Deliver
    "android.provider.Telephony.WAP_PUSH_RECEIVED" -> WapAction.ReceivedIgnored
    else -> WapAction.Unexpected
}

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
 *   - Both `WAP_PUSH_DELIVER` and `WAP_PUSH_RECEIVED` are registered
 *     in the manifest, and handled deliberately (not interchangeably):
 *       · `WAP_PUSH_DELIVER` is delivered ONLY to the default SMS app
 *         and is the authoritative inbound-MMS path (download + persist).
 *       · `WAP_PUSH_RECEIVED` is a PUBLIC broadcast — any app holding
 *         `BROADCAST_WAP_PUSH` receives it. While we are the default app
 *         the MMS already arrives via DELIVER, so a RECEIVED is a
 *         duplicate and is deliberately ignored (de-dup) to avoid a
 *         double download + double persist. When we are NOT the default
 *         app we receive RECEIVED but not DELIVER, and we hold no
 *         authority to ack the carrier / call
 *         `downloadMultimediaMessage` from that path — so there is
 *         nothing actionable here either. (Surfacing a non-default
 *         inbound MMS, if ever wanted, is a consumer/unify concern, not
 *         this receiver's.)
 *     Routing lives in [classifyWapPushAction] / [WapAction] so the
 *     de-dup decision is unit-tested (UNFY-161). Before this fix RECEIVED
 *     fell into the `else` branch and logged `W/"unexpected action"` on
 *     every inbound MMS, and this comment falsely claimed the RECEIVED
 *     filter had been removed from the manifest.
 *   - The result `PendingIntent` uses `FLAG_ONE_SHOT | FLAG_IMMUTABLE`
 *     — the AOSP `RetrieveTransaction` canonical pair. ONE_SHOT
 *     invalidates the PendingIntent the moment SmsManager fires the
 *     result broadcast; IMMUTABLE locks the stashed intent. SmsManager
 *     still delivers result extras (`EXTRA_MMS_HTTP_STATUS`, etc.) by
 *     merging an `extrasIntent` at `pendingIntent.send(ctx, code,
 *     extrasIntent)` time — IMMUTABLE doesn't block that path.
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
        when (classifyWapPushAction(intent.action)) {
            WapAction.Deliver -> handleDeliver(context, intent)
            WapAction.ReceivedIgnored ->
                // Expected duplicate of the DELIVER path (see [WapAction]): the
                // MMS is downloaded + persisted from WAP_PUSH_DELIVER while we
                // are the default app, so RECEIVED is intentionally a no-op to
                // avoid a double download + double persist. Debug, not warn — it
                // fires on every inbound MMS and is not an error.
                Log.d(TAG, "WAP_PUSH_RECEIVED ignored (de-dup of WAP_PUSH_DELIVER)")
            WapAction.Unexpected ->
                Log.w(TAG, "Received unexpected action: ${intent.action}")
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

        // `contentLocation`/`transactionId` are now explicitly nullable on the
        // Kotlin NotificationInd (the vendored getters returned platform-type
        // byte[] that this call site already treated as non-null); `!!`
        // preserves that prior non-null treatment (same NPE-if-null at runtime).
        val contentLocationBytes: ByteArray = notification.contentLocation!!
        if (contentLocationBytes.isEmpty()) {
            Log.e(TAG, "No contentLocation in MMS NotificationInd PDU")
            return
        }

        val transactionIdBytes = notification.transactionId!!
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
        // Fill in Verizon's template MMSC URL (carrier rationale +
        // heuristic documented on resolveVerizonDownloadUrl).
        val downloadContentUri = resolveVerizonDownloadUrl(contentUri, transactionIdStr)

        sharedExecutor.execute {
            try {
                startMmsDownload(
                    context = context,
                    contentUri = downloadContentUri,
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
        // Resolve to the canonical path before handing to FileProvider — its
        // configured roots are stored canonically, so a non-canonical input
        // (e.g. /data/data/<pkg>/... when the resolved root is
        // /data/user/0/<pkg>/...) walks past every configured entry and
        // throws IllegalArgumentException. `canonicalFile` itself can throw
        // IOException (filesystem in a bad state); fall back to
        // `absoluteFile` rather than crashing the receiver — a non-canonical
        // path may still match if the device doesn't symlink /data/data →
        // /data/user/0, and the IllegalArgumentException catch below covers
        // the case where it doesn't.
        val rawTempMmsFile = createTempMmsFile(context)
        val tempMmsFile = try {
            rawTempMmsFile.canonicalFile
        } catch (e: java.io.IOException) {
            Log.w(
                TAG,
                "canonicalFile failed for ${rawTempMmsFile.absolutePath}; " +
                    "falling back to absoluteFile",
                e
            )
            rawTempMmsFile.absoluteFile
        }
        // Defensive: even with file_paths.xml covering every storage type,
        // any future provider misconfiguration should degrade to "no inbound
        // MMS" rather than crashing the receiver process. ART marks the
        // process "crashed too many times" after a few rapid receiver crashes
        // and Android then suppresses subsequent WAP_PUSH deliveries until
        // reinstall.
        val contentFileUri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                tempMmsFile
            )
        } catch (e: IllegalArgumentException) {
            Log.e(
                TAG,
                "FileProvider.getUriForFile failed for ${tempMmsFile.absolutePath}; " +
                    "MMS download aborted. Check file_paths.xml cache-path coverage.",
                e
            )
            return
        }
        Log.d(
            TAG,
            "Starting MMS download from: $contentUri " +
                "(txn=$transactionId, sub=$subId, tempFile=${tempMmsFile.absolutePath})"
        )

        // S1 #7: unique requestCode per inbound MMS. Two near-
        // simultaneous WAP_PUSHes used to collide on requestCode=0,
        // overwriting each other's extras.
        val requestCode = transactionId.hashCode()

        // PendingIntent flags match AOSP `RetrieveTransaction` for this
        // exact use case: `FLAG_ONE_SHOT | FLAG_IMMUTABLE`.
        //
        // - FLAG_ONE_SHOT: invalidates the PendingIntent the moment
        //   SmsManager fires the result broadcast. Carrier ack happens
        //   inside that broadcast — defense in depth against any reuse.
        // - FLAG_IMMUTABLE: the PendingIntent's stashed Intent is
        //   sealed. SmsManager populates result extras (resultCode,
        //   EXTRA_MMS_HTTP_STATUS, etc.) by calling
        //   `pendingIntent.send(ctx, code, extrasIntent)` — that
        //   `extrasIntent` is merged into the outgoing broadcast and
        //   delivered to MmsDownloadReceiver, regardless of mutability.
        //   IMMUTABLE just blocks the PendingIntent's stashed Intent
        //   from being modified directly by any caller.
        //
        // Pre-fix the flags were `UPDATE_CURRENT | MUTABLE | ONE_SHOT`
        // — UPDATE_CURRENT was pointless under unique requestCodes,
        // and MUTABLE was based on a misreading of how SmsManager
        // delivers result extras (it doesn't need a mutable
        // PendingIntent to do so). The mismatch matters in practice:
        // some Android versions / OEMs reject or silently drop
        // unusual flag combinations, leaving the result PendingIntent
        // never delivered — causing every download to "succeed at
        // initiation" but never produce a result broadcast for
        // MmsDownloadReceiver. AOSP's canonical pair is the safe
        // baseline.
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
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.downloadMultimediaMessage(
            context,
            contentUri,
            contentFileUri,
            null,
            pi
        )
        Log.d(TAG, "MMS download initiated (awaiting result broadcast for txn=$transactionId)")
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

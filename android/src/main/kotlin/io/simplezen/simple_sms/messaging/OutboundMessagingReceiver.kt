package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.net.toUri
import io.simplezen.simple_sms.mms.sending.MmsSentReceiver
import java.io.File

/**
 * The [Telephony.Sms] delivery status (`STATUS_COMPLETE` / `STATUS_PENDING` /
 * `STATUS_FAILED`) carried by an SMS status report, or null when the report is
 * missing, unparseable, not a status report, or carries an unknown status
 * (UNFY-293). Mirrors AOSP Messaging's handling of the delivery `PendingIntent`
 * extras (`pdu`, `format`). Every null is logged with its reason.
 */
internal fun smsDeliveryStatus(pdu: ByteArray?, format: String?): Int? {
    if (pdu == null || format == null) {
        Log.w(TAG_RECEIVER, "Delivery report without pdu/format extras (pdu=${pdu != null}, format=$format)")
        return null
    }
    // Parsing runs inside onReceive; a platform/OEM parser failure must not
    // crash the process (UNFY-282 containment).
    val report = try {
        SmsMessage.createFromPdu(pdu, format)
    } catch (e: RuntimeException) {
        Log.w(TAG_RECEIVER, "Delivery report PDU failed to parse ($format)", e)
        return null
    }
    if (report == null) {
        Log.w(TAG_RECEIVER, "Delivery report PDU did not parse ($format, ${pdu.size} bytes)")
        return null
    }
    val status = try {
        if (!report.isStatusReportMessage) {
            Log.w(TAG_RECEIVER, "Delivery report PDU is not a status report ($format)")
            return null
        }
        report.status
    } catch (e: RuntimeException) {
        Log.w(TAG_RECEIVER, "Delivery report status unreadable ($format)", e)
        return null
    }
    val mapped = deliveryStatusFromReport(status, format)
    if (mapped == null) Log.w(TAG_RECEIVER, "Unknown delivery status 0x${Integer.toHexString(status)} ($format)")
    return mapped
}

/**
 * Maps a status report's raw status to a [Telephony.Sms] delivery status.
 *
 * 3GPP: the TP-Status ranges of TS 23.040 (completed below
 * [TP_STATUS_TRYING], still trying below [TP_STATUS_FAILED], failed above).
 * 3GPP2: the platform packs the bearer-data error class and message status
 * into bits 24–31 and 16–23. A delivery acknowledgment with no error is a
 * delivery (the message status is an optional subparameter, reported as 0
 * when absent), unless its status says "cancelled"; a temporary error is
 * pending and a permanent one failed. Undefined or unknown values map to null
 * rather than a guess.
 */
internal fun deliveryStatusFromReport(status: Int, format: String): Int? {
    if (format != SmsMessage.FORMAT_3GPP2) {
        return when {
            status < TP_STATUS_TRYING -> Telephony.Sms.STATUS_COMPLETE
            status < TP_STATUS_FAILED -> Telephony.Sms.STATUS_PENDING
            else -> Telephony.Sms.STATUS_FAILED
        }
    }
    val errorClass = (status shr 24) and 0xFF
    val messageStatus = (status shr 16) and 0xFF
    if (errorClass == CDMA_UNDEFINED || messageStatus == CDMA_UNDEFINED) return null
    return when (errorClass) {
        CDMA_ERROR_NONE ->
            if (messageStatus == CDMA_STATUS_CANCELLED) Telephony.Sms.STATUS_FAILED
            else Telephony.Sms.STATUS_COMPLETE
        CDMA_ERROR_TEMPORARY -> Telephony.Sms.STATUS_PENDING
        CDMA_ERROR_PERMANENT -> Telephony.Sms.STATUS_FAILED
        else -> null
    }
}

/**
 * Which rows a delivery report may write (UNFY-293):
 * - not already `STATUS_FAILED`: a failed part is terminal for the whole
 *   message, so a multipart SMS whose later parts report delivered stays
 *   failed;
 * - an outbound row whose send was not marked failed (sent, outbox or
 *   queued), so a late report for a deleted message never stamps an inbound
 *   row that reused its id.
 */
internal const val DELIVERY_REPORT_SELECTION =
    "(${Telephony.Sms.STATUS} IS NULL OR ${Telephony.Sms.STATUS} != ?) " +
        "AND ${Telephony.Sms.TYPE} IN (?, ?, ?)"
internal val DELIVERY_REPORT_ARGS = arrayOf(
    Telephony.Sms.STATUS_FAILED.toString(),
    Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
    Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString(),
    Telephony.Sms.MESSAGE_TYPE_QUEUED.toString(),
)

/** Records an SMS delivery report's [status] on [messageUri] (see [DELIVERY_REPORT_SELECTION]). */
internal fun persistSmsDeliveryStatus(context: Context, messageUri: Uri, status: Int): Int =
    context.contentResolver.update(
        messageUri,
        ContentValues().apply { put(Telephony.Sms.STATUS, status) },
        DELIVERY_REPORT_SELECTION,
        DELIVERY_REPORT_ARGS,
    )

/**
 * Deletes the encoded PDU an outbound MMS was sent from, once its SENT result
 * has arrived: the platform has finished reading it, success or failure
 * (UNFY-311). Only a `send.*.dat` file directly in [Context.getCacheDir] —
 * where `Transaction` writes it — is deleted; anything else is logged and
 * left alone. Returns whether a file was deleted.
 */
internal fun deleteMmsSendFile(context: Context, path: String?): Boolean {
    if (path == null) {
        Log.w(TAG_RECEIVER, "MMS SENT result without a send-file path; nothing to delete.")
        return false
    }
    val file = File(path)
    val inCache = file.parentFile?.canonicalFile == context.cacheDir.canonicalFile
    if (!inCache || !file.name.startsWith("send.") || !file.name.endsWith(".dat")) {
        Log.e(TAG_RECEIVER, "Refusing to delete $path: not an MMS send file in the cache directory.")
        return false
    }
    if (!file.delete()) {
        Log.w(TAG_RECEIVER, "MMS send file $path was not deleted (exists=${file.exists()}).")
        return false
    }
    return true
}

// TP-Status range boundaries (3GPP TS 23.040 9.2.3.15).
private const val TP_STATUS_TRYING = 0x20
private const val TP_STATUS_FAILED = 0x40

// CDMA bearer-data values (3GPP2 C.S0015-B 4.5.21), as the platform reports them.
private const val CDMA_ERROR_NONE = 0
private const val CDMA_ERROR_TEMPORARY = 2
private const val CDMA_ERROR_PERMANENT = 3
private const val CDMA_STATUS_CANCELLED = 3
private const val CDMA_UNDEFINED = 0xFF

private const val TAG_RECEIVER = "OutboundMessagingReceiver"

/**
 * Receives outbound status broadcasts. Declared in the manifest, and every
 * status intent targets it explicitly, so Android delivers them even when the
 * app process is cached or has gone since the send started (UNFY-293,
 * UNFY-306).
 *
 * - SENT results are recorded on the message row, and settle the send if it
 *   is still pending in this process (see [OutboundSendResults]).
 * - SMS delivery reports only record the parsed status on the row named by
 *   the intent data (UNFY-293).
 */
class OutboundMessagingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) {
            Log.e(TAG_RECEIVER, "Received null intent or context.")
            return
        }
        val currentResultCode = resultCode // This is the result code from the broadcast
        val eventType = intent.action ?: run {
            Log.w(TAG_RECEIVER, "Received a broadcast with no action.")
            return
        }
        val statusString = when (eventType) {
            DELIVEREDSMS_ACTION -> {
                onDeliveryReport(context, intent)
                return
            }
            SENTSMS_ACTION -> getSentStatusString(currentResultCode)
            SENTMMS_ACTION -> getMmsSentStatusString(currentResultCode)
            else -> {
                Log.w(TAG_RECEIVER, "Received unknown action: $eventType")
                return
            }
        }

        val messageId = intent.getIntExtra("messageID", -1)
        if (messageId == -1) {
            Log.e(TAG_RECEIVER, "Received $eventType broadcast with no messageID.")
            return
        }
        val messageUri = intent.getStringExtra("uri")?.toUri() ?: Uri.EMPTY
        Log.d(TAG_RECEIVER, "MessageID: $messageId, Action: $eventType, ResultCode: $currentResultCode, Status: $statusString")
        OutboundSendResults.onSentBroadcast(context, messageId, messageUri, eventType, statusString, currentResultCode)
        if (eventType == SENTMMS_ACTION) {
            deleteMmsSendFile(context, intent.getStringExtra(MmsSentReceiver.EXTRA_FILE_PATH))
        }
    }

    /**
     * Records an SMS delivery report on its row. The row comes from the intent
     * data, which the platform's fill-in cannot replace (no `FILL_IN_DATA`).
     * Failures are logged, never thrown: nothing is pending on a report.
     */
    private fun onDeliveryReport(context: Context, intent: Intent) {
        val messageUri = intent.data
        if (messageUri == null) {
            Log.e(TAG_RECEIVER, "Delivery report without a message URI.")
            return
        }
        val status = smsDeliveryStatus(intent.getByteArrayExtra("pdu"), intent.getStringExtra("format"))
        if (status == null) {
            Log.w(TAG_RECEIVER, "No delivery status in the report for $messageUri; row left unchanged.")
            return
        }
        try {
            val updated = persistSmsDeliveryStatus(context, messageUri, status)
            Log.d(TAG_RECEIVER, "Delivery ${getSmsDeliveryStatusString(status)} for $messageUri (rows=$updated)")
        } catch (e: Exception) {
            Log.e(TAG_RECEIVER, "Failed recording the delivery status for $messageUri", e)
        }
    }

    private fun getSentStatusString(resultCode: Int): String {

        return when (resultCode) {
            Activity.RESULT_OK -> "SENT_SUCCESS"
            SmsManager.RESULT_BLUETOOTH_DISCONNECTED -> "ERROR_BLUETOOTH_DISCONNECTED"
            SmsManager.RESULT_CANCELLED -> "ERROR_CANCELLED"
            SmsManager.RESULT_ENCODING_ERROR -> "ERROR_ENCODING"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "ERROR_FDN_CHECK_FAILURE"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "ERROR_GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "ERROR_LIMIT_EXCEEDED"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "ERROR_NO_SERVICE"
            SmsManager.RESULT_ERROR_NULL_PDU -> "ERROR_NULL_PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "ERROR_RADIO_OFF"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "ERROR_SHORT_CODE_NEVER_ALLOWED"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "ERROR_SHORT_CODE_NOT_ALLOWED"
            SmsManager.RESULT_INTERNAL_ERROR -> "ERROR_INTERNAL"
            SmsManager.RESULT_INVALID_ARGUMENTS -> "ERROR_INVALID_ARGUMENTS"
            SmsManager.RESULT_INVALID_BLUETOOTH_ADDRESS -> "ERROR_INVALID_BLUETOOTH_ADDRESS"
            SmsManager.RESULT_INVALID_SMSC_ADDRESS -> "ERROR_INVALID_SMSC_ADDRESS"
            SmsManager.RESULT_INVALID_SMS_FORMAT -> "ERROR_INVALID_SMS_FORMAT"
            SmsManager.RESULT_INVALID_STATE -> "ERROR_INVALID_STATE"
            SmsManager.RESULT_MODEM_ERROR -> "ERROR_MODEM"
            SmsManager.RESULT_NETWORK_ERROR -> "ERROR_NETWORK"
            SmsManager.RESULT_NETWORK_REJECT -> "ERROR_NETWORK_REJECT"
            SmsManager.RESULT_NO_BLUETOOTH_SERVICE -> "ERROR_NO_BLUETOOTH_SERVICE"
            SmsManager.RESULT_NO_DEFAULT_SMS_APP -> "ERROR_NO_DEFAULT_SMS_APP"
            SmsManager.RESULT_NO_MEMORY -> "RESULT_NO_MEMORY"
            SmsManager.RESULT_NO_RESOURCES -> "ERROR_NO_RESOURCES"
            SmsManager.RESULT_OPERATION_NOT_ALLOWED -> "ERROR_OPERATION_NOT_ALLOWED"
            SmsManager.RESULT_RADIO_NOT_AVAILABLE -> "ERROR_RADIO_NOT_AVAILABLE"
            SmsManager.RESULT_REMOTE_EXCEPTION -> "ERROR_REMOTE_EXCEPTION"
            SmsManager.RESULT_REQUEST_NOT_SUPPORTED -> "ERROR_REQUEST_NOT_SUPPORTED"
            SmsManager.RESULT_RIL_ABORTED -> "ERROR_RIL_ABORTED"
            SmsManager.RESULT_RIL_ACCESS_BARRED -> "ERROR_RIL_ACCESS_BARRED"
            SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL -> "ERROR_RIL_BLOCKED_DUE_TO_CALL"
            SmsManager.RESULT_RIL_CANCELLED -> "ERROR_RIL_CANCELLED"
            SmsManager.RESULT_RIL_ENCODING_ERR -> "ERROR_RIL_ENCODING_ERR"
            SmsManager.RESULT_RIL_INTERNAL_ERR -> "ERROR_RIL_INTERNAL"
            SmsManager.RESULT_RIL_INVALID_ARGUMENTS -> "ERROR_RIL_INVALID_ARGUMENTS"
            SmsManager.RESULT_RIL_INVALID_MODEM_STATE -> "ERROR_RIL_INVALID_MODEM_STATE"
            SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS -> "ERROR_RIL_INVALID_SMSC_ADDRESS"
            SmsManager.RESULT_RIL_INVALID_SMS_FORMAT -> "ERROR_RIL_INVALID_SMS_FORMAT"
            SmsManager.RESULT_RIL_INVALID_STATE -> "ERROR_RIL_INVALID_STATE"
            SmsManager.RESULT_RIL_MODEM_ERR -> "ERROR_RIL_MODEM_ERR"
            SmsManager.RESULT_RIL_NETWORK_ERR -> "ERROR_RIL_NETWORK_ERR"
            SmsManager.RESULT_RIL_NETWORK_NOT_READY -> "ERROR_RIL_NETWORK_NOT_READY"
            SmsManager.RESULT_RIL_NETWORK_REJECT -> "ERROR_RIL_NETWORK_REJECT"
            SmsManager.RESULT_RIL_NO_MEMORY -> "ERROR_RIL_NO_MEMORY"
            SmsManager.RESULT_RIL_NO_RESOURCES -> "ERROR_RIL_NO_RESOURCES"
            SmsManager.RESULT_RIL_NO_SMS_TO_ACK-> "ERROR_RIL_NO_SMS_TO_ACK"
            SmsManager.RESULT_RIL_NO_SUBSCRIPTION -> "ERROR_RIL_NO_SUBSCRIPTION"
            SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED -> "ERROR_RIL_OPERATION_NOT_ALLOWED"
            SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE -> "ERROR_RIL_RADIO_NOT_AVAILABLE"
            SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED -> "ERROR_RIL_REQUEST_NOT_SUPPORTED"
            SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED -> "ERROR_RIL_REQUEST_RATE_LIMITED"
            SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED -> "ERROR_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED"
            SmsManager.RESULT_RIL_SIM_ABSENT -> "ERROR_RIL_SIM_ABSENT"
            SmsManager.RESULT_RIL_SIM_BUSY -> "ERROR_RIL_SIM_BUSY"
            SmsManager.RESULT_RIL_SIM_FULL -> "ERROR_RIL_SIM_FULL"
            SmsManager.RESULT_RIL_SIM_PIN2 -> "ERROR_RIL_SIM_PIN2"
            SmsManager.RESULT_RIL_SIM_PUK2 -> "ERROR_RIL_SIM_PUK2"
            SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY -> "ERROR_RIL_SMS_SEND_FAIL_RETRY"
            SmsManager.RESULT_RIL_SYSTEM_ERR -> "ERROR_RIL_SYSTEM_ERR"
            SmsManager.RESULT_SMS_BLOCKED_DURING_EMERGENCY -> "ERROR_SMS_BLOCKED_DURING_EMERGENCY"
            SmsManager.RESULT_SMS_SEND_RETRY_FAILED -> "ERROR_SMS_SEND_RETRY_FAILED"
            SmsManager.RESULT_SYSTEM_ERROR -> "ERROR_SYSTEM_ERROR"
            SmsManager.RESULT_UNEXPECTED_EVENT_STOP_SENDING -> "ERROR_UNEXPECTED_EVENT_STOP_SENDING"
            else -> "SENT_UNKNOWN_ERROR ($resultCode)"
        }
    }

    private fun getSmsDeliveryStatusString(deliveryStatus: Int?): String = when (deliveryStatus) {
        Telephony.Sms.STATUS_COMPLETE -> "DELIVERED_OK"
        Telephony.Sms.STATUS_PENDING -> "DELIVERY_PENDING"
        Telephony.Sms.STATUS_FAILED -> "DELIVERY_FAILED"
        else -> "DELIVERY_STATUS_UNKNOWN"
    }

    // MMS *sent* result codes are SmsManager.MMS_ERROR_* values, a different
    // enum from both the SMS sent (RESULT_*) and the delivery enums — they
    // collide numerically (e.g. SMS RESULT 5 vs MMS_ERROR_IO_ERROR 5), so MMS
    // needs its own mapping. RESULT_OK is success; anything else is a send
    // failure (UNFY-178).
    internal fun getMmsSentStatusString(resultCode: Int): String {
        return when (resultCode) {
            Activity.RESULT_OK -> "SENT_SUCCESS"
            SmsManager.MMS_ERROR_UNSPECIFIED -> "ERROR_MMS_UNSPECIFIED"
            SmsManager.MMS_ERROR_INVALID_APN -> "ERROR_MMS_INVALID_APN"
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "ERROR_MMS_UNABLE_TO_CONNECT"
            SmsManager.MMS_ERROR_HTTP_FAILURE -> "ERROR_MMS_HTTP_FAILURE"
            SmsManager.MMS_ERROR_IO_ERROR -> "ERROR_MMS_IO_ERROR"
            SmsManager.MMS_ERROR_RETRY -> "ERROR_MMS_RETRY"
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "ERROR_MMS_CONFIGURATION"
            SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "ERROR_MMS_NO_DATA_NETWORK"
            SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID -> "ERROR_MMS_INVALID_SUBSCRIPTION_ID"
            SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION -> "ERROR_MMS_INACTIVE_SUBSCRIPTION"
            SmsManager.MMS_ERROR_DATA_DISABLED -> "ERROR_MMS_DATA_DISABLED"
            SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER -> "ERROR_MMS_DISABLED_BY_CARRIER"
            else -> "MMS_SENT_UNKNOWN_ERROR ($resultCode)"
        }
    }
}

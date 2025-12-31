package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.net.toUri

class OutboundMessagingReceiver(
    // Callback to notify the handler with messageId, event type, status string, and raw resultCode
    private var onStatusUpdate: ((messageId: Int, messageUri: Uri, eventType: String, statusString: String, resultCode: Int) -> Unit)? = null
) : BroadcastReceiver() {

    // Public no-arg constructor required for framework instantiation
    constructor() : this(null)

    companion object {
        const val SENTSMS_ACTION = "com.simplezen.simple_sms.SMS_SENT"
        const val SENTMMS_ACTION = "com.simplezen.simple_sms.MMS_SENT"
        const val DELIVEREDSMS_ACTION = "com.simplezen.simple_sms.SMS_DELIVERED"
        const val DELIVEREDMMS_ACTION = "com.simplezen.simple_sms.MMS_DELIVERED"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) {
            Log.e("OutboundMessagingReceiver", "Received null intent or context.")
            return
        }

        for(extra in intent.extras?.keySet() ?: emptySet()) {
            val temp = intent.extras?.get(extra)
            val type = temp?.javaClass?.simpleName
            Log.d("OutboundMessagingReceiver", "Extra: $extra = ${intent.extras?.get(extra)} (type: $type)")
        }

        val messageId = intent.getIntExtra("messageID", -1)
        if (messageId == -1) {
            Log.e("OutboundMessagingReceiver", "Received broadcast with no messageID.")
            return
        }

        val currentResultCode = resultCode // This is the result code from the broadcast
        val messageUri = intent.getStringExtra("uri")?.toUri() ?: Uri.EMPTY
        val eventType: String
        val statusString: String

        when (intent.action) {
            SENTSMS_ACTION -> {
                eventType = SENTSMS_ACTION
                statusString = getSentStatusString(currentResultCode)
            }
            DELIVEREDSMS_ACTION -> {
                eventType = DELIVEREDSMS_ACTION
                statusString = getDeliveredStatusString(currentResultCode)
            }
            SENTMMS_ACTION -> {
                eventType = SENTMMS_ACTION
                statusString = getDeliveredStatusString(currentResultCode)
            }
            DELIVEREDMMS_ACTION -> {
                eventType = DELIVEREDMMS_ACTION
                statusString = getDeliveredStatusString(currentResultCode)
            }
            else -> {
                eventType = "UNKNOWN_ACTION"
                statusString = "Unknown action: ${intent.action}"
                Log.w("OutboundMessagingReceiver", "Received unknown action: ${intent.action}")
            }
        }
        Log.d("OutboundMessagingReceiver", "MessageID: $messageId, Action: ${intent.action}, ResultCode: $currentResultCode, Status: $statusString")
    // Invoke callback if provided
    onStatusUpdate?.invoke(messageId, messageUri, eventType, statusString, currentResultCode)
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

    private fun getDeliveredStatusString(resultCode: Int): String {
        return when (resultCode) {
            Activity.RESULT_OK -> "DELIVERED_OK"
            Activity.RESULT_CANCELED -> "DELIVERED_FAILED" // Often used for failure
            else -> "DELIVERED_STATUS_UNKNOWN ($resultCode)"
        }
    }
}

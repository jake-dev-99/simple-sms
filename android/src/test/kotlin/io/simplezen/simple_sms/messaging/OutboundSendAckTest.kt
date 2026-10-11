package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Send-status ack golden (UNFY-160, UNFY-282, UNFY-283).
 *
 * Drives the manifest-declared [OutboundMessagingReceiver] with status
 * broadcasts, against fake `sms` / `mms` providers, and pins how the pending
 * Flutter result in [OutboundSendResults] is settled:
 * exactly once — after every SENT part succeeded, or at the first failed
 * part; with the re-read row when it exists, [ERR_MESSAGE_ROW_MISSING] —
 * never a URI-only success — when it doesn't, and [ERR_SEND_ACK_FAILED] when
 * the ack can't be built.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSendAckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val smsUri: Uri = Uri.parse("content://sms/42")
    private val mmsUri: Uri = Uri.parse("content://mms/7")
    private lateinit var smsProvider: FakeMessageProvider
    private lateinit var mmsProvider: FakeMessageProvider
    private val pending = OutboundSendResults.pending

    @Before
    fun setUp() {
        smsProvider = Robolectric.buildContentProvider(FakeMessageProvider::class.java).create("sms").get()
        mmsProvider = Robolectric.buildContentProvider(FakeMessageProvider::class.java).create("mms").get()
    }

    @After
    fun tearDown() {
        pending.clear()
    }

    @Test
    fun rowPresent_settlesWithSuccess_carryingTheRow() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertNull(result.errorCode)
        val payload = result.successPayload as String
        assertTrue("ack carries the row", payload.contains("\"body\":\"hi\""))
        assertTrue("ack carries the uri", payload.contains("content://sms/42"))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun rowMissing_acceptedSend_settlesWithRowMissingError_sentTrue() {
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertNull("must not ack a URI-only success", result.successPayload)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        assertTrue("message names the URI", result.errorMessage!!.contains("content://sms/42"))
        val details = result.errorDetails as Map<*, *>
        assertEquals("content://sms/42", details["messageUri"])
        assertEquals(SENTSMS_ACTION, details["eventType"])
        assertEquals(Activity.RESULT_OK, details["resultCode"])
        assertEquals("SENT_SUCCESS", details["statusString"])
        assertEquals(true, details["sent"])
        assertTrue(pending.isEmpty())
    }

    @Test
    fun rowMissing_failedSend_reportsSentFalse() {
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, result.calls)
        assertNull(result.successPayload)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, details["resultCode"])
        assertEquals("ERROR_GENERIC_FAILURE", details["statusString"])
        assertEquals(false, details["sent"])
        assertTrue(pending.isEmpty())
    }

    @Test
    fun mmsRowMissing_failedSend_reportsMmsOutcome_sentFalse() {
        // MMS sent results are MMS_ERROR_* values, a different enum from SMS.
        mmsProvider.row = null
        val result = pendingSend(mmsUri)

        deliverStatusBroadcast(SENTMMS_ACTION, 7, mmsUri, SmsManager.MMS_ERROR_IO_ERROR)

        assertEquals(1, result.calls)
        assertNull(result.successPayload)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals("content://mms/7", details["messageUri"])
        assertEquals(SENTMMS_ACTION, details["eventType"])
        assertEquals(SmsManager.MMS_ERROR_IO_ERROR, details["resultCode"])
        assertEquals("ERROR_MMS_IO_ERROR", details["statusString"])
        assertEquals(false, details["sent"])
        assertTrue(pending.isEmpty())
    }

    @Test
    fun nullCursor_isReportedAsRowMissing() {
        // simple_query maps a null cursor to no rows, so a provider that
        // returns null is indistinguishable from a deleted row here.
        smsProvider.nullCursor = true
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
    }

    @Test
    fun laterBroadcast_afterSettlement_isNoOp() {
        // Every part of a multipart SMS fires the same SENT broadcast; only
        // the first settles, and the rest must not settle (or re-read) again.
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, result.calls)
        assertEquals(true, (result.errorDetails as Map<*, *>)["sent"])
        assertEquals(1, smsProvider.queries)
    }

    @Test
    fun aResultIsRecordedOnce_howeverManyEnginesHaveAHandler() {
        // Every engine has its own handler (UNFY-306); none registers a
        // receiver, so the manifest receiver alone writes and settles.
        OutboundMessagingHandler(context)
        OutboundMessagingHandler(context)
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
        assertEquals(1, smsProvider.updates)
        assertEquals(1, smsProvider.queries)
    }

    @Test
    fun aFailedSendIsRecorded_afterItsProcessForgotIt() {
        // The process that sent was killed or recreated: nothing is pending,
        // but the manifest receiver still marks the row failed (UNFY-306).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")

        smsStatusIntents(context, 42, smsUri).sent.send(
            context, SmsManager.RESULT_ERROR_GENERIC_FAILURE, null,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_FAILED,
            smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE),
        )
        assertEquals(0, smsProvider.queries)
    }

    @Test
    fun multipart_failedPart_settlesAtThatPart_laterPartsWriteNothing() {
        smsProvider.row = null
        val result = pendingSend(smsUri, parts = 3)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)
        assertEquals("a successful part alone doesn't settle", 0, result.calls)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(false, details["sent"])
        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, details["resultCode"])
        assertEquals("ERROR_GENERIC_FAILURE", details["statusString"])
        assertEquals("only the failed part writes (TYPE=FAILED)", 1, smsProvider.updates)
        assertEquals("the row is re-read once, at settlement", 1, smsProvider.queries)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun multipart_allPartsSent_settlesSuccessAfterLastPart() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri, parts = 2)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)
        assertEquals(0, result.calls)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun deliveryReport_neverSettlesTheSend() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        val result = pendingSend(smsUri)

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00))

        assertEquals(0, result.calls)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
        assertTrue(pending.containsKey(smsUri))

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)
        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
    }

    @Test
    fun queryFailure_settlesSendAckFailed_withCarrierOutcome() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        smsProvider.queryFailure = SecurityException("role revoked")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertNull(result.successPayload)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(true, details["sent"])
        assertEquals(Activity.RESULT_OK, details["resultCode"])
        assertEquals("role revoked", details["cause"])
        assertTrue(pending.isEmpty())
    }

    @Test
    fun updateFailure_settlesSendAckFailed_withoutReRead() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        smsProvider.updateFailure = SecurityException("role revoked")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, result.calls)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        assertEquals(false, (result.errorDetails as Map<*, *>)["sent"])
        assertEquals(0, smsProvider.queries)
    }

    @Test
    fun failureAfterRowRead_settlesSendAckFailed_once() {
        // The MMS parts read runs after the row read; a failure there is still
        // part of building the ack, and the reply is sent exactly once.
        mmsProvider.row = mapOf("_id" to 7L, "msg_box" to 2)
        mmsProvider.partQueryFailure = IllegalStateException("parts unreadable")
        val result = pendingSend(mmsUri)

        deliverStatusBroadcast(SENTMMS_ACTION, 7, mmsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(true, details["sent"])
        assertEquals("parts unreadable", details["cause"])
    }

    @Test
    fun providerFailure_withNothingPending_doesNotEscapeTheReceiver() {
        // An escaping exception would crash the app process (UNFY-282).
        smsProvider.updateFailure = SecurityException("role revoked")

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, smsProvider.updates)
    }

    @Test
    fun smsStatusIntents_separateDeliveryReportsFromSendResults() {
        val intents = smsStatusIntents(context, 42, smsUri)

        // Both go to the manifest receiver, which runs even when the app
        // process is cached or has gone (UNFY-293, UNFY-306).
        val sent = shadowOf(intents.sent).savedIntent
        assertEquals(SENTSMS_ACTION, sent.action)
        assertEquals(42, sent.getIntExtra("messageID", -1))
        assertEquals("content://sms/42", sent.getStringExtra("uri"))
        assertEquals(ComponentName(context, OutboundMessagingReceiver::class.java), sent.component)

        // The delivery row is the intent data, which a fill-in cannot replace.
        val delivered = shadowOf(intents.delivered).savedIntent
        assertEquals(DELIVEREDSMS_ACTION, delivered.action)
        assertEquals(smsUri, delivered.data)
        assertEquals(ComponentName(context, OutboundMessagingReceiver::class.java), delivered.component)
    }

    @Test
    fun manifestReceiver_isNotExported() {
        // UNFY-292: an exported receiver let any app spoof SENT/DELIVERED results.
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, OutboundMessagingReceiver::class.java), 0,
        )
        assertFalse(info.exported)
    }

    @Test
    fun failedDeliveryReport_filledInByThePlatform_writesFailedStatus() {
        // The platform fills the status report into the delivery PendingIntent;
        // that only works because the intent is mutable (UNFY-293).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x41))

        assertEquals(Telephony.Sms.STATUS_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun deliveryReport_cannotRedirectTheWriteToAnotherRow() {
        // A fill-in may add extras, but the row comes from the intent data.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)

        smsStatusIntents(context, 42, smsUri).delivered.send(
            context,
            Activity.RESULT_OK,
            Intent()
                .setData(Uri.parse("content://sms/1"))
                .putExtra("uri", "content://sms/1")
                .putExtra("pdu", SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x41))
                .putExtra("format", SmsMessage.FORMAT_3GPP),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(smsUri, smsProvider.lastUri)
    }

    @Test
    fun deliveryReport_writesThroughTheDeliveryReportGuard() {
        // What the guard admits is tested as real SQL in DeliveryReportSelectionTest.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00))

        assertEquals(DELIVERY_REPORT_SELECTION, smsProvider.lastSelection)
        assertEquals(DELIVERY_REPORT_ARGS.toList(), smsProvider.lastSelectionArgs?.toList())
    }

    @Test
    fun sentSuccess_leavesTheDeliveryStatusToTheReport() {
        // STATUS is the delivery status: an accepted send writes nothing, so a
        // pending or failed delivery is never overwritten by a later part.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertNull(result.errorCode)
        assertEquals(0, smsProvider.updates)
    }

    @Test
    fun sentPendingIntent_settlesThePendingSend() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        smsStatusIntents(context, 42, smsUri).sent.send(context, Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
    }

    @Test
    fun deliveryIntent_isMutableForTheReport_sentIntentIsNot() {
        val intents = smsStatusIntents(context, 42, smsUri)
        assertFalse("the platform must be able to attach the report", intents.delivered.isImmutable)
        assertTrue(intents.sent.isImmutable)
    }

    @Test
    fun deliveryReportWithoutStatus_leavesTheRowUnchanged() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")

        deliverReport(pdu = null)

        assertEquals("nothing to record", 0, smsProvider.updates)
    }

    @Test
    fun noPendingSend_skipsReRead() {
        // A part arriving after its send settled has nothing to settle: a
        // failure is still recorded, but no ack is built.
        smsProvider.row = null

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, smsProvider.updates)
        assertEquals(0, smsProvider.queries)
    }

    private fun pendingSend(messageUri: Uri, parts: Int = 1): RecordingResult {
        val result = RecordingResult()
        pending[messageUri] = OutboundMessagingHandler.MessageRequestDetails(
            threadId = 1L,
            addresses = listOf("5551234"),
            body = "hi",
            flutterResult = result,
            messageUri = messageUri,
        ).apply { sentParts.expectParts(parts) }
        return result
    }

    private fun deliverStatusBroadcast(action: String, messageId: Int, messageUri: Uri, resultCode: Int) {
        val intent = Intent(action)
            .setComponent(ComponentName(context, OutboundMessagingReceiver::class.java))
            .putExtra("messageID", messageId)
            .putExtra("uri", messageUri.toString())
        context.sendOrderedBroadcast(intent, null, null, null, resultCode, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Fires the real delivery PendingIntent with the report the platform fills in. */
    private fun deliverReport(pdu: ByteArray?) {
        val fillIn = Intent()
        if (pdu != null) fillIn.putExtra("pdu", pdu).putExtra("format", SmsMessage.FORMAT_3GPP)
        smsStatusIntents(context, 42, smsUri).delivered.send(context, Activity.RESULT_OK, fillIn)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class RecordingResult : MethodChannel.Result {
        var calls = 0
        var successPayload: Any? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorDetails: Any? = null

        override fun success(result: Any?) {
            calls++
            successPayload = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            calls++
            this.errorCode = errorCode
            this.errorMessage = errorMessage
            this.errorDetails = errorDetails
        }

        override fun notImplemented() {
            calls++
        }
    }

    /**
     * Single-row message provider; [row] = null models the row being gone,
     * [nullCursor] a provider that returns no cursor at all, and
     * [queryFailure] / [updateFailure] a provider that throws, and
     * [partQueryFailure] one that throws only for MMS part reads. It records
     * but ignores selections: what the delivery-report guard admits is tested
     * as real SQL in [DeliveryReportSelectionTest].
     */
    class FakeMessageProvider : ContentProvider() {
        var row: Map<String, Any?>? = null
        var nullCursor = false
        var queryFailure: RuntimeException? = null
        var updateFailure: RuntimeException? = null
        var partQueryFailure: RuntimeException? = null
        var queries = 0
        var updates = 0
        var lastUri: Uri? = null
        var lastValues: ContentValues? = null
        var lastSelection: String? = null
        var lastSelectionArgs: Array<out String>? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries++
            queryFailure?.let { throw it }
            if (uri.pathSegments.contains("part")) partQueryFailure?.let { throw it }
            if (nullCursor) return null
            val current = row ?: return MatrixCursor(arrayOf("_id"))
            return MatrixCursor(current.keys.toTypedArray()).apply {
                addRow(current.values.toTypedArray())
            }
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            updates++
            lastUri = uri
            lastValues = values
            lastSelection = selection
            lastSelectionArgs = selectionArgs
            updateFailure?.let { throw it }
            return if (row == null) 0 else 1
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}

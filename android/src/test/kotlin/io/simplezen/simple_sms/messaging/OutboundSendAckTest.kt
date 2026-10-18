package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.time.Duration
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
 * Send-status ack golden (UNFY-160, UNFY-282, UNFY-283, UNFY-180).
 *
 * Drives the real [OutboundMessagingReceiver] callback registered by
 * [OutboundSender] with status broadcasts, against fake `sms` /
 * `mms` providers, and pins how the pending Flutter result is settled:
 * exactly once — after every SENT part has reported, failed if any part
 * failed; with the re-read row when it exists, [ERR_MESSAGE_ROW_MISSING] —
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
    private lateinit var handler: OutboundSender
    private var sendParts: List<SmsPartRef>? = null
    private val originalDeliver = OutboundMessagingHandler.deliver
    private val announced = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val quickReply = RespondViaMessageRequest(listOf("5551234"), "Can't talk now", 3)

    @Before
    fun setUp() {
        smsProvider = Robolectric.buildContentProvider(FakeMessageProvider::class.java).create("sms").get()
        mmsProvider = Robolectric.buildContentProvider(FakeMessageProvider::class.java).create("mms").get()
        handler = OutboundSender(context)
        OutboundMessagingHandler.deliver = { _, method, payload -> announced += method to payload.toMap() }
    }

    @After
    fun tearDown() {
        handler.release()
        OutboundMessagingHandler.deliver = originalDeliver
    }

    @Test
    fun rowPresent_settlesWithSuccess_carryingTheRow() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertNull(result.errorCode)
        val payload = result.successPayload as String
        assertTrue("ack carries the row", payload.contains("\"body\":\"hi\""))
        assertTrue("ack carries the uri", payload.contains("content://sms/42"))
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun rowMissing_acceptedSend_settlesWithRowMissingError_sentTrue() {
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

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
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun rowReadBackWithNoCursor_settlesSendAckFailed_notRowMissing() {
        // The status write landed; only the read-back returned no cursor, so
        // the row may still exist (UNFY-284).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        smsProvider.nullCursorOnRowRead = true
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertNull(result.successPayload)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(true, details["sent"])
        assertTrue(details["cause"].toString(), details["cause"].toString().contains("no cursor"))
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun rowMissing_failedSend_reportsSentFalse() {
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, part())

        assertEquals(1, result.calls)
        assertNull(result.successPayload)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, details["resultCode"])
        assertEquals("ERROR_GENERIC_FAILURE", details["statusString"])
        assertEquals(false, details["sent"])
        assertTrue(handler.channelResultMap.isEmpty())
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
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun aWriteThatTookNoRow_andCantBeReadBack_settlesSendAckFailed() {
        // The provider returns no cursor, so whether the result was recorded
        // is unknown (UNFY-335). The write is kept, though it can't be tied
        // to its row's date.
        smsProvider.nullCursor = true
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(true, details["sent"])
        assertTrue(details["cause"].toString(), details["cause"].toString().contains("no cursor"))
        assertEquals(setOf(smsUri), OwedRowWrites.forApp(context).rows())
    }

    @Test
    fun laterBroadcast_afterSettlement_isNoOp() {
        // A part reported again after its send settled neither settles nor
        // re-reads again.
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, part())

        assertEquals(1, result.calls)
        assertEquals(true, (result.errorDetails as Map<*, *>)["sent"])
        assertEquals(1, smsProvider.rowReads)
    }

    @Test
    fun onlyTheOwningHandler_settlesAndReReads() {
        // Foreground and background engines each register a handler; only the
        // owner of the pending send records, re-reads and settles it.
        val otherHandler = OutboundSender(context)
        try {
            smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
            val result = pendingSend(smsUri)

            deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, part())

            assertEquals(1, result.calls)
            assertTrue(result.successPayload is String)
            assertEquals("only the owner records the failed send", 1, smsProvider.updates)
            assertEquals(1, smsProvider.rowReads)
        } finally {
            otherHandler.release()
        }
    }

    @Test
    fun multipart_successThenFailure_settlesOnceAfterEveryPart_asFailed() {
        smsProvider.row = null
        val result = pendingSend(smsUri, parts = 3)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(0, 3))
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, part(1, 3))
        assertEquals("a failed part does not settle before the others report", 0, result.calls)
        assertEquals(0, smsProvider.updates)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(2, 3))

        assertEquals(1, result.calls)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(false, details["sent"])
        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, details["resultCode"])
        assertEquals("ERROR_GENERIC_FAILURE", details["statusString"])
        assertEquals("the row is written once, failed", 1, smsProvider.updates)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals("the row is re-read once, at settlement", 1, smsProvider.rowReads)
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun multipart_failureThenSuccess_settlesOnceAfterEveryPart_asFailed() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri, parts = 2)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_NO_SERVICE, part(0, 2))
        assertEquals(0, result.calls)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 2))

        assertEquals(1, result.calls)
        assertEquals(1, smsProvider.updates)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.ERROR_CODE))
    }

    @Test
    fun multipart_failuresOutOfOrder_settleWithTheFirstFailureToArrive() {
        smsProvider.row = null
        val result = pendingSend(smsUri, parts = 3)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_NO_SERVICE, part(2, 3))
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, part(0, 3))
        assertEquals("failed parts do not settle before the others report", 0, result.calls)
        assertEquals(0, smsProvider.updates)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 3))

        assertEquals(1, result.calls)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(false, details["sent"])
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, details["resultCode"])
        assertEquals("ERROR_NO_SERVICE", details["statusString"])
        assertEquals(1, smsProvider.updates)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.ERROR_CODE))
    }

    @Test
    fun multipart_allPartsSent_settlesSuccessAfterLastPart() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri, parts = 2)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 2))
        assertEquals(0, result.calls)
        assertEquals("the row stays in the outbox until every part is sent", 0, smsProvider.updates)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 2))
        assertEquals("a repeated part is not another part", 0, result.calls)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(0, 2))

        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
        assertEquals(1, smsProvider.updates)
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun multipart_partsAcrossProcessDeath_stillSettleTheRowFromEveryPart() {
        // The app process dies after part 1 reports; Android starts it again for
        // part 2's result, with no handler owning the send (UNFY-180).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        pendingSend(smsUri, parts = 2)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(0, 2))
        handler.release()
        assertEquals(0, smsProvider.updates)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_RADIO_OFF, part(1, 2))

        assertEquals(1, smsProvider.updates)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
    }

    @Test
    fun aReusedRowId_ignoresTheEarlierSendsLateParts() {
        // The row was deleted mid-send and its _id taken by a new send.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        pendingSend(smsUri, parts = 2)
        val earlier = part(0, 2)
        val result = pendingSend(smsUri, parts = 1)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, earlier)

        assertEquals(0, result.calls)
        assertEquals(0, smsProvider.updates)
    }

    @Test
    fun deliveryReports_settleTheMessageFromEveryPart() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        pendingSend(smsUri, parts = 2)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(0, 2))
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 2))

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part(0, 2))
        assertEquals(
            "one part delivered is not the message delivered",
            Telephony.Sms.STATUS_PENDING,
            smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS),
        )
        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part(1, 2))

        assertEquals(Telephony.Sms.STATUS_COMPLETE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun deliveryReports_anyFailedPart_failsTheMessage() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        pendingSend(smsUri, parts = 2)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(0, 2))
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 2))

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x41), part(1, 2))
        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part(0, 2))

        assertEquals(Telephony.Sms.STATUS_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun anUnavailableLedger_neverSettlesAMultipartSmsFromOnePart() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri, parts = 2)
        makeUnreadable(part(0, 2))

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(0, 2))
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part(1, 2))
        assertEquals("every part reported, but they cannot be added up", 0, result.calls)
        assertEquals(0, smsProvider.updates)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEND_RESULT_TIMEOUT_MS))

        assertEquals(1, result.calls)
        assertEquals(ERR_SEND_RESULT_TIMEOUT, result.errorCode)
    }

    @Test
    fun anUnavailableLedger_leavesAMultipartSmsDeliveryStatusAsIs() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        pendingSend(smsUri, parts = 2)
        makeUnreadable(part(0, 2))

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part(0, 2))

        assertEquals(0, smsProvider.updates)
    }

    @Test
    fun anUnavailableLedger_stillRecordsAOnePartSmsReport() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        pendingSend(smsUri, parts = 1)
        makeUnreadable(part())

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part())

        assertEquals(Telephony.Sms.STATUS_COMPLETE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun anUnavailableLedger_stillSettlesAOnePartSms_fromItsOnlyResult() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri, parts = 1)
        makeUnreadable(part())

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
        assertEquals(1, smsProvider.updates)
    }

    // Replaces [part]'s ledger record with a directory, which cannot be read.
    private fun makeUnreadable(part: SmsPartRef) {
        val record = File(File(File(context.filesDir, "simple_sms_sms_parts"), part.rowId.toString()), part.token + ".json")
        record.delete()
        record.mkdirs()
    }

    @Test
    fun eachPart_getsItsOwnStatusIntents() {
        val first = smsStatusIntents(context, 42, smsUri, SmsPartRef("t", 42L, 0, 2))
        val second = smsStatusIntents(context, 42, smsUri, SmsPartRef("t", 42L, 1, 2))

        assertFalse(first.sent == second.sent)
        assertFalse(first.delivered == second.delivered)
        val secondSent = shadowOf(second.sent).savedIntent
        assertEquals("t", secondSent.getStringExtra("partToken"))
        assertEquals(42L, secondSent.getLongExtra("partRowId", -1L))
        assertEquals(1, secondSent.getIntExtra("partIndex", -1))
        assertEquals(2, secondSent.getIntExtra("partCount", -1))
        assertEquals(1, shadowOf(second.delivered).savedIntent.getIntExtra("partIndex", -1))
    }

    @Test
    fun aSentResultWithoutAPartToken_forARowATrackedSendHolds_isIgnored() {
        // A send made before parts were tracked, whose status intents outlived
        // an app update, while the provider reused its row id (UNFY-180).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        assertEquals(0, result.calls)
        assertEquals(0, smsProvider.updates)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())
        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
    }

    @Test
    fun aSentResultWithoutAPartToken_afterTheTrackedSendFinished_isStillIgnored() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        // Its start cancelled an earlier, untracked send's status intents.
        val result = pendingSend(smsUri, markUntrackedSend = true)
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())
        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part())
        assertEquals(1, result.calls)
        val updates = smsProvider.updates

        // An earlier, untracked send's late failure for the reused row.
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(updates, smsProvider.updates)
    }

    @Test
    fun aDeliveryReportWithoutAPartToken_forARowATrackedSendHolds_isIgnored() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        val part = trackedPart()

        deliverUntrackedReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x41))
        assertEquals(0, smsProvider.updates)

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun aDeliveryReportWithoutAPartToken_forAnUntrackedRow_isStillRecorded() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)

        deliverUntrackedReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00))

        assertEquals(Telephony.Sms.STATUS_COMPLETE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun startingATrackedSend_cancelsTheStatusIntentsOfAnUntrackedSendToItsRow() {
        // The pair a send made before parts were tracked, still pending after
        // an app update: shared by every part, with no identifier.
        val receiver = ComponentName(context, OutboundMessagingReceiver::class.java)
        val untrackedSent = PendingIntent.getBroadcast(
            context, 42,
            Intent(SENTSMS_ACTION).putExtra("messageID", 42).putExtra("uri", smsUri.toString()).setComponent(receiver),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val untrackedDelivered = PendingIntent.getBroadcast(
            context, 42, Intent(DELIVEREDSMS_ACTION).setData(smsUri).setComponent(receiver),
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) or
                PendingIntent.FLAG_CANCEL_CURRENT,
        )

        val parts = startTrackedSmsSend(context, SmsPartLedger.forApp(context), 42, smsUri, 1)

        assertTrue("its late SENT result never lands", shadowOf(untrackedSent).isCanceled)
        assertTrue("its late delivery report never lands", shadowOf(untrackedDelivered).isCanceled)
        SmsPartLedger.forApp(context).forget(parts.single())
        assertTrue("one already on its way is still told apart", SmsPartLedger.forApp(context).tracks(42L))
        val tracked = smsStatusIntents(context, 42, smsUri, parts.single())
        assertFalse(shadowOf(tracked.sent).isCanceled)
        assertFalse(shadowOf(tracked.delivered).isCanceled)
        assertTrue(SmsPartLedger.forApp(context).tracks(42L))
    }

    @Test
    fun startingATrackedSend_withNoUntrackedSendToItsRow_leavesNoMark() {
        val parts = startTrackedSmsSend(context, SmsPartLedger.forApp(context), 42, smsUri, 1)
        SmsPartLedger.forApp(context).forget(parts.single())
        assertFalse(SmsPartLedger.forApp(context).tracks(42L))
    }

    @Test
    fun cancellingUntrackedStatusIntents_sparesTrackedIntents_andOtherRows() {
        val receiver = ComponentName(context, OutboundMessagingReceiver::class.java)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val tracked = smsStatusIntents(context, 42, smsUri, SmsPartRef("t", 42L, 0, 1))
        val otherRowSent = PendingIntent.getBroadcast(
            context, 43, Intent(SENTSMS_ACTION).setComponent(receiver), PendingIntent.FLAG_IMMUTABLE,
        )
        val otherRowDelivered = PendingIntent.getBroadcast(
            context, 43, Intent(DELIVEREDSMS_ACTION).setData(Uri.parse("content://sms/43")).setComponent(receiver),
            mutable,
        )

        cancelUntrackedStatusIntents(context, 42, smsUri)

        assertFalse(shadowOf(tracked.sent).isCanceled)
        assertFalse(shadowOf(tracked.delivered).isCanceled)
        assertFalse(shadowOf(otherRowSent).isCanceled)
        assertFalse(shadowOf(otherRowDelivered).isCanceled)
    }

    @Test
    fun deliveryReport_neverSettlesTheSend() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT)
        val result = pendingSend(smsUri)

        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00))

        assertEquals(0, result.calls)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.STATUS))
        assertTrue(handler.channelResultMap.containsKey(smsUri))

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())
        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
    }

    @Test
    fun queryFailure_settlesSendAckFailed_withCarrierOutcome() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        smsProvider.queryFailure = SecurityException("role revoked")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertNull(result.successPayload)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(true, details["sent"])
        assertEquals(Activity.RESULT_OK, details["resultCode"])
        assertEquals("role revoked", details["cause"])
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun updateFailure_settlesSendAckFailed_withoutReRead() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        smsProvider.updateFailure = SecurityException("role revoked")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, part())

        assertEquals(1, result.calls)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        assertEquals(false, (result.errorDetails as Map<*, *>)["sent"])
        assertEquals(0, smsProvider.rowReads)
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
    fun aResultWriteTheProviderIgnores_settlesSendAckFailed_andIsOwed() {
        // The app lost the default-SMS role or its WRITE_SMS app-op mid-send
        // (UNFY-335): the row would read back unchanged, still sending.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_OUTBOX, "date" to 1_000L)
        smsProvider.ignoreWrites = true
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertNull("must not ack the unchanged row", result.successPayload)
        assertEquals(ERR_SEND_ACK_FAILED, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertEquals(true, details["sent"])
        assertTrue(details["cause"].toString(), details["cause"].toString().contains("ignored"))
        assertEquals(setOf(smsUri), OwedRowWrites.forApp(context).rows())
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun aPendingSendWhoseRowIdIsReused_isAnsweredRowMissing_itsOutcomeUnknown() {
        // Its row was deleted and a new send reused the id before its result
        // came; it would otherwise never be answered (UNFY-335).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val superseded = pendingSend(smsUri)
        val current = pendingSend(smsUri)

        assertEquals(1, superseded.calls)
        assertEquals(ERR_MESSAGE_ROW_MISSING, superseded.errorCode)
        val details = superseded.errorDetails as Map<*, *>
        assertEquals("content://sms/42", details["messageUri"])
        assertTrue(details.containsKey("sent"))
        assertNull(details["sent"])
        assertEquals(0, current.calls)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals("the superseded send is answered once", 1, superseded.calls)
        assertEquals(1, current.calls)
        assertNull(current.errorCode)
    }

    @Test
    fun aNewSendThatTakesARow_forgetsTheWritesOwedToIt() {
        // The message they were for was deleted, and its id reused (UNFY-335).
        OwedRowWrites.forApp(context).owe(sendResultWrite(smsUri, SENTSMS_ACTION, Activity.RESULT_OK))

        pendingSend(smsUri)

        assertTrue(OwedRowWrites.forApp(context).rows().isEmpty())
    }

    @Test
    fun aSend_retriesTheOwedStatusWrites() {
        // A send needs write access, so an ignored write may land now.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "date" to 1_000L)
        OwedRowWrites.forApp(context).owe(sendResultWrite(smsUri, SENTSMS_ACTION, Activity.RESULT_OK))
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)

        handler.onMethodCall(MethodCall("sendMessage", null), RecordingResult())

        val deadline = System.currentTimeMillis() + 5_000
        while (OwedRowWrites.forApp(context).rows().isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(OwedRowWrites.forApp(context).rows().isEmpty())
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
    }

    @Test
    fun aDeliveryWriteTheProviderIgnores_isOwed() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi", "type" to Telephony.Sms.MESSAGE_TYPE_SENT, "date" to 1_000L)
        smsProvider.ignoreWrites = true

        pendingSend(smsUri)
        deliverReport(SmsDeliveryStatusTest.gsmStatusReportPdu(tpStatus = 0x00), part())

        assertEquals(setOf(smsUri), OwedRowWrites.forApp(context).rows())
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
        val intents = smsStatusIntents(context, 42, smsUri, SmsPartRef("t", 42L, 0, 2))

        val sent = shadowOf(intents.sent).savedIntent
        assertEquals(SENTSMS_ACTION, sent.action)
        assertEquals(42, sent.getIntExtra("messageID", -1))
        assertEquals("content://sms/42", sent.getStringExtra("uri"))
        // SENT also targets the manifest receiver, so a result is recorded
        // even after the app process has gone (UNFY-299).
        assertEquals(ComponentName(context, OutboundMessagingReceiver::class.java), sent.component)

        // Delivery reports go to the manifest receiver, which runs even after
        // the app process has gone; the row is the intent data, which a
        // fill-in cannot replace.
        val delivered = shadowOf(intents.delivered).savedIntent
        assertEquals(DELIVEREDSMS_ACTION, delivered.action)
        assertEquals(smsUri, delivered.data)
        assertEquals(ComponentName(context, OutboundMessagingReceiver::class.java), delivered.component)
    }

    @Test
    fun noRuntimeStatusReceiver_isRegistered() {
        // UNFY-292: an exported runtime receiver let any app spoof results.
        // Every status intent now targets the unexported manifest receiver
        // (UNFY-299), so nothing is registered at runtime.
        val registrations = shadowOf(context as android.app.Application).registeredReceivers
        assertTrue(registrations.none { it.intentFilter.hasAction(SENTSMS_ACTION) })
        assertTrue(registrations.none { it.intentFilter.hasAction(SENTMMS_ACTION) })
    }

    @Test
    fun manifestReceiver_isNotExported() {
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

        smsStatusIntents(context, 42, smsUri, trackedPart()).delivered.send(
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
    fun sentSuccess_movesTheRowOutOfTheOutbox_leavingTheDeliveryStatus() {
        // An accepted send moves outbox -> sent (UNFY-299). STATUS is the
        // delivery status, left for the report (UNFY-293).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(1, result.calls)
        assertNull(result.errorCode)
        assertEquals(1, smsProvider.updates)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertFalse(smsProvider.lastValues!!.containsKey(Telephony.Sms.STATUS))
        assertEquals(SENT_RESULT_SELECTION, smsProvider.lastSelection)
        assertEquals(SENT_RESULT_ARGS.toList(), smsProvider.lastSelectionArgs?.toList())
    }

    @Test
    fun settledResult_isRecordedWhileTheSendIsStillOwned() {
        // A concurrent stale-outbox sweep skips owned sends; the send must stay
        // owned until its result is written, or the sweep could fail it first.
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        pendingSend(smsUri)
        var ownedAtWrite: Boolean? = null
        smsProvider.duringUpdate = { ownedAtWrite = smsUri in SendResultRouter.pendingSends() }

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())

        assertEquals(true, ownedAtWrite)
        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun noSentResult_settlesOnceAsTimeout_andFailsTheOutboxRow() {
        // The radio process died mid-send: no SENT result ever comes (UNFY-299).
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEND_RESULT_TIMEOUT_MS))

        assertEquals(1, result.calls)
        assertEquals(ERR_SEND_RESULT_TIMEOUT, result.errorCode)
        val details = result.errorDetails as Map<*, *>
        assertTrue(details.containsKey("sent"))
        assertNull("whether it went out is unknown", details["sent"])
        assertEquals("content://sms/42", details["messageUri"])
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(NO_SENT_RESULT_ERROR_CODE, smsProvider.lastValues?.getAsInteger(Telephony.Sms.ERROR_CODE))
        // What the guard admits is tested as real SQL in SentResultSelectionTest.
        assertTrue(handler.channelResultMap.isEmpty())

        // A result arriving after the timeout is unowned: no second reply.
        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())
        assertEquals(1, result.calls)
    }

    @Test
    fun sentResultBeforeTheDeadline_cancelsTheTimeout() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK, part())
        assertEquals("the timer is cancelled", Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEND_RESULT_TIMEOUT_MS))

        assertEquals(1, result.calls)
        assertNull(result.errorCode)
    }

    @Test
    fun release_cancelsPendingTimeouts() {
        val result = pendingSend(smsUri)
        assertTrue(shadowOf(Looper.getMainLooper()).nextScheduledTaskTime > Duration.ZERO)

        handler.release()

        assertEquals(Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEND_RESULT_TIMEOUT_MS))
        assertEquals(0, result.calls)
    }

    @Test
    fun sentResult_withNoLiveHandler_isStillRecorded() {
        // The app process died after sending: no handler owns the result, and
        // the manifest receiver still records it on the row (UNFY-299). These
        // parts carry no token, as from an intent built before parts were
        // tracked, so each is recorded on its own.
        handler.release()
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(SENT_RESULT_SELECTION, smsProvider.lastSelection)
        assertEquals(0, smsProvider.rowReads)
    }

    // --- quick replies (UNFY-319): nobody awaits them; a failure reaches Dart ---

    @Test
    fun quickReply_isNotAwaited() {
        handler.awaitResult(smsUri, quickReplyDetails())

        assertTrue(handler.channelResultMap.isEmpty())
    }

    @Test
    fun failedQuickReply_isRecorded_thenAnnouncedToDart() {
        handler.release()
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        sendParts = SmsPartLedger.forApp(context).start(42L, 1, false)

        sendQuickReplyResult(part(), SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(
            listOf(
                "receiveRespondViaMessageFailure" to mapOf(
                    "recipients" to listOf("5551234"),
                    "body" to "Can't talk now",
                    "subscriptionId" to 3,
                ),
            ),
            announced,
        )
    }

    @Test
    fun sentQuickReply_announcesNothing() {
        handler.release()
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        sendParts = SmsPartLedger.forApp(context).start(42L, 1, false)

        sendQuickReplyResult(part(), Activity.RESULT_OK)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, smsProvider.lastValues?.getAsInteger(Telephony.Sms.TYPE))
        assertTrue(announced.isEmpty())
    }

    @Test
    fun multipartQuickReply_isAnnouncedOnceAfterEveryPart() {
        handler.release()
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        sendParts = SmsPartLedger.forApp(context).start(42L, 2, false)

        sendQuickReplyResult(part(0, 2), SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        assertTrue("the other part has not reported", announced.isEmpty())
        sendQuickReplyResult(part(1, 2), Activity.RESULT_OK)

        assertEquals(1, announced.size)
    }

    @Test
    fun failedMmsQuickReply_isAnnouncedToDart() {
        handler.release()
        mmsProvider.row = mapOf("_id" to 7L)

        deliverStatusBroadcast(
            SENTMMS_ACTION, 7, mmsUri, SmsManager.MMS_ERROR_UNSPECIFIED, quickReply = quickReply,
        )

        assertEquals(listOf("receiveRespondViaMessageFailure"), announced.map { it.first })
    }

    @Test
    fun failedOrdinarySend_announcesNothing() {
        handler.release()
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertTrue(announced.isEmpty())
    }

    private fun quickReplyDetails() = OutboundSender.MessageRequestDetails(
        threadId = 1L,
        addresses = quickReply.recipients,
        body = quickReply.body,
        flutterResult = RecordingResult(),
    ).also { it.quickReply = quickReply }

    private fun sendQuickReplyResult(part: SmsPartRef, resultCode: Int) {
        smsStatusIntents(context, 42, smsUri, part, quickReply).sent.send(context, resultCode, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun sentPendingIntent_settlesThePendingSend() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        smsStatusIntents(context, 42, smsUri, part()).sent.send(context, Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, result.calls)
        assertTrue(result.successPayload is String)
    }

    @Test
    fun deliveryIntent_isMutableForTheReport_sentIntentIsNot() {
        val intents = smsStatusIntents(context, 42, smsUri, SmsPartRef("t", 42L, 0, 1))
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
        // A part arriving after its send settled (or another handler's
        // receiver) has nothing to settle: a failure is still recorded, but no
        // ack is built.
        smsProvider.row = null

        deliverStatusBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, smsProvider.updates)
        assertEquals(0, smsProvider.rowReads)
    }

    /** A send this test's handler owns; an SMS's [parts] are tracked in the ledger, as [sendSms] does. */
    private fun pendingSend(messageUri: Uri, parts: Int = 1, markUntrackedSend: Boolean = false): RecordingResult {
        val result = RecordingResult()
        if (messageUri.authority == "sms") {
            sendParts = SmsPartLedger.forApp(context).start(42L, parts, markUntrackedSend)
        }
        handler.awaitResult(
            messageUri,
            OutboundSender.MessageRequestDetails(
                threadId = 1L,
                addresses = listOf("5551234"),
                body = "hi",
                flutterResult = result,
                messageUri = messageUri,
            ),
        )
        return result
    }

    /** Part [index] of the [count]-part SMS [pendingSend] started. */
    private fun part(index: Int = 0, count: Int = 1): SmsPartRef =
        checkNotNull(sendParts).single { it.index == index }.also { check(it.count == count) }

    /** The SENT broadcast of [part], or of an intent built before parts were tracked when null. */
    private fun deliverStatusBroadcast(
        action: String,
        messageId: Int,
        messageUri: Uri,
        resultCode: Int,
        part: SmsPartRef? = null,
        quickReply: RespondViaMessageRequest? = null,
    ) {
        val intent = Intent(action)
            .setComponent(ComponentName(context, OutboundMessagingReceiver::class.java))
            .putExtra("messageID", messageId)
            .putExtra("uri", messageUri.toString())
        quickReply?.let(intent::putQuickReply)
        if (part != null) {
            intent.putExtra("partToken", part.token)
                .putExtra("partRowId", part.rowId)
                .putExtra("partIndex", part.index)
                .putExtra("partCount", part.count)
        }
        context.sendOrderedBroadcast(intent, null, null, null, resultCode, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Fires the real delivery PendingIntent with the report the platform fills
     * in. Robolectric hands a component-targeted broadcast to every registered
     * instance of that receiver class (here also the handler's runtime one),
     * where Android delivers it only to the manifest receiver; every instance
     * records it the same way, so tests assert the written status, not counts.
     */
    private fun deliverReport(pdu: ByteArray?, part: SmsPartRef = trackedPart()) {
        val fillIn = Intent()
        if (pdu != null) fillIn.putExtra("pdu", pdu).putExtra("format", SmsMessage.FORMAT_3GPP)
        smsStatusIntents(context, 42, smsUri, part).delivered.send(context, Activity.RESULT_OK, fillIn)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A delivery report on the intent a send made before parts were tracked. */
    private fun deliverUntrackedReport(pdu: ByteArray) {
        context.sendBroadcast(
            Intent(DELIVEREDSMS_ACTION)
                .setData(smsUri)
                .setComponent(ComponentName(context, OutboundMessagingReceiver::class.java))
                .putExtra("pdu", pdu)
                .putExtra("format", SmsMessage.FORMAT_3GPP),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Part 0 of the send [pendingSend] started, or of a new one-part send. */
    private fun trackedPart(): SmsPartRef {
        if (sendParts == null) sendParts = SmsPartLedger.forApp(context).start(42L, 1)
        return part()
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
     * [partQueryFailure] one that throws only for MMS part reads, and
     * [ignoreWrites] an app without write access (the default-SMS role and
     * its WRITE_SMS app-op), whose updates the provider ignores. It records
     * but ignores selections: what the guards admit is tested as real SQL in
     * [DeliveryReportSelectionTest] and [SentResultSelectionTest].
     */
    class FakeMessageProvider : ContentProvider() {
        var row: Map<String, Any?>? = null
        var ignoreWrites = false
        var nullCursor = false
        var nullCursorOnRowRead = false
        var queryFailure: RuntimeException? = null
        var updateFailure: RuntimeException? = null
        var partQueryFailure: RuntimeException? = null
        var queries = 0

        /**
         * Reads of the row itself, to build an ack: no projection, unlike a
         * check of whether an unwritten row still matches its write's guard.
         */
        var rowReads = 0
        var updates = 0
        var lastUri: Uri? = null
        var lastValues: ContentValues? = null
        var lastSelection: String? = null
        var lastSelectionArgs: Array<out String>? = null
        var duringUpdate: (() -> Unit)? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries++
            if (projection == null) rowReads++
            queryFailure?.let { throw it }
            if (uri.pathSegments.contains("part")) partQueryFailure?.let { throw it }
            if (nullCursor || (nullCursorOnRowRead && projection == null)) return null
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
            duringUpdate?.invoke()
            updateFailure?.let { throw it }
            return if (row == null || ignoreWrites) 0 else 1
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}

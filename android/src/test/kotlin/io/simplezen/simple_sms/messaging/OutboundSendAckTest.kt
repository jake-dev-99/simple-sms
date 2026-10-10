package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodChannel
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Send-status ack golden (UNFY-160).
 *
 * Drives the real [OutboundMessagingReceiver] callback registered by
 * [OutboundMessagingHandler] with SENT broadcasts, against fake `sms` / `mms`
 * providers, and pins how the pending Flutter result is settled: exactly once,
 * with the re-read row when it exists, and with [ERR_MESSAGE_ROW_MISSING] —
 * never a URI-only success — when it doesn't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSendAckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val smsUri: Uri = Uri.parse("content://sms/42")
    private val mmsUri: Uri = Uri.parse("content://mms/7")
    private lateinit var smsProvider: FakeMessageProvider
    private lateinit var mmsProvider: FakeMessageProvider
    private lateinit var handler: OutboundMessagingHandler

    @Before
    fun setUp() {
        smsProvider = Robolectric.buildContentProvider(FakeMessageProvider::class.java).create("sms").get()
        mmsProvider = Robolectric.buildContentProvider(FakeMessageProvider::class.java).create("mms").get()
        handler = OutboundMessagingHandler(context)
    }

    @After
    fun tearDown() {
        handler.release()
    }

    @Test
    fun rowPresent_settlesWithSuccess_carryingTheRow() {
        smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
        val result = pendingSend(smsUri)

        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

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

        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

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
    fun rowMissing_failedSend_reportsSentFalse() {
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

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

        deliverSentBroadcast(SENTMMS_ACTION, 7, mmsUri, SmsManager.MMS_ERROR_IO_ERROR)

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
    fun nullCursor_isReportedAsRowMissing() {
        // simple_query maps a null cursor to no rows, so a provider that
        // returns null is indistinguishable from a deleted row here.
        smsProvider.nullCursor = true
        val result = pendingSend(smsUri)

        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, result.calls)
        assertEquals(ERR_MESSAGE_ROW_MISSING, result.errorCode)
    }

    @Test
    fun laterBroadcast_afterSettlement_isNoOp() {
        // Every part of a multipart SMS fires the same SENT broadcast; only
        // the first settles, and the rest must not settle (or re-read) again.
        smsProvider.row = null
        val result = pendingSend(smsUri)

        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)
        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, result.calls)
        assertEquals(true, (result.errorDetails as Map<*, *>)["sent"])
        assertEquals(1, smsProvider.queries)
    }

    @Test
    fun onlyTheOwningHandler_settlesAndReReads() {
        // Foreground and background engines each register a handler whose
        // receiver hears the same broadcast; only the owner of the pending
        // send re-reads and settles it.
        val otherHandler = OutboundMessagingHandler(context)
        try {
            smsProvider.row = mapOf("_id" to 42L, "body" to "hi")
            val result = pendingSend(smsUri)

            deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

            assertEquals(1, result.calls)
            assertTrue(result.successPayload is String)
            assertEquals(2, smsProvider.updates)
            assertEquals(1, smsProvider.queries)
        } finally {
            otherHandler.release()
        }
    }

    @Test
    fun noPendingSend_skipsReRead() {
        // A later part of a multipart SMS (or another handler's receiver) has
        // nothing to settle: the status is still written, but no ack is built.
        smsProvider.row = null

        deliverSentBroadcast(SENTSMS_ACTION, 42, smsUri, Activity.RESULT_OK)

        assertEquals(1, smsProvider.updates)
        assertEquals(0, smsProvider.queries)
    }

    private fun pendingSend(messageUri: Uri): RecordingResult {
        val result = RecordingResult()
        handler.channelResultMap[messageUri] = OutboundMessagingHandler.MessageRequestDetails(
            threadId = 1L,
            addresses = listOf("5551234"),
            body = "hi",
            sentPendingIntent = null,
            deliveredPendingIntent = null,
            flutterResult = result,
            messageUri = messageUri,
        )
        return result
    }

    private fun deliverSentBroadcast(action: String, messageId: Int, messageUri: Uri, resultCode: Int) {
        val intent = Intent(action)
            .putExtra("messageID", messageId)
            .putExtra("uri", messageUri.toString())
        context.sendOrderedBroadcast(intent, null, null, null, resultCode, null, null)
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
     * Single-row message provider; [row] = null models the row being gone and
     * [nullCursor] a provider that returns no cursor at all.
     */
    class FakeMessageProvider : ContentProvider() {
        var row: Map<String, Any?>? = null
        var nullCursor = false
        var queries = 0
        var updates = 0

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries++
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
            return if (row == null) 0 else 1
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}

package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.mms.sending.bindMmsSentIntent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

/** Exercises the real receiver, handler, resolver writes and returned row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundMmsRecordTest {
    @Test
    fun resultIntentUsesTheCanonicalRowUriAfterTheBoxChanges() {
        val intent = Intent(SENTMMS_ACTION).setPackage("test.host")
        val uri = bindMmsSentIntent(intent, Uri.parse("content://mms/outbox/9001"))
        assertEquals("content://mms/9001", uri.toString())
        assertEquals(uri.toString(), intent.getStringExtra("uri"))
        assertEquals(9001, intent.getIntExtra("messageID", -1))
        assertEquals(SENTMMS_ACTION, intent.action)
        assertEquals("test.host", intent.`package`)
    }

    @Test
    fun successfulSendMovesThePersistedRowToSent() {
        verifyOutcome(Activity.RESULT_OK, Telephony.Mms.MESSAGE_BOX_SENT)
    }

    @Test
    fun failedSendMovesTheSameRowToFailed() {
        verifyOutcome(SmsManager.MMS_ERROR_IO_ERROR, Telephony.Mms.MESSAGE_BOX_FAILED)
    }

    @Test
    fun mmsResultDoesNotConsumeAnSmsRequestWithTheSameNumericId() {
        verifyOutcome(Activity.RESULT_OK, Telephony.Mms.MESSAGE_BOX_SENT, concurrentSms = true)
    }

    @Test
    fun initiationFailureMarksThePersistedOutboxRecordFailed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = MmsRecordProvider()
        ShadowContentResolver.registerProviderInternal("mms", provider)
        markPersistedSendFailed(context, Uri.parse("content://mms/9001"))
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, provider.row.getAsInteger("msg_box"))
        assertEquals(1, provider.updates)
    }

    private fun verifyOutcome(resultCode: Int, expectedBox: Int, concurrentSms: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = MmsRecordProvider()
        ShadowContentResolver.registerProviderInternal("mms", provider)
        val handler = OutboundMessagingHandler(context)
        val result = MmsRecordResult()
        val request = OutboundMessagingHandler.MessageRequestDetails(
            threadId = 7,
            addresses = listOf("+15551234567"),
            body = "MMS test",
            sentPendingIntent = null,
            deliveredPendingIntent = null,
            flutterResult = result,
        )
        val field = OutboundMessagingHandler::class.java.getDeclaredField("channelResultMap")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val requests = field.get(handler) as MutableMap<Uri, OutboundMessagingHandler.MessageRequestDetails>
        requests[Uri.parse("content://mms/9001")] = request
        val smsResult = MmsRecordResult()
        if (concurrentSms) {
            requests[Uri.parse("content://sms/9001")] = request.copy(flutterResult = smsResult)
        }

        try {
            context.sendOrderedBroadcast(
                Intent(SENTMMS_ACTION).setPackage(context.packageName)
                    .putExtra("messageID", 9001)
                    .putExtra("uri", "content://mms/9001"),
                null, null, null, resultCode, null, null,
            )
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertEquals(expectedBox, provider.row.getAsInteger("msg_box"))
            assertEquals(1, provider.updates)
            assertEquals(1, result.successes.size)
            val returned = JSONObject(result.successes.single() as String)
            assertEquals(9001, returned.getInt("_id"))
            assertEquals(expectedBox, returned.getInt("msg_box"))
            assertEquals(128, returned.getInt("m_type"))
            assertEquals(3, returned.getInt("sub_id"))
            if (concurrentSms) {
                assertEquals(setOf(Uri.parse("content://sms/9001")), requests.keys)
                assertTrue(smsResult.successes.isEmpty())
            } else {
                assertTrue(requests.isEmpty())
            }
        } finally {
            handler.release()
        }
    }
}

private class MmsRecordProvider : ContentProvider() {
    val row = ContentValues().apply {
        put("_id", 9001)
        put("thread_id", 7)
        put("msg_box", Telephony.Mms.MESSAGE_BOX_OUTBOX)
        put("m_type", 128)
        put("sub_id", 3)
        put("st", Telephony.Sms.STATUS_PENDING)
    }
    var updates = 0
    override fun onCreate() = true
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        assertEquals("content://mms/9001", uri.toString())
        row.putAll(requireNotNull(values))
        updates++
        return 1
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.toString() != "content://mms/9001") return MatrixCursor(arrayOf("_id"))
        val columns = row.keySet().toTypedArray()
        return MatrixCursor(columns).apply { addRow(columns.map { row.get(it) }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw AssertionError("A send result must not create another row")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw AssertionError("A send result must not delete the row")
    override fun getType(uri: Uri): String = throw AssertionError("No MIME query expected")
}

private class MmsRecordResult : MethodChannel.Result {
    val successes = mutableListOf<Any?>()
    override fun success(result: Any?) { successes.add(result) }
    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = throw AssertionError("$errorCode: $errorMessage")
    override fun notImplemented() = throw AssertionError("Missing send result")
}

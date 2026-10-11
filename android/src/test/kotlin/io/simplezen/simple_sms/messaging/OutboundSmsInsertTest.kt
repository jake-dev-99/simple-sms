package io.simplezen.simple_sms.messaging

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An outbound SMS row starts in the outbox and only moves to sent once the
 * carrier accepted it (UNFY-299). The platform provider takes the row's type
 * from the insert URI, so both must say outbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSmsInsertTest {

    @Test
    fun insertSms_putsTheRowInTheOutbox() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val provider = Robolectric.buildContentProvider(InsertRecorder::class.java).create("sms").get()

        val uri = MmsDatabaseWriter.insertSms(
            context,
            OutboundMessagingHandler.MessageRequestDetails(
                threadId = 3L,
                addresses = listOf("5551234"),
                body = "hi",
                flutterResult = NoReply,
            ),
        )

        assertEquals(Uri.parse("content://sms/7"), uri)
        assertEquals(Telephony.Sms.Outbox.CONTENT_URI, provider.insertedInto)
        assertEquals(context.packageName, provider.values?.getAsString(Telephony.Sms.CREATOR))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.values?.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(Telephony.Sms.STATUS_PENDING, provider.values?.getAsInteger(Telephony.Sms.STATUS))
    }

    @Test
    fun insertSms_refusedByTheProvider_throwsBeforeAnythingIsSent() {
        // A denied WRITE_SMS app-op yields a placeholder row id 0, not null.
        val context: Context = ApplicationProvider.getApplicationContext()
        val provider = Robolectric.buildContentProvider(InsertRecorder::class.java).create("sms").get()
        provider.result = Uri.parse("content://sms/outbox/0")

        assertThrows(SecurityException::class.java) {
            MmsDatabaseWriter.insertSms(
                context,
                OutboundMessagingHandler.MessageRequestDetails(
                    threadId = 3L,
                    addresses = listOf("5551234"),
                    body = "hi",
                    flutterResult = NoReply,
                ),
            )
        }
    }

    class InsertRecorder : ContentProvider() {
        var insertedInto: Uri? = null
        var values: ContentValues? = null
        var result: Uri = Uri.parse("content://sms/7")

        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            insertedInto = uri
            this.values = values
            return result
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private object NoReply : MethodChannel.Result {
        override fun success(result: Any?) = Unit
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = Unit
        override fun notImplemented() = Unit
    }
}

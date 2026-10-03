package io.simplezen.simple_sms.device

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Purpose: protect the public read-state commands at the native provider boundary (UNFY-114). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceActionsReadStateTest {
    private val smsProvider = RecordingProvider()
    private val mmsProvider = RecordingProvider()
    private val actions = DeviceActions(ApplicationProvider.getApplicationContext())

    /** Purpose: isolate each command from every other test's provider state. */
    @Before
    fun registerProviders() {
        ShadowContentResolver.registerProviderInternal("sms", smsProvider)
        ShadowContentResolver.registerProviderInternal("mms", mmsProvider)
    }

    /** Purpose: prevent equal SMS and MMS row IDs from updating both tables. */
    @Test
    fun markMessageAsUnread_targetsOnlyTheRequestedTable_andPreservesSeen() {
        for (channel in listOf("sms", "mms")) {
            smsProvider.updates.clear()
            mmsProvider.updates.clear()
            val result = invoke(MethodCall(
                "markMessageAsUnread", mapOf("messageId" to "42", "channel" to channel),
            ))

            assertEquals(listOf(true), result.successes)
            assertTrue(result.errors.isEmpty())
            val selected = if (channel == "sms") smsProvider else mmsProvider
            val other = if (channel == "sms") mmsProvider else smsProvider
            val update = selected.updates.single()
            assertTrue(other.updates.isEmpty())
            assertEquals("content://$channel", update.uri.toString())
            assertEquals("_id = ?", update.selection)
            assertEquals(listOf("42"), update.arguments)
            assertEquals(0, update.values.getAsInteger("read"))
            assertFalse(update.values.containsKey("seen"))
            assertEquals(1, update.values.size())
        }
    }

    /** Purpose: preserve the established read-and-seen behavior for both message types. */
    @Test
    fun markMessageAsRead_targetsOnlyTheRequestedTable_andSetsSeen() {
        for (channel in listOf("sms", "mms")) {
            smsProvider.updates.clear()
            mmsProvider.updates.clear()
            val result = invoke(MethodCall(
                "markMessageAsRead", mapOf("messageId" to "42", "channel" to channel),
            ))

            assertEquals(listOf(true), result.successes)
            assertTrue(result.errors.isEmpty())
            val selected = if (channel == "sms") smsProvider else mmsProvider
            val other = if (channel == "sms") mmsProvider else smsProvider
            val update = selected.updates.single()
            assertTrue(other.updates.isEmpty())
            assertEquals("content://$channel", update.uri.toString())
            assertEquals("_id = ?", update.selection)
            assertEquals(listOf("42"), update.arguments)
            assertEquals(1, update.values.getAsInteger("read"))
            assertEquals(1, update.values.getAsInteger("seen"))
            assertEquals(2, update.values.size())
        }
    }

    /** Purpose: keep unread writes confined to read rows in the selected thread. */
    @Test
    fun markConversationAsUnread_updatesBothTables_andPreservesSeen() {
        val result = invoke(MethodCall("markConversationAsUnread", "123"))

        assertEquals(listOf(true), result.successes)
        assertTrue(result.errors.isEmpty())
        assertEquals("content://sms", smsProvider.updates.single().uri.toString())
        assertEquals("content://mms", mmsProvider.updates.single().uri.toString())
        for (update in smsProvider.updates + mmsProvider.updates) {
            assertEquals("thread_id = ? AND read = 1", update.selection)
            assertEquals(listOf("123"), update.arguments)
            assertEquals(0, update.values.getAsInteger("read"))
            assertFalse(update.values.containsKey("seen"))
            assertEquals(1, update.values.size())
        }
    }

    /** Purpose: keep existing conversation reads limited to unread rows in both tables. */
    @Test
    fun markConversationAsRead_updatesBothTables_andSetsSeen() {
        val result = invoke(MethodCall("markConversationAsRead", "123"))

        assertEquals(listOf(true), result.successes)
        assertTrue(result.errors.isEmpty())
        assertEquals("content://sms", smsProvider.updates.single().uri.toString())
        assertEquals("content://mms", mmsProvider.updates.single().uri.toString())
        for (update in smsProvider.updates + mmsProvider.updates) {
            assertEquals("thread_id = ? AND read = 0", update.selection)
            assertEquals(listOf("123"), update.arguments)
            assertEquals(1, update.values.getAsInteger("read"))
            assertEquals(1, update.values.getAsInteger("seen"))
            assertEquals(2, update.values.size())
        }
    }

    /** Purpose: distinguish a missing message from a successful provider update. */
    @Test
    fun messageCommands_returnFalseWhenTheSelectedProviderUpdatesNoRows() {
        smsProvider.updatedRows = 0
        mmsProvider.updatedRows = 0
        for (method in listOf("markMessageAsRead", "markMessageAsUnread")) {
            for (channel in listOf("sms", "mms")) {
                val result = invoke(MethodCall(method, mapOf("messageId" to "42", "channel" to channel)))
                assertEquals(listOf(false), result.successes)
                assertTrue(result.errors.isEmpty())
            }
        }
    }

    /** Purpose: treat either provider's changed rows as a conversation update. */
    @Test
    fun conversationCommands_returnTrueWhenEitherTableChanges_andFalseWhenNeitherChanges() {
        for (method in listOf("markConversationAsRead", "markConversationAsUnread")) {
            for ((smsRows, mmsRows, expected) in listOf(Triple(1, 0, true), Triple(0, 1, true), Triple(0, 0, false))) {
                smsProvider.updatedRows = smsRows
                mmsProvider.updatedRows = mmsRows
                val result = invoke(MethodCall(method, "123"))
                assertEquals(listOf(expected), result.successes)
                assertTrue(result.errors.isEmpty())
            }
        }
    }

    /** Purpose: reject ambiguous message identity before any provider mutation. */
    @Test
    fun messageCommands_rejectMissingIdentityAndUnknownChannel() {
        val invalidArguments = listOf(
            null,
            mapOf("messageId" to "42"),
            mapOf("channel" to "sms"),
            mapOf("messageId" to "42", "channel" to "rcs"),
        )
        for (method in listOf("markMessageAsRead", "markMessageAsUnread")) {
            for (arguments in invalidArguments) {
                val result = invoke(MethodCall(method, arguments))
                assertEquals(listOf("INVALID_ARGUMENT"), result.errors)
                assertTrue(result.successes.isEmpty())
            }
        }
        assertTrue(smsProvider.updates.isEmpty())
        assertTrue(mmsProvider.updates.isEmpty())
    }

    /** Purpose: reject a malformed conversation ID before either table is touched. */
    @Test
    fun conversationCommands_rejectNonStringIds() {
        for (method in listOf("markConversationAsRead", "markConversationAsUnread")) {
            for (arguments in listOf(null, 123)) {
                val result = invoke(MethodCall(method, arguments))
                assertEquals(listOf("INVALID_ARGUMENT"), result.errors)
                assertTrue(result.successes.isEmpty())
            }
        }
        assertTrue(smsProvider.updates.isEmpty())
        assertTrue(mmsProvider.updates.isEmpty())
    }

    /** Purpose: surface provider denial as an error, never as a false success result. */
    @Test
    fun messageCommands_surfaceProviderFailuresForBothTables() {
        smsProvider.failure = SecurityException("SMS role required")
        mmsProvider.failure = SecurityException("SMS role required")
        val methods = mapOf("markMessageAsRead" to "MARK_READ_FAILED", "markMessageAsUnread" to "MARK_UNREAD_FAILED")
        for ((method, errorCode) in methods) {
            for (channel in listOf("sms", "mms")) {
                val result = invoke(MethodCall(method, mapOf("messageId" to "42", "channel" to channel)))
                assertEquals(listOf(errorCode), result.errors)
                assertTrue(result.successes.isEmpty())
            }
        }
    }

    /** Purpose: never report a thread update as successful when either provider fails. */
    @Test
    fun conversationCommands_surfaceFailuresFromEitherTable() {
        val methods = mapOf("markConversationAsRead" to "MARK_READ_FAILED", "markConversationAsUnread" to "MARK_UNREAD_FAILED")
        for ((method, errorCode) in methods) {
            for (provider in listOf(smsProvider, mmsProvider)) {
                smsProvider.failure = null
                mmsProvider.failure = null
                provider.failure = SecurityException("SMS role required")
                val result = invoke(MethodCall(method, "123"))
                assertEquals(listOf(errorCode), result.errors)
                assertTrue(result.successes.isEmpty())
            }
        }
    }

    /** Purpose: exercise the real method dispatcher and capture its public result. */
    private fun invoke(call: MethodCall): RecordingResult {
        val result = RecordingResult()
        actions.onMethodCall(call, result)
        return result
    }
}

/** Purpose: capture only the provider boundary; the command handler and resolver remain real. */
private class RecordingProvider : ContentProvider() {
    val updates = mutableListOf<ProviderUpdate>()
    var updatedRows = 1
    var failure: RuntimeException? = null

    /** Purpose: permit registration without allocating external resources. */
    override fun onCreate() = true

    /** Purpose: record writes so incorrect targeting or values fail the contract tests. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        failure?.let { throw it }
        updates.add(ProviderUpdate(uri, ContentValues(requireNotNull(values)), selection, selectionArgs?.toList()))
        return updatedRows
    }

    /** Purpose: fail if a read-state command unexpectedly queries the provider. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Read-state commands must not query")

    /** Purpose: fail if a read-state command unexpectedly inserts a row. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-state commands must not insert")

    /** Purpose: fail if a read-state command unexpectedly deletes a row. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-state commands must not delete")

    /** Purpose: fail if a read-state command unexpectedly inspects a MIME type. */
    override fun getType(uri: Uri): String? =
        throw UnsupportedOperationException("Read-state commands must not inspect MIME types")
}

/** Purpose: retain a snapshot of every write, independent of later ContentValues mutation. */
private data class ProviderUpdate(
    val uri: Uri,
    val values: ContentValues,
    val selection: String?,
    val arguments: List<String>?,
)

/** Purpose: distinguish successful booleans from platform errors at the Flutter boundary. */
private class RecordingResult : MethodChannel.Result {
    val successes = mutableListOf<Any?>()
    val errors = mutableListOf<String>()

    /** Purpose: retain every success callback so duplicate replies fail assertions. */
    override fun success(result: Any?) { successes.add(result) }

    /** Purpose: retain platform error codes without confusing them with false results. */
    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) { errors.add(errorCode) }

    /** Purpose: fail immediately if a public read-state command disappears. */
    override fun notImplemented() { throw AssertionError("Read-state command was not implemented") }
}

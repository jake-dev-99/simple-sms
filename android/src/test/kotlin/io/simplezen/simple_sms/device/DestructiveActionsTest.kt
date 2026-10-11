package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowRoleManager

/**
 * Purpose: pin the verified-delete contract at the native provider boundary
 * (UNFY-244) — a delete reports success only when the read-back finds no row
 * that existed before it, messages that arrive mid-delete are told apart from
 * those survivors, a message the provider doesn't return is already absent
 * (UNFY-330), and every unverifiable state surfaces as its own error code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestructiveActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = Device()
    private val sms = ScriptedProvider(device)
    private val mms = ScriptedProvider(device)
    private val mmsSms = ScriptedProvider(device)
    private val actions = DestructiveActions(context)

    /** Purpose: isolate provider state per test and start as the default SMS app. */
    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal("sms", sms)
        ShadowContentResolver.registerProviderInternal("mms", mms)
        ShadowContentResolver.registerProviderInternal("mms-sms", mmsSms)
        roleManager().apply {
            addAvailableRole(RoleManager.ROLE_SMS)
            addHeldRole(RoleManager.ROLE_SMS)
        }
    }

    // ── deleteThread ──────────────────────────────────────────────────

    /** Purpose: snapshot, delete through the conversation URI, and read back every place the thread lives. */
    @Test
    fun deleteThread_marksDeletesViaConversationUri_andReadsBackMessagesAndThreadRow() {
        sms.rowsBefore = rows(5L, 6L)
        mms.rowsBefore = rows(9L)
        mmsSms.deletedRows = 3

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf<Any?>("deleted"), result.successes)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("content://mms-sms/conversations/5"), mmsSms.deletes.map { it.toString() })
        assertTrue(sms.deletes.isEmpty())
        assertTrue(mms.deletes.isEmpty())
        val inThread = { table: String -> ProviderQuery("content://$table", "thread_id = ?", listOf("5")) }
        assertEquals(listOf(inThread("sms"), inThread("sms")), sms.queries) // snapshot, then read-back
        assertEquals(listOf(inThread("mms"), inThread("mms")), mms.queries)
        assertEquals(
            ProviderQuery("content://mms-sms/conversations?simple=true", "_id = ?", listOf("5")),
            mmsSms.queries.single(),
        )
    }

    /** Purpose: report a thread with nothing to delete and nothing left as already gone. */
    @Test
    fun deleteThread_nothingDeletedNothingLeft_isAlreadyAbsent() {
        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf<Any?>("alreadyAbsent"), result.successes)
        assertTrue(result.errors.isEmpty())
    }

    /** Purpose: never report success while a message that existed before the delete survives. */
    @Test
    fun deleteThread_preexistingMessageSurvives_isIncomplete() {
        for (survivor in listOf(sms, mms)) {
            listOf(sms, mms, mmsSms).forEach { it.reset() }
            survivor.rowsBefore = rows(5L, 6L)
            survivor.rowsAfter = rows(6L)

            val result = invoke(MethodCall("deleteThread", "5"))

            assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
            assertTrue(result.successes.isEmpty())
        }
    }

    /** Purpose: an unpruned, empty thread row would still list the conversation — not a success. */
    @Test
    fun deleteThread_emptyThreadRowLeft_isIncomplete() {
        sms.rowsBefore = rows(5L)
        mmsSms.deletedRows = 1
        mmsSms.rowsAfter = rows(5L) // the threads row, still listed with no messages

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
    }

    /**
     * Purpose: a thread whose messages the per-user subscription filter hides
     * (Codex P2) — the delete and the message reads see nothing, but the
     * unfiltered `threads` row outlives the hidden messages, so it is never
     * reported as already absent.
     */
    @Test
    fun deleteThread_messagesHiddenBySubscriptionFilter_isIncomplete() {
        mmsSms.rowsAfter = rows(5L) // the threads row, kept by the hidden messages

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
        assertTrue(result.successes.isEmpty())
    }

    /** Purpose: messages that arrive mid-delete are reported as new arrivals, not survivors. */
    @Test
    fun deleteThread_onlyMessagesAbsentFromTheSnapshotRemain_isNewMessagesArrived() {
        sms.rowsBefore = rows(5L, 6L)
        mms.rowsBefore = rows(9L)
        mmsSms.deletedRows = 3
        sms.rowsAfter = rows(7L) // not in the snapshot
        mms.rowsAfter = rows(10L)
        mmsSms.rowsAfter = rows(5L) // the thread is listed again because of them

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf<Any?>("newMessagesArrived"), result.successes)
        assertTrue(result.errors.isEmpty())
    }

    /** Purpose: a survivor is never masked by new arrivals alongside it. */
    @Test
    fun deleteThread_survivorBesideNewArrivals_isIncomplete() {
        sms.rowsBefore = rows(5L, 6L)
        sms.rowsAfter = rows(6L, 7L)

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
    }

    /**
     * Purpose: `sms._id` is a plain INTEGER PRIMARY KEY, so a reply can reuse the
     * id of a just-deleted message; its own `date` still marks it as new.
     */
    @Test
    fun deleteThread_newMessageReusingADeletedId_isAnArrival() {
        sms.rowsBefore = listOf(Row(5L, 1_000L), Row(6L, 2_000L))
        mmsSms.deletedRows = 2
        sms.rowsAfter = listOf(Row(6L, 9_000L)) // reused _id, new receive time
        mmsSms.rowsAfter = rows(5L)

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf<Any?>("newMessagesArrived"), result.successes)
    }

    /** Purpose: an MMS whose `date` the app rewrote after insert is still the same, surviving row. */
    @Test
    fun deleteThread_survivingMmsWithRewrittenDate_isIncomplete() {
        mms.rowsBefore = listOf(Row(9L, 1_000L)) // PDU date
        mms.rowsAfter = listOf(Row(9L, 1_700L)) // local receive time, written later
        mmsSms.rowsAfter = rows(5L)

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
    }

    /** Purpose: arrivals under a thread row the list no longer shows aren't a live thread. */
    @Test
    fun deleteThread_arrivalsWithoutAListedThreadRow_isIncomplete() {
        sms.rowsBefore = rows(5L)
        mmsSms.deletedRows = 1
        sms.rowsAfter = rows(7L) // threads row 5 not listed

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
    }

    /** Purpose: an OEM provider returning `_id` as text is still read; a non-numeric one is unverifiable. */
    @Test
    fun deleteThread_readsTextIdsAndRejectsNonNumericOnes() {
        sms.rowsBefore = rows("5")
        sms.rowsAfter = rows("7")
        mmsSms.rowsAfter = rows(5L)
        assertEquals(listOf<Any?>("newMessagesArrived"), invoke(MethodCall("deleteThread", "5")).successes)

        listOf(sms, mms, mmsSms).forEach { it.reset() }
        sms.rowsAfter = rows("abc")
        assertEquals(listOf("VERIFY_FAILED"), invoke(MethodCall("deleteThread", "5")).errors)
    }

    /** Purpose: never read, delete — or report success — without the default-SMS role. */
    @Test
    fun deleteThread_withoutSmsRole_failsBeforeTouchingTheProvider() {
        roleManager().removeHeldRole(RoleManager.ROLE_SMS)

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("NOT_DEFAULT_SMS_APP"), result.errors)
        assertTrue(mmsSms.deletes.isEmpty())
        assertTrue(sms.queries.isEmpty())
    }

    /** Purpose: without a snapshot nothing is deleted, and the failure says so. */
    @Test
    fun deleteThread_unreadableSnapshot_failsBeforeDeleting() {
        sms.nullCursorBefore = true

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_FAILED"), result.errors)
        assertTrue(mmsSms.deletes.isEmpty())
    }

    /** Purpose: surface a provider failure during the delete itself. */
    @Test
    fun deleteThread_providerThrows_isDeleteFailed() {
        mmsSms.deleteFailure = IllegalStateException("provider error")

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("DELETE_FAILED"), result.errors)
        assertTrue(result.successes.isEmpty())
    }

    /** Purpose: a read-back that returns no cursor or is refused can't confirm the delete. */
    @Test
    fun deleteThread_unreadableReadBack_isVerifyFailed() {
        for (provider in listOf(sms, mms, mmsSms)) {
            for (failure in listOf<(ScriptedProvider) -> Unit>(
                { it.nullCursorAfter = true },
                { it.queryFailureAfter = SecurityException("READ_SMS revoked") },
            )) {
                listOf(sms, mms, mmsSms).forEach { it.reset() }
                failure(provider)

                val result = invoke(MethodCall("deleteThread", "5"))

                assertEquals(listOf("VERIFY_FAILED"), result.errors)
                assertTrue(result.successes.isEmpty())
            }
        }
    }

    /** Purpose: reject ids that match no row — they would read back as already gone. */
    @Test
    fun deleteThread_rejectsNonPositiveNumericIds() {
        for (arguments in listOf(null, 5, "", "0", "-1", "6f1c2e7a-uuid")) {
            val result = invoke(MethodCall("deleteThread", arguments))

            assertEquals(listOf("INVALID_ARGUMENT"), result.errors)
            assertTrue(result.successes.isEmpty())
        }
        assertTrue(mmsSms.deletes.isEmpty())
    }

    // ── deleteMessage ─────────────────────────────────────────────────

    /** Purpose: delete and read back exactly the one message in the selected table. */
    @Test
    fun deleteMessage_targetsAndVerifiesOnlyTheSelectedTable() {
        for ((channel, selected, other) in listOf(Triple("sms", sms, mms), Triple("mms", mms, sms))) {
            listOf(sms, mms).forEach { it.reset() }
            selected.deletedRows = 1

            val result = invoke(MethodCall("deleteMessage", mapOf("messageId" to "42", "channel" to channel)))

            assertEquals(listOf<Any?>("deleted"), result.successes)
            assertEquals(listOf("content://$channel/42"), selected.deletes.map { it.toString() })
            assertEquals(ProviderQuery("content://$channel/42", null, null), selected.queries.single())
            assertTrue(other.deletes.isEmpty())
            assertTrue(other.queries.isEmpty())
        }
    }

    /**
     * Purpose: a message the provider doesn't return is deleted as far as this
     * app is concerned (UNFY-330) — from Android 14 that includes one the
     * per-user subscription filter hides from the delete and the read alike.
     */
    @Test
    fun deleteMessage_nothingDeletedNothingLeft_isAlreadyAbsent() {
        for (channel in listOf("sms", "mms")) {
            listOf(sms, mms).forEach { it.reset() }

            val result = invoke(MethodCall("deleteMessage", mapOf("messageId" to "42", "channel" to channel)))

            assertEquals(listOf<Any?>("alreadyAbsent"), result.successes)
            assertTrue(result.errors.isEmpty())
        }
    }

    /** Purpose: map each unverified state after the delete for a single message. */
    @Test
    fun deleteMessage_classifiesTheReadBack() {
        val call = MethodCall("deleteMessage", mapOf("messageId" to "42", "channel" to "sms"))

        sms.rowsAfter = rows(42L)
        assertEquals(listOf("DELETE_INCOMPLETE"), invoke(call).errors)

        sms.reset()
        // The existence read parses nothing, so an OEM's text `date` can't
        // turn a surviving message into a read-back failure.
        sms.rowsAfter = listOf(Row(42L, date = "2024-01-01"))
        assertEquals(listOf("DELETE_INCOMPLETE"), invoke(call).errors)

        sms.reset()
        sms.nullCursorAfter = true
        assertEquals(listOf("VERIFY_FAILED"), invoke(call).errors)
    }

    /** Purpose: never delete a message without the default-SMS role. */
    @Test
    fun deleteMessage_withoutSmsRole_failsBeforeDeleting() {
        roleManager().removeHeldRole(RoleManager.ROLE_SMS)

        val result = invoke(MethodCall("deleteMessage", mapOf("messageId" to "42", "channel" to "sms")))

        assertEquals(listOf("NOT_DEFAULT_SMS_APP"), result.errors)
        assertTrue(sms.deletes.isEmpty())
    }

    /** Purpose: reject ambiguous or row-less message identity before any provider mutation. */
    @Test
    fun deleteMessage_rejectsInvalidArguments() {
        for (arguments in listOf(
            null,
            mapOf("messageId" to "42"),
            mapOf("channel" to "sms"),
            mapOf("messageId" to "42", "channel" to "rcs"),
            mapOf("messageId" to "0", "channel" to "sms"),
            mapOf("messageId" to "abc", "channel" to "mms"),
        )) {
            val result = invoke(MethodCall("deleteMessage", arguments))

            assertEquals(listOf("INVALID_ARGUMENT"), result.errors)
            assertTrue(result.successes.isEmpty())
        }
        assertTrue(sms.deletes.isEmpty())
        assertTrue(mms.deletes.isEmpty())
    }

    private fun roleManager(): ShadowRoleManager =
        Shadow.extract(context.getSystemService(RoleManager::class.java))

    /** Purpose: exercise the real method dispatcher and capture its public result. */
    private fun invoke(call: MethodCall): CapturedResult {
        device.deleted = false
        val result = CapturedResult()
        actions.onMethodCall(call, result)
        return result
    }
}

/** Purpose: one provider row — its `_id` and `date` (either may be text, as on some OEMs). */
private data class Row(val id: Any, val date: Any? = 1_000L)

/** Purpose: rows with the given ids and one shared `date`. */
private fun rows(vararg ids: Any): List<Row> = ids.map { Row(it) }

/** Purpose: provider state shared across authorities — whether the delete has run yet. */
private class Device {
    var deleted = false
}

/**
 * Purpose: script one provider authority — the rows it holds before and after
 * the delete, and its failures; the handler and resolver stay real.
 */
private class ScriptedProvider(private val device: Device) : ContentProvider() {
    val deletes = mutableListOf<Uri>()
    val queries = mutableListOf<ProviderQuery>()
    var rowsBefore: List<Row> = emptyList()
    var rowsAfter: List<Row> = emptyList()
    var deletedRows = 0
    var deleteFailure: RuntimeException? = null
    var nullCursorBefore = false
    var nullCursorAfter = false
    var queryFailureAfter: RuntimeException? = null

    /** Purpose: restore the "nothing held, nothing left" baseline between scenarios. */
    fun reset() {
        deletes.clear()
        queries.clear()
        rowsBefore = emptyList()
        rowsAfter = emptyList()
        deletedRows = 0
        deleteFailure = null
        nullCursorBefore = false
        nullCursorAfter = false
        queryFailureAfter = null
    }

    /** Purpose: permit registration without allocating external resources. */
    override fun onCreate() = true

    /** Purpose: record the delete target and flip the device to its post-delete state. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleteFailure?.let { throw it }
        deletes.add(uri)
        device.deleted = true
        return deletedRows
    }

    /** Purpose: answer with the rows held before or after the delete, a null cursor, or a failure. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (device.deleted) queryFailureAfter?.let { throw it }
        queries.add(ProviderQuery(uri.toString(), selection, selectionArgs?.toList()))
        if (if (device.deleted) nullCursorAfter else nullCursorBefore) return null
        return MatrixCursor(arrayOf("_id", "date")).apply {
            (if (device.deleted) rowsAfter else rowsBefore).forEach { addRow(arrayOf(it.id, it.date)) }
        }
    }

    /** Purpose: fail if a delete unexpectedly inserts a row. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Deletes must not insert")

    /** Purpose: fail if a delete unexpectedly updates a row. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Deletes must not update")

    /** Purpose: fail if a delete unexpectedly inspects a MIME type. */
    override fun getType(uri: Uri): String? =
        throw UnsupportedOperationException("Deletes must not inspect MIME types")
}

/** Purpose: snapshot one query's target and filter. */
private data class ProviderQuery(val uri: String, val selection: String?, val arguments: List<String>?)

/** Purpose: capture the handler's single reply, keeping success values apart from error codes. */
private class CapturedResult : MethodChannel.Result {
    val successes = mutableListOf<Any?>()
    val errors = mutableListOf<String>()

    override fun success(result: Any?) { successes.add(result) }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) { errors.add(errorCode) }

    override fun notImplemented() { throw AssertionError("Destructive command was not implemented") }
}

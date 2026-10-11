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
 * (UNFY-244) — a delete reports success only when the read-back finds nothing
 * left, and every unverifiable state surfaces as its own error code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestructiveActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sms = ScriptedProvider()
    private val mms = ScriptedProvider()
    private val mmsSms = ScriptedProvider()
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

    /** Purpose: delete through the conversation URI and read back every place the thread lives. */
    @Test
    fun deleteThread_deletesViaConversationUri_andReadsBackMessagesAndThreadRow() {
        mmsSms.deletedRows = 3

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf<Any?>("deleted"), result.successes)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("content://mms-sms/conversations/5"), mmsSms.deletes.map { it.toString() })
        assertTrue(sms.deletes.isEmpty())
        assertTrue(mms.deletes.isEmpty())
        assertEquals(ProviderQuery("content://sms", "thread_id = ?", listOf("5")), sms.queries.single())
        assertEquals(ProviderQuery("content://mms", "thread_id = ?", listOf("5")), mms.queries.single())
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

    /** Purpose: never report success while messages or the thread row survive. */
    @Test
    fun deleteThread_anythingLeft_isIncomplete() {
        for (survivor in listOf(sms, mms, mmsSms)) {
            listOf(sms, mms, mmsSms).forEach { it.remainingRows = 0 }
            survivor.remainingRows = 1

            val result = invoke(MethodCall("deleteThread", "5"))

            assertEquals(listOf("DELETE_INCOMPLETE"), result.errors)
            assertTrue(result.successes.isEmpty())
        }
    }

    /** Purpose: never delete — or report success — without the default-SMS role. */
    @Test
    fun deleteThread_withoutSmsRole_failsBeforeDeleting() {
        roleManager().removeHeldRole(RoleManager.ROLE_SMS)

        val result = invoke(MethodCall("deleteThread", "5"))

        assertEquals(listOf("NOT_DEFAULT_SMS_APP"), result.errors)
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
                { it.nullCursor = true },
                { it.queryFailure = SecurityException("READ_SMS revoked") },
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

    /** Purpose: map each verified state for a single message. */
    @Test
    fun deleteMessage_classifiesTheReadBack() {
        val call = MethodCall("deleteMessage", mapOf("messageId" to "42", "channel" to "sms"))

        assertEquals(listOf<Any?>("alreadyAbsent"), invoke(call).successes)

        sms.remainingRows = 1
        assertEquals(listOf("DELETE_INCOMPLETE"), invoke(call).errors)

        sms.reset()
        sms.nullCursor = true
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
        val result = CapturedResult()
        actions.onMethodCall(call, result)
        return result
    }
}

/** Purpose: script one provider authority's deletes and read-backs; the handler and resolver stay real. */
private class ScriptedProvider : ContentProvider() {
    val deletes = mutableListOf<Uri>()
    val queries = mutableListOf<ProviderQuery>()
    var deletedRows = 0
    var deleteFailure: RuntimeException? = null
    var remainingRows = 0
    var nullCursor = false
    var queryFailure: RuntimeException? = null

    /** Purpose: restore the "nothing deleted, nothing left" baseline between scenarios. */
    fun reset() {
        deletes.clear()
        queries.clear()
        deletedRows = 0
        deleteFailure = null
        remainingRows = 0
        nullCursor = false
        queryFailure = null
    }

    /** Purpose: permit registration without allocating external resources. */
    override fun onCreate() = true

    /** Purpose: record the delete target so mistargeted deletes fail the contract tests. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleteFailure?.let { throw it }
        deletes.add(uri)
        return deletedRows
    }

    /** Purpose: answer the read-back with the scripted remaining rows, a null cursor, or a failure. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queryFailure?.let { throw it }
        queries.add(ProviderQuery(uri.toString(), selection, selectionArgs?.toList()))
        if (nullCursor) return null
        return MatrixCursor(arrayOf("_id")).apply {
            repeat(remainingRows) { addRow(arrayOf<Any>(it + 1L)) }
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

/** Purpose: snapshot one read-back query's target and filter. */
private data class ProviderQuery(val uri: String, val selection: String?, val arguments: List<String>?)

/** Purpose: capture the handler's single reply, keeping success values apart from error codes. */
private class CapturedResult : MethodChannel.Result {
    val successes = mutableListOf<Any?>()
    val errors = mutableListOf<String>()

    override fun success(result: Any?) { successes.add(result) }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) { errors.add(errorCode) }

    override fun notImplemented() { throw AssertionError("Destructive command was not implemented") }
}

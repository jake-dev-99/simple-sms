package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.messaging.OutboundSendCorrelations
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowRoleManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Purpose: pin bounded, durable logical deletion at the real SQLite/provider boundary (UNFY-346). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicalSendDeletionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rows = linkedMapOf<String, ContentValues>()
    private val deletes = mutableListOf<String>()
    private var refuse: String? = null
    private var queries = 0
    private var beforeDelete: (() -> Unit)? = null
    private var afterDelete: (() -> Unit)? = null
    private val store get() = OutboundSendCorrelations(context)

    /** Purpose: isolate provider storage and default-SMS authority for each test. */
    @Before fun setUp() {
        val role = Shadow.extract<ShadowRoleManager>(context.getSystemService(RoleManager::class.java))
        role.addAvailableRole(RoleManager.ROLE_SMS)
        role.addHeldRole(RoleManager.ROLE_SMS)
        for (table in listOf("sms", "mms")) {
            ShadowContentResolver.registerProviderInternal(table, RowsProvider())
        }
    }

    /** A reused MMS row ID must never authorize deletion of another PDU. */
    @Test fun reusedMmsIdentitySurvivesDeletion() {
        record("content://mms/100")
        rows.getValue("content://mms/100").put("tr_id", "replacement")
        assertTrue(store.lookup(listOf("content://mms/100")).isEmpty())
        assertEquals("INVALID_ARGUMENT", delete("content://mms/100").error)
        assertTrue(rows.containsKey("content://mms/100"))
        assertTrue(deletes.isEmpty())
    }

    /** Conditional MMS writes must reject replacement after the last identity read. */
    @Test fun mmsReusedDuringDeleteIsPreserved() {
        record("content://mms/100")
        beforeDelete = { rows.getValue("content://mms/100").put("tr_id", "replacement") }
        val receipt = delete("content://mms/100").receipt()
        assertEquals(true, receipt["complete"])
        assertEquals("replacement", rows.getValue("content://mms/100").getAsString("tr_id"))
    }

    /** Missing MMS occurrence proof cannot create a deletion fence or a provider write. */
    @Test fun missingMmsTransactionFailsClosed() {
        record("content://mms/100")
        rows.getValue("content://mms/100").putNull("tr_id")
        assertEquals("DELETE_FAILED", delete("content://mms/100").error)
        assertTrue(deletes.isEmpty())
        assertTrue(pending().isEmpty())
    }

    /** Older date-based readers must reject transaction-proof stores even after rows are gone. */
    @Test fun mmsProofPermanentlyFencesOlderReaders() {
        record("content://mms/100")
        database { db ->
            assertThrows(IllegalStateException::class.java) {
                check(db.version in 1..2) { "Older reader cannot interpret MMS occurrence proof" }
            }
        }
        delete("content://mms/100").receipt()
        acknowledge(listOf("content://mms/100")).requireSuccess()
        assertTrue(pending().isEmpty())
        database { assertEquals(3, it.version) }
    }

    /** Import old local-build cancellation records before any retry can create a provider row. */
    @Test fun legacyLogicalDeletionFencesAreImportedWithoutLosingReceipts() {
        record("content://sms/100")
        delete("content://sms/100").receipt()
        database {
            it.execSQL("DELETE FROM deleted_sends")
            it.execSQL("DROP TABLE correlation_migrations")
            it.version = 2
        }
        val restarted = OutboundSendCorrelations(context)
        assertThrows(IllegalStateException::class.java) {
            restarted.withAttempt("job" to 1) { fail("Old logical deletion fence was lost") }
        }
        assertEquals(listOf("content://sms/100"), pending().single()["confirmedMessageUris"])
        assertThrows(IllegalStateException::class.java) {
            restarted.withAttempt("job" to 1) { fail("Restart bypassed imported fence") }
        }
    }

    /** Purpose: stale UI targets must still leave the actual latest attempt until last. */
    @Test fun staleTargetDeletesAllRecordedAttemptsLatestLast() {
        for (id in 100..102) record("content://sms/$id")
        row("content://sms/200")
        row("content://mms/100")
        val receipt = delete("content://sms/100").receipt()
        assertEquals(listOf("content://sms/100", "content://sms/101", "content://sms/102"), deletes)
        assertEquals(deletes, receipt["confirmedMessageUris"])
        assertEquals(true, receipt["complete"])
        assertEquals(setOf("content://sms/200", "content://mms/100"), rows.keys)
    }

    /** Purpose: MMS date rewrites do not change an AUTOINCREMENT provider identity. */
    @Test fun rewrittenMmsDateRetainsOwnershipAndDeletesTheSamePdu() {
        record("content://mms/100")
        rows.getValue("content://mms/100").put("date", 2000L)
        assertEquals("job", store.lookup(listOf("content://mms/100"))["content://mms/100"]?.get("clientCorrelationId"))
        assertEquals(true, delete("content://mms/100").receipt()["complete"])
        assertEquals(listOf("content://mms/100"), deletes)
        assertTrue(rows.isEmpty())
    }

    /** Purpose: an MMS timestamp update between read and delete must not retain the PDU. */
    @Test fun mmsDateChangingDuringConditionalDeleteStillDeletesThePdu() {
        record("content://mms/100")
        beforeDelete = { rows.getValue("content://mms/100").put("date", 2000L) }
        assertEquals(true, delete("content://mms/100").receipt()["complete"])
        assertTrue(rows.isEmpty())
    }

    /** Purpose: a conditional-delete miss after thread movement is not proof of removal. */
    @Test fun aCapturedMmsMovingThreadsDuringDeleteIsNotConfirmedGone() {
        record("content://mms/100")
        beforeDelete = { rows.getValue("content://mms/100").put("thread_id", 8L) }
        val receipt = delete("content://mms/100").receipt()
        assertEquals(false, receipt["complete"])
        assertEquals("DELETE_INCOMPLETE", receipt["failureCode"])
        assertEquals(emptyList<String>(), receipt["confirmedMessageUris"])
        assertTrue(rows.containsKey("content://mms/100"))
    }

    /** Purpose: MMS identity and deletion do not depend on optional or mutable dates. */
    @Test fun mmsWithoutDateCanBeRecordedAndDeleted() {
        row("content://mms/100")
        rows.getValue("content://mms/100").putNull("date")
        store.record(Uri.parse("content://mms/100"), "job")
        assertEquals(true, delete("content://mms/100").receipt()["complete"])
        assertTrue(rows.isEmpty())
    }

    /** Legacy date/row-ID proofs must not adopt a replacement PDU during migration. */
    @Test fun legacyMmsGenerationsFailClosedWithoutRebinding() {
        record("content://mms/100")
        database { db ->
            db.execSQL("UPDATE correlations SET generation='1000'")
            db.version = 1
        }
        rows.getValue("content://mms/100").put("tr_id", "replacement")
        assertThrows(IllegalStateException::class.java) { store.lookup(listOf("content://mms/100")) }
        assertEquals("DELETE_FAILED", delete("content://mms/100").error)
        assertTrue(deletes.isEmpty())
        assertTrue(rows.containsKey("content://mms/100"))
        database { db ->
            db.rawQuery("SELECT generation FROM correlations", null).use {
                assertTrue(it.moveToFirst()); assertEquals("1000", it.getString(0))
            }
        }
    }

    /** Purpose: partial provider failure preserves latest and confirmations survive reopening until acknowledged. */
    @Test fun partialFailureResumesAndReceiptsSurviveUntilAcknowledged() {
        for (id in 100..102) record("content://sms/$id")
        refuse = "content://sms/101"
        val partial = delete("content://sms/100").receipt()
        assertEquals(false, partial["complete"])
        assertEquals("DELETE_FAILED", partial["failureCode"])
        assertEquals(listOf("content://sms/100"), partial["confirmedMessageUris"])
        assertEquals(setOf("content://sms/101", "content://sms/102"), rows.keys)
        assertEquals(listOf("content://sms/100"), pending().single()["confirmedMessageUris"])
        acknowledge(listOf("content://sms/100")).requireSuccess()
        assertEquals(emptyList<String>(), pending().single()["confirmedMessageUris"])
        refuse = null
        val complete = delete("content://sms/100").receipt()
        assertEquals(true, complete["complete"])
        assertEquals(listOf("content://sms/101", "content://sms/102"), complete["confirmedMessageUris"])
        acknowledge(listOf("content://sms/101", "content://sms/102")).requireSuccess()
        assertTrue(pending().isEmpty())
        var handoffs = 0
        assertThrows(IllegalStateException::class.java) { store.withAttempt("job" to 1) { handoffs++ } }
        assertEquals(0, handoffs)
    }

    /** Purpose: a recovered completion with already-acked rows can retire its empty receipt. */
    @Test fun completionAfterAcknowledgementAcceptsAnEmptyIdempotentAck() {
        record("content://sms/100")
        database { it.execSQL("CREATE TRIGGER fail_completion BEFORE UPDATE OF complete ON send_deletions " +
            "WHEN NEW.complete=1 BEGIN SELECT RAISE(ABORT, 'injected completion failure'); END") }
        assertEquals("DELETE_FAILED", delete("content://sms/100").error)
        acknowledge(listOf("content://sms/100")).requireSuccess()
        database { it.execSQL("DROP TRIGGER fail_completion") }
        val receipt = delete("content://sms/100").receipt()
        assertEquals(true, receipt["complete"])
        assertEquals(emptyList<String>(), receipt["confirmedMessageUris"])
        acknowledge(emptyList()).requireSuccess()
        assertTrue(pending().isEmpty())
    }

    /** Purpose: a lost acknowledgement response must not make a successful deletion fail on retry. */
    @Test fun acknowledgementIsIdempotentAfterTheRecoveryBacklogIsCleared() {
        record("content://sms/100")
        delete("content://sms/100").receipt()
        acknowledge(listOf("content://sms/100")).requireSuccess()
        assertTrue(pending().isEmpty())
        acknowledge(listOf("content://sms/100")).requireSuccess()
        assertEquals("INVALID_ARGUMENT", acknowledge(listOf("content://sms/999")).error)
    }

    /** Purpose: a host cannot acknowledge an unconfirmed deletion and lose its retry evidence. */
    @Test fun unconfirmedAcknowledgementFailsWithoutDroppingConfirmedReceipts() {
        for (id in 100..102) record("content://sms/$id")
        refuse = "content://sms/101"
        delete("content://sms/102").receipt()
        assertEquals("INVALID_ARGUMENT", acknowledge(listOf("content://sms/100", "content://sms/101")).error)
        assertEquals(listOf("content://sms/100"), pending().single()["confirmedMessageUris"])
    }

    /** Purpose: a physical delete followed by a failed confirmation commit remains recoverable. */
    @Test fun failedConfirmationCommitRecoversTheAbsentCapturedGeneration() {
        record("content://sms/100")
        record("content://sms/101")
        afterDelete = {
            database { it.execSQL("CREATE TRIGGER fail_confirmation BEFORE UPDATE OF confirmed ON send_deletion_rows BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END") }
            afterDelete = null
        }
        val first = delete("content://sms/101")
        assertEquals("DELETE_FAILED", first.error)
        assertFalse(rows.containsKey("content://sms/100"))
        assertTrue(rows.containsKey("content://sms/101"))
        assertEquals(false, pending().single()["complete"])
        database { it.execSQL("DROP TRIGGER fail_confirmation") }
        val recovered = delete("content://sms/101").receipt()
        assertEquals(listOf("content://sms/100", "content://sms/101"), recovered["confirmedMessageUris"])
        assertEquals(true, recovered["complete"])
    }

    /** Purpose: unknown generations must fail before either cancellation or a provider write. */
    @Test fun nullGenerationFailsPreflightWithoutDroppingAnOlderAttempt() {
        record("content://sms/100")
        record("content://sms/101")
        rows.getValue("content://sms/100").putNull("date")
        assertEquals("DELETE_FAILED", delete("content://sms/101").error)
        assertTrue(deletes.isEmpty())
        var handoffs = 0
        store.withAttempt("job" to 1) { handoffs++ }
        assertEquals(1, handoffs)
    }

    /** Purpose: conditional writes must preserve a provider ID reused after the pre-delete read. */
    @Test fun reusedGenerationIsNotPhysicallyDeleted() {
        record("content://sms/100")
        beforeDelete = {
            rows.getValue("content://sms/100").put("date", 2000L)
            beforeDelete = null
        }
        val receipt = delete("content://sms/100").receipt()
        assertEquals(true, receipt["complete"])
        assertEquals(2000L, rows.getValue("content://sms/100").getAsLong("date"))
    }

    /** Purpose: a logical job cannot erase another conversation by presenting its row URI. */
    @Test fun threadMismatchFailsBeforeEveryWrite() {
        record("content://sms/100")
        row("content://sms/101", thread = 8)
        store.record(Uri.parse("content://sms/101"), "job")
        assertEquals("DELETE_FAILED", delete("content://sms/100").error)
        assertTrue(deletes.isEmpty())
    }

    /** Purpose: job-index work stays bounded independently of conversation length. */
    @Test fun completeHistoryIsReadThroughTheJobIndexWithoutThreadEnumeration() {
        for (id in 1..64) record("content://sms/$id")
        for (id in 1000..31000) row("content://sms/$id")
        queries = 0
        assertEquals(true, delete("content://sms/1").receipt()["complete"])
        assertEquals(64, deletes.size)
        assertEquals(30001, rows.size)
        assertTrue("Only retained job rows may be verified: $queries", queries <= 200)
    }

    /** Purpose: a competing engine cannot allocate another attempt after deletion's durable fence. */
    @Test fun concurrentRetryCannotPassTheDeletionFence() {
        record("content://sms/100")
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        var handoffs = 0
        var retry: java.util.concurrent.Future<*>? = null
        beforeDelete = {
            beforeDelete = null
            retry = executor.submit {
                started.countDown()
                assertThrows(IllegalStateException::class.java) { store.withAttempt("job" to 1) { handoffs++ } }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
        }
        try {
            delete("content://sms/100").receipt()
            requireNotNull(retry).get(2, TimeUnit.SECONDS)
            assertEquals(0, handoffs)
        } finally { executor.shutdownNow() }
    }

    /** Purpose: loss of the default SMS role cannot create a cancellation or erase provider data. */
    @Test fun missingRoleRejectsBeforeMutation() {
        record("content://sms/100")
        Shadow.extract<ShadowRoleManager>(context.getSystemService(RoleManager::class.java))
            .removeHeldRole(RoleManager.ROLE_SMS)
        assertEquals("NOT_DEFAULT_SMS_APP", delete("content://sms/100").error)
        assertTrue(deletes.isEmpty())
        assertTrue(pending().isEmpty())
    }

    /** Purpose: keep journal paging bounds explicit instead of treating malformed reads as empty. */
    @Test fun invalidPendingLimitIsRejected() {
        assertEquals("INVALID_ARGUMENT", invoke("pendingSendDeletions", mapOf("limit" to 0)).error)
        assertEquals("INVALID_ARGUMENT", invoke("pendingSendDeletions", mapOf("limit" to 65)).error)
    }

    /** Purpose: pending work is bounded and rejects overflow before cancelling or deleting a new job. */
    @Test fun pendingBackpressureAndPagingDoNotScaleWithDeletedHistory() {
        for (id in 1..64) {
            val uri = "content://sms/$id"
            val job = "job-${id.toString().padStart(3, '0')}"
            row(uri)
            store.record(Uri.parse(uri), job)
            invoke("deleteSend", mapOf("messageUri" to uri, "clientCorrelationId" to job, "threadId" to "7")).receipt()
        }
        @Suppress("UNCHECKED_CAST")
        val first = invoke("pendingSendDeletions", mapOf("limit" to 20)).value as List<Map<String, Any?>>
        assertEquals(20, first.size)
        assertEquals("job-001", first.first()["clientCorrelationId"])
        @Suppress("UNCHECKED_CAST")
        val rest = invoke("pendingSendDeletions", mapOf("limit" to 64,
            "afterClientCorrelationId" to first.last()["clientCorrelationId"])).value as List<Map<String, Any?>>
        assertEquals(44, rest.size)
        assertEquals("job-021", rest.first()["clientCorrelationId"])
        row("content://sms/65")
        store.record(Uri.parse("content://sms/65"), "overflow")
        assertEquals("DELETE_FAILED", invoke("deleteSend", mapOf("messageUri" to "content://sms/65",
            "clientCorrelationId" to "overflow", "threadId" to "7")).error)
        assertTrue(rows.containsKey("content://sms/65"))
        var allowed = false
        store.withAttempt("overflow" to 1) { allowed = true }
        assertTrue(allowed)
    }

    /** Purpose: initialize a provider occurrence without storing message content in the ledger. */
    private fun row(uri: String, thread: Long = 7) {
        rows[uri] = ContentValues().apply {
            put("_id", Uri.parse(uri).lastPathSegment!!.toLong()); put("date", 1000L); put("thread_id", thread)
            if (Uri.parse(uri).authority == "mms") put("tr_id", "transaction-${Uri.parse(uri).lastPathSegment}")
        }
    }
    /** Purpose: persist a real correlation for the provider occurrence under test. */
    private fun record(uri: String) { row(uri); store.record(Uri.parse(uri), "job") }
    /** Purpose: invoke a logical delete through the actual background-channel handler. */
    private fun delete(uri: String) = invoke("deleteSend", mapOf(
        "messageUri" to uri, "clientCorrelationId" to "job", "threadId" to "7"))
    /** Purpose: reopen the native journal on every read to prove durable recovery. */
    @Suppress("UNCHECKED_CAST")
    private fun pending(): List<Map<String, Any?>> =
        invoke("pendingSendDeletions", mapOf("limit" to 20)).also { it.requireSuccess() }.value as List<Map<String, Any?>>
    /** Purpose: acknowledge only the exact confirmed URI identities supplied by the host. */
    private fun acknowledge(uris: List<String>) = invoke("acknowledgeSendDeletion", mapOf(
        "clientCorrelationId" to "job", "messageUris" to uris))
    /** Purpose: exercise the public method dispatch rather than a test-only mutation seam. */
    private fun invoke(method: String, args: Any?): Answer = Answer().also {
        DestructiveActions(context).onMethodCall(MethodCall(method, args), it)
    }
    /** Purpose: inject SQLite I/O failure after an irreversible provider write. */
    private fun database(action: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "outbound_send_correlations.db").path, null, 0).use(action)
    }

    /** Purpose: record method-channel results while keeping unknown-method failures observable. */
    private class Answer : MethodChannel.Result {
        var value: Any? = null
        var error: String? = null
        var implemented = true
        override fun success(result: Any?) { value = result }
        override fun error(code: String, message: String?, details: Any?) { error = code }
        override fun notImplemented() { implemented = false }
        fun requireSuccess() { assertTrue("Native method must be implemented", implemented); assertNull(error) }
        @Suppress("UNCHECKED_CAST")
        fun receipt(): Map<String, Any?> { requireSuccess(); return value as Map<String, Any?> }
    }

    /** Purpose: model exact row queries and conditional deletes without a radio or real device. */
    private inner class RowsProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queries++
            require(uri.pathSegments.size == 1) { "A logical deletion must not enumerate a conversation: $uri" }
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply { rows[uri.toString()]?.let { r -> addRow(columns.map { r[it] }.toTypedArray()) } }
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletes.add(uri.toString())
            if (uri.toString() == refuse) throw IllegalStateException("injected provider failure")
            beforeDelete?.invoke()
            val row = rows[uri.toString()] ?: return 0
            if (uri.authority == "sms") {
                assertEquals("date = ? AND thread_id = ?", selection)
                assertEquals(2, args?.size)
                if (row.getAsString("date") != args!![0] || row.getAsString("thread_id") != args[1]) return 0
            } else {
                assertEquals("tr_id = ? AND thread_id = ?", selection)
                assertEquals(2, args?.size)
                if (row.getAsString("tr_id") != args!![0] || row.getAsString("thread_id") != args[1]) return 0
            }
            rows.remove(uri.toString())
            afterDelete?.invoke()
            return 1
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected provider insert")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Unexpected provider update")
    }
}

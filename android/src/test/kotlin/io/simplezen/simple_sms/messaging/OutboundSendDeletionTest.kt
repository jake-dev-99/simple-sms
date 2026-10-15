package io.simplezen.simple_sms.messaging

import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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

/** UNFY-346: exercise logical-send deletion against real durable metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSendDeletionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = Rows()
    private val store = OutboundSendCorrelations(context)
    private val deletion = OutboundSendDeletion(context)

    @Before fun setup() {
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        Shadow.extract<ShadowRoleManager>(context.getSystemService(RoleManager::class.java)).apply {
            addAvailableRole(RoleManager.ROLE_SMS)
            addHeldRole(RoleManager.ROLE_SMS)
        }
    }

    private fun record(id: Int, job: String = "job", table: String = "sms") {
        val uri = "content://$table/$id"
        provider.rows[uri] = 1000L
        store.record(Uri.parse(uri), job)
    }

    @Test fun staleTargetKeepsActualLatestUntilOlderRowsSucceed() {
        record(70); record(2); record(40)
        provider.refuse = "content://sms/2"
        assertThrows(IllegalStateException::class.java) { deletion.delete("thread", "content://sms/70") }
        assertEquals(listOf("content://sms/70", "content://sms/2"), provider.deletes)
        assertTrue(provider.rows.containsKey("content://sms/40"))
        assertEquals(listOf("content://sms/70"), deletion.pending(0, 64).filter { it["confirmed"] == true }.map { it["messageUri"] })
    }

    @Test fun deletesOnlyTheBoundedJobRegardlessOfThreadSizeOrDates() {
        record(70); record(2); record(40)
        record(40, "other-job", "mms")
        for (id in 100..10000) provider.rows["content://sms/$id"] = null
        deletion.delete("thread", "content://sms/2")
        assertEquals(listOf("content://sms/70", "content://sms/2", "content://sms/40"), provider.deletes)
        assertEquals(9902, provider.rows.size)
        assertTrue(provider.queries < 30)
        assertEquals(3, OutboundSendDeletion(context).pending(0, 64).count { it["confirmed"] == true })
    }

    @Test fun durableFenceRejectsRetriesBeforeTheyCreateRows() {
        record(1); record(2)
        deletion.delete("thread", "content://sms/1")
        assertThrows(IllegalStateException::class.java) {
            OutboundSendCorrelations(context).withAttempt("job" to 1) { fail("Deleted send reached row creation") }
        }
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun aRetryThatWinsTheLockIsIncludedInDeletion() {
        record(1)
        store.withAttempt("job" to 1) { attempt ->
            provider.rows["content://sms/2"] = 1000L
            store.recordAttemptRow(Uri.parse("content://sms/2"), attempt)
        }
        deletion.delete("thread", "content://sms/1")
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun retryContendingWithDeleteCannotCreateARowAfterThePlanWasCaptured() {
        record(1); record(2)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        var retry: java.util.concurrent.Future<*>? = null
        provider.beforeDelete = {
            provider.beforeDelete = null
            retry = pool.submit {
                started.countDown()
                assertThrows(IllegalStateException::class.java) {
                    OutboundSendCorrelations(context).withAttempt("job" to 1) { fail("Retry created a row") }
                }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            deletion.delete("thread", "content://sms/1")
            retry!!.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(provider.rows.isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun crashBetweenProviderDeleteAndConfirmationRecoversIdentityAfterRestart() {
        record(1); record(2)
        // Initialize the receipt schema before installing a disk-failure trigger.
        assertTrue(deletion.pending(0, 64).isEmpty())
        database { it.execSQL("CREATE TRIGGER fail_confirmation BEFORE UPDATE OF confirmed ON deletion_rows BEGIN SELECT RAISE(ABORT, 'disk failure'); END") }
        assertThrows(android.database.sqlite.SQLiteException::class.java) { deletion.delete("thread", "content://sms/2") }
        assertFalse(provider.rows.containsKey("content://sms/1"))
        assertTrue(provider.rows.containsKey("content://sms/2"))
        database { it.execSQL("DROP TRIGGER fail_confirmation") }
        val recovered = OutboundSendDeletion(context).pending(0, 64)
        assertEquals("content://sms/1", recovered.first()["messageUri"])
        assertEquals(true, recovered.first()["confirmed"])
        val receipt = (recovered.first()["id"] as Number).toLong()
        OutboundSendDeletion(context).acknowledge(receipt)
        assertFalse(OutboundSendDeletion(context).pending(0, 64).any { it["id"] == receipt })
        deletion.delete("thread", "content://sms/2")
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun reusedRowIsNeverDeletedAsAnOlderAttempt() {
        record(1); record(2)
        provider.rows["content://sms/1"] = 2000L
        deletion.delete("thread", "content://sms/2")
        assertEquals(mapOf("content://sms/1" to 2000L), provider.rows)
        assertEquals(listOf("content://sms/2"), provider.deletes)
    }

    @Test fun nullDatedUncorrelatedMessageHasADurableReceipt() {
        provider.rows["content://mms/7"] = null
        deletion.delete("thread", "content://mms/7")
        assertTrue(provider.rows.isEmpty())
        assertEquals(true, deletion.pending(0, 64).single()["confirmed"])
    }

    @Test fun unavailableProviderAndExpiredBudgetPerformNoDeletes() {
        record(1)
        provider.unavailable = true
        assertThrows(IllegalStateException::class.java) { deletion.delete("thread", "content://sms/1") }
        provider.unavailable = false
        assertThrows(IllegalArgumentException::class.java) { deletion.delete("thread", "content://sms/1", budgetMs = 0) }
        assertTrue(provider.deletes.isEmpty())
    }

    @Test fun methodHandlerReturnsReceiptsAndAcknowledgesTheSameIdentity() {
        record(1)
        val handler = io.simplezen.simple_sms.device.DestructiveActions(context)
        fun invoke(method: String, arguments: Any?): Any? {
            var reply: Any? = null
            handler.onMethodCall(io.flutter.plugin.common.MethodCall(method, arguments),
                object : io.flutter.plugin.common.MethodChannel.Result {
                    override fun success(result: Any?) { reply = result }
                    override fun error(code: String, message: String?, details: Any?) { fail("$code: $message") }
                    override fun notImplemented() { fail("Missing native deletion API: $method") }
                })
            return reply
        }
        invoke("deleteMessageAttempts", mapOf("scope" to "thread", "messageUri" to "content://sms/1"))
        val receipt = (invoke("pendingMessageDeletions", mapOf("after" to 0, "limit" to 64)) as List<*>).single() as Map<*, *>
        assertEquals("job", receipt["clientCorrelationId"])
        assertEquals(true, receipt["jobDeletionComplete"])
        invoke("acknowledgeMessageDeletion", receipt["id"])
        assertEquals(emptyList<Any>(), invoke("pendingMessageDeletions", mapOf("after" to 0, "limit" to 64)))
    }

    private fun database(action: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "outbound_send_correlations.db").path, null, 0).use(action)
    }

    class Rows : ContentProvider() {
        val rows = linkedMapOf<String, Long?>()
        val deletes = mutableListOf<String>()
        var refuse: String? = null
        var unavailable = false
        var queries = 0
        var beforeDelete: (() -> Unit)? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queries++
            assertNotNull("Never enumerate an entire thread", uri.lastPathSegment?.toLongOrNull())
            if (unavailable) return null
            return MatrixCursor(arrayOf("_id", "date")).apply {
                if (rows.containsKey(uri.toString())) addRow(arrayOf(uri.lastPathSegment!!.toLong(), rows[uri.toString()]))
            }
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            beforeDelete?.invoke()
            deletes.add(uri.toString())
            if (uri.toString() == refuse) return 0
            val value = rows[uri.toString()]
            val matches = if (value == null) selection == "date IS NULL" else selection == "date = ?" && selectionArgs?.single() == value.toString()
            if (!rows.containsKey(uri.toString()) || !matches) return 0
            rows.remove(uri.toString())
            return 1
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No sends in deletion test")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

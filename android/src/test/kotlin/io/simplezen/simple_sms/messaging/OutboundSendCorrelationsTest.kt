package io.simplezen.simple_sms.messaging

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSendCorrelationsTest {
    private lateinit var sms: CorrelationRows

    @Before fun providers() {
        sms = Robolectric.buildContentProvider(CorrelationRows::class.java).create("sms").get()
        Robolectric.buildContentProvider(CorrelationRows::class.java).create("mms")
    }

    @Test fun uncorrelatedReuseClearsThePreviousJobAfterReopening() {
        val context: Context = ApplicationProvider.getApplicationContext()
        OutboundSendCorrelations(context).record(Uri.parse("content://sms/7"), "old-job")
        OutboundSendCorrelations(context).record(Uri.parse("content://sms/outbox/7"), null)
        assertTrue(OutboundSendCorrelations(context).lookup(listOf("content://sms/7")).isEmpty())
    }

    @Test fun reusedLatestAttemptPreservesThePreviousJobsSurvivingAttempt() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/7"), "old-job")
        store.record(Uri.parse("content://sms/8"), "old-job")
        store.record(Uri.parse("content://sms/8"), "new-job")
        val recovered = OutboundSendCorrelations(context).lookup(listOf("content://sms/7", "content://sms/8"))
        assertEquals("old-job", recovered["content://sms/7"]?.get("clientCorrelationId"))
        assertEquals("content://sms/7", recovered["content://sms/7"]?.get("latestMessageUri"))
        assertEquals("new-job", recovered["content://sms/8"]?.get("clientCorrelationId"))
    }

    @Test fun reusedEarlierAttemptKeepsTheLatestAttemptOfTheOriginalJob() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/7"), "old-job")
        store.record(Uri.parse("content://sms/8"), "old-job")
        store.record(Uri.parse("content://sms/7"), null)
        val recovered = store.lookup(listOf("content://sms/7", "content://sms/8"))
        assertFalse(recovered.containsKey("content://sms/7"))
        assertEquals("old-job", recovered["content://sms/8"]?.get("clientCorrelationId"))
    }

    @Test fun deletedLatestAttemptIsPrunedBeforeItsIdCanBeReused() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/7"), "job")
        store.record(Uri.parse("content://sms/8"), "job")
        sms.absent.add(8L)
        assertEquals("content://sms/7", store.lookup(listOf("content://sms/7"))["content://sms/7"]?.get("latestMessageUri"))
        sms.absent.clear()
        val recovered = OutboundSendCorrelations(context).lookup(listOf("content://sms/7", "content://sms/8"))
        assertEquals("job", recovered["content://sms/7"]?.get("clientCorrelationId"))
        assertFalse(recovered.containsKey("content://sms/8"))
    }

    @Test fun deletedAttemptsRecoverInRecordedOrderRatherThanRowIdOrder() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/70"), "job")
        store.record(Uri.parse("content://sms/2"), "job")
        store.record(Uri.parse("content://sms/40"), "job")
        sms.absent.add(40L)
        val recovered = OutboundSendCorrelations(context).lookup(listOf("content://sms/70", "content://sms/2"))
        assertEquals("content://sms/2", recovered["content://sms/70"]?.get("latestMessageUri"))
        assertEquals("content://sms/2", recovered["content://sms/2"]?.get("latestMessageUri"))
    }

    @Test fun unavailableProviderFailsWithoutErasingTheIdentity() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/7"), "job")
        sms.unavailable = true
        assertThrows(IllegalStateException::class.java) { store.lookup(listOf("content://sms/7")) }
        sms.unavailable = false
        assertEquals("job", store.lookup(listOf("content://sms/7"))["content://sms/7"]?.get("clientCorrelationId"))
    }

    @Test fun failedCommitAbortsBeforeReturningToTheRadioHandoff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val actual = context.getSharedPreferences("failed_commit", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            OutboundSendCorrelations(failing) {
                error("Recording must not read the provider")
            }.record(Uri.parse("content://sms/14"), "job")
        }
    }

    @Test fun durableIdentityDistinguishesTablesAndRetries() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/outbox/7"), "job-one")
        store.record(Uri.parse("content://mms/7"), "job-two")
        store.record(Uri.parse("content://sms/8"), "job-one")
        val recovered = OutboundSendCorrelations(context).lookup(listOf(
            "content://sms/sent/7", "content://mms/7", "content://sms/8", "content://sms/9"))
        assertEquals("job-one", recovered["content://sms/sent/7"]?.get("clientCorrelationId"))
        assertEquals("content://sms/8", recovered["content://sms/sent/7"]?.get("latestMessageUri"))
        assertEquals("job-two", recovered["content://mms/7"]?.get("clientCorrelationId"))
        assertFalse(recovered.containsKey("content://sms/9"))
    }

    @Test fun absentAndMalformedIdentitiesAreNeverGuessed() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/12"), null)
        assertTrue(store.lookup(listOf("content://sms/12")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            store.record(Uri.parse("content://sms/13"), " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.record(Uri.parse("content://contacts/7"), "job")
        }
    }

    class CorrelationRows : ContentProvider() {
        val absent = mutableSetOf<Long>()
        var unavailable = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (unavailable) return null
            assertArrayEquals(arrayOf("_id"), projection)
            return MatrixCursor(arrayOf("_id")).apply {
                val id = uri.lastPathSegment!!.toLong()
                if (id !in absent) addRow(arrayOf(id))
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

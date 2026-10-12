package io.simplezen.simple_sms.messaging

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import org.robolectric.Shadows.shadowOf
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.robolectric.annotation.Config

// Purpose: prevent send recovery from confusing retries, tables, or reused rows.
/**
 * @param None The runner supplies each test's application context.
 * @returns A regression suite exercising real registry and method-handler behavior.
 * @throws AssertionError If any identity, persistence, or retention contract breaks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSendCorrelationsTest {
    private lateinit var sms: CorrelationRows

    // Purpose: isolate row existence from carrier behavior in these regressions.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Before fun providers() {
        sms = Robolectric.buildContentProvider(CorrelationRows::class.java).create("sms").get()
        Robolectric.buildContentProvider(CorrelationRows::class.java).create("mms")
    }

    // Purpose: prevent a reused provider URI from inheriting another send identity.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Test fun uncorrelatedReuseClearsThePreviousJobAfterReopening() {
        val context: Context = ApplicationProvider.getApplicationContext()
        OutboundSendCorrelations(context).record(Uri.parse("content://sms/7"), "old-job")
        OutboundSendCorrelations(context).record(Uri.parse("content://sms/outbox/7"), null)
        assertTrue(OutboundSendCorrelations(context).lookup(listOf("content://sms/7")).isEmpty())
    }

    // Purpose: keep surviving attempts attached to their original queue job.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
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

    // Purpose: avoid losing a live latest attempt when an older URI is reassigned.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
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

    // Purpose: prevent deleted attempt metadata from being resurrected by URI reuse.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
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

    // Purpose: prevent numeric row ordering from selecting the wrong surviving attempt.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
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

    // Purpose: prevent a provider outage from being treated as deletion.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Test fun unavailableProviderFailsWithoutErasingTheIdentity() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/7"), "job")
        sms.unavailable = true
        assertThrows(IllegalStateException::class.java) { store.lookup(listOf("content://sms/7")) }
        sms.unavailable = false
        assertEquals("job", store.lookup(listOf("content://sms/7"))["content://sms/7"]?.get("clientCorrelationId"))
    }

    // Purpose: bound retry history without evicting surviving identities.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Test fun liveAttemptRetentionStopsAt64WithoutLosingExistingMappings() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        for (id in 1..64) store.record(Uri.parse("content://sms/$id"), "job")
        assertThrows(IllegalStateException::class.java) {
            store.record(Uri.parse("content://sms/65"), "job")
        }
        val recovered = store.lookup(listOf("content://sms/1", "content://sms/64", "content://sms/65"))
        assertEquals("content://sms/64", recovered["content://sms/1"]?.get("latestMessageUri"))
        assertEquals("job", recovered["content://sms/64"]?.get("clientCorrelationId"))
        assertFalse(recovered.containsKey("content://sms/65"))
    }

    // Purpose: allow another attempt after verified deletion frees retention space.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Test fun deletedAttemptsFreeRetentionSpaceWithoutDroppingSurvivors() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        for (id in 1..64) store.record(Uri.parse("content://sms/$id"), "job")
        sms.absent.add(2L)
        store.record(Uri.parse("content://sms/65"), "job")
        val recovered = store.lookup(listOf("content://sms/1", "content://sms/2", "content://sms/65"))
        assertEquals("content://sms/65", recovered["content://sms/1"]?.get("latestMessageUri"))
        assertEquals("job", recovered["content://sms/65"]?.get("clientCorrelationId"))
        assertFalse(recovered.containsKey("content://sms/2"))
    }

    // Purpose: prevent rejected retries from leaving uncorrelated provider rows.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Test fun exhaustedRetentionFailsBeforeTheSenderCreatesAProviderRow() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        for (id in 1..64) store.record(Uri.parse("content://sms/$id"), "job")
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        var failure: String? = null
        val result = object : MethodChannel.Result {
            // Purpose: isolate the platform boundary needed by this regression.
            /**
             * @param result The unexpected success payload.
             * @returns Unit after recording or rejecting the callback.
             * @throws AssertionError If the regression boundary is used unexpectedly.
             */
            override fun success(result: Any?) { fail("Exhausted retention must reject the send") }
            // Purpose: isolate the platform boundary needed by this regression.
            /**
             * @param code The native rejection being observed.
             * @param message The native rejection being observed.
             * @param details The native rejection being observed.
             * @returns Unit after recording or rejecting the callback.
             * @throws AssertionError If the regression boundary is used unexpectedly.
             */
            override fun error(code: String, message: String?, details: Any?) { failure = message }
            // Purpose: isolate the platform boundary needed by this regression.
            /**
             * @param None No arguments are needed.
             * @returns Unit after recording or rejecting the callback.
             * @throws AssertionError If the regression boundary is used unexpectedly.
             */
            override fun notImplemented() { fail("sendMessage must be implemented") }
        }
        val sender = OutboundSender(context)
        try {
            sender.onMethodCall(MethodCall("sendMessage", mapOf(
                "recipients" to listOf("5551234"), "body" to "hi", "clientCorrelationId" to "job")), result)
            assertTrue(failure.orEmpty().contains("retention limit"))
            assertEquals(0, sms.inserts)
            assertTrue(sender.pendingSends.isEmpty())
        } finally {
            sender.release()
        }
    }

    // Purpose: stop damaged history from exposing a previous owner after URI reuse.
    /**
     * @param None This regression supplies a damaged persisted history.
     * @returns Unit after checking that recovery fails closed.
     * @throws AssertionError If recovery returns the stale identity.
     */
    @Test fun corruptHistoryCannotExposePriorOwnerAfterUncorrelatedReuse() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        val uri = Uri.parse("content://sms/7")
        store.record(uri, "old-job")
        context.getSharedPreferences("outbound_send_correlations", Context.MODE_PRIVATE)
            .edit().putString("attempts:old-job", "broken").commit()
        assertThrows(org.json.JSONException::class.java) { store.record(uri, null) }
        assertThrows(org.json.JSONException::class.java) { store.lookup(listOf(uri.toString())) }
    }

    // Purpose: prevent the radio handoff from outrunning durable identity storage.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
    @Test fun failedCommitAbortsBeforeReturningToTheRadioHandoff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val actual = context.getSharedPreferences("failed_commit", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by actual {
            // Purpose: isolate the platform boundary needed by this regression.
            /**
             * @param None No arguments are needed.
             * @returns An editor whose commit can be refused.
             * @throws AssertionError If the regression boundary is used unexpectedly.
             */
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    // Purpose: isolate the platform boundary needed by this regression.
                    /**
                     * @param key Metadata staged for the controlled persistence failure.
                     * @param value Metadata staged for the controlled persistence failure.
                     * @returns The editor after staging metadata.
                     * @throws AssertionError If the regression boundary is used unexpectedly.
                     */
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    // Purpose: isolate the platform boundary needed by this regression.
                    /**
                     * @param None No arguments are needed.
                     * @returns False to reproduce durable write failure.
                     * @throws AssertionError If the regression boundary is used unexpectedly.
                     */
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

    // Purpose: preserve exact table identity and latest attempt across registry reopening.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
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

    // Purpose: prevent malformed or missing identity from being guessed.
    /**
     * @param None This regression takes no arguments.
     * @returns Unit after checking the recovery contract.
     * @throws AssertionError If the regression contract is broken.
     */
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

    // Purpose: verify exact provider reads while controlling row availability.
    /**
     * @param None Robolectric constructs this fixture.
     * @returns A provider with controllable existence and availability.
     * @throws AssertionError If recovery requests message data instead of row identity.
     */
    class CorrelationRows : ContentProvider() {
        val absent = mutableSetOf<Long>()
        var unavailable = false
        var inserts = 0
        // Purpose: isolate the platform boundary needed by this regression.
        /**
         * @param None No arguments are needed.
         * @returns True when the fixture is ready.
         * @throws AssertionError If the regression boundary is used unexpectedly.
         */
        override fun onCreate() = true
        // Purpose: isolate the platform boundary needed by this regression.
        /**
         * @param uri The platform query contract.
         * @param projection The platform query contract.
         * @param selection The platform query contract.
         * @param selectionArgs The platform query contract.
         * @param sortOrder The platform query contract.
         * @returns An identity-only cursor, or null for provider unavailability.
         * @throws AssertionError If the regression boundary is used unexpectedly.
         */
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (unavailable) return null
            assertArrayEquals(arrayOf("_id"), projection)
            return MatrixCursor(arrayOf("_id")).apply {
                val id = uri.lastPathSegment!!.toLong()
                if (id !in absent) addRow(arrayOf(id))
            }
        }
        // Purpose: isolate the platform boundary needed by this regression.
        /**
         * @param uri The provider URI.
         * @returns Null because MIME is irrelevant to recovery.
         * @throws AssertionError If the regression boundary is used unexpectedly.
         */
        override fun getType(uri: Uri): String? = null
        // Purpose: isolate the platform boundary needed by this regression.
        /**
         * @param uri The platform insertion contract.
         * @param values The platform insertion contract.
         * @returns The simulated newly created row.
         * @throws AssertionError If the regression boundary is used unexpectedly.
         */
        override fun insert(uri: Uri, values: ContentValues?): Uri? { inserts++; return Uri.parse("content://sms/65") }
        // Purpose: isolate the platform boundary needed by this regression.
        /**
         * @param uri The platform update contract.
         * @param values The platform update contract.
         * @param selection The platform update contract.
         * @param selectionArgs The platform update contract.
         * @returns Zero because the fixture does not mutate rows.
         * @throws AssertionError If the regression boundary is used unexpectedly.
         */
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        // Purpose: isolate the platform boundary needed by this regression.
        /**
         * @param uri The platform deletion contract.
         * @param selection The platform deletion contract.
         * @param selectionArgs The platform deletion contract.
         * @returns Zero because deletion is controlled by existence fixtures.
         * @throws AssertionError If the regression boundary is used unexpectedly.
         */
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

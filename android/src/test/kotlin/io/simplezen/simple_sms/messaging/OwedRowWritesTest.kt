package io.simplezen.simple_sms.messaging

import android.content.Context
import android.net.Uri
import android.app.Activity
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.messaging.DeliveryReportSelectionTest.SqliteSmsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Pins [OwedRowWrites] (UNFY-335): what it keeps of the status writes that
 * did not land, and when it gives one up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwedRowWritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "owed_row_writes_test")
    private var now = System.currentTimeMillis()
    private lateinit var provider: SqliteSmsProvider

    /** A store over the same files, as a new app process would build it. */
    private fun owed() = OwedRowWrites(directory) { now }

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(SqliteSmsProvider::class.java).create("sms").get()
        provider.db.execSQL("INSERT INTO sms (_id, type, date) VALUES (1, ${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, 1000)")
        provider.ignoreWrites = true
    }

    @Test
    fun aLaterWriteOfTheSameColumns_replacesTheEarlierOne() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_FAILED))
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.ignoreWrites = false

        assertEquals(1, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
    }

    @Test
    fun aRowOwesOneResultWrite_whateverColumnsEachSets() {
        // An MMS failure sets the box alone, a success more columns.
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_FAILED))
        owed().owe(
            statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT).let {
                it.copy(values = it.values + (Telephony.Sms.ERROR_CODE to 0L))
            },
        )

        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun anOwedFailure_isNeverReplacedByALaterResult() {
        // A part sent before parts were tracked failed, then another part
        // was sent: the failure is final.
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, SmsManager.RESULT_ERROR_NO_SERVICE))
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, Activity.RESULT_OK))
        provider.ignoreWrites = false

        assertEquals(1, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, provider.errorCode(1))
    }

    @Test
    fun aRowsSendResult_landsBeforeItsDeliveryStatus() {
        // Failed meanwhile for lack of a result; the delivery status needs
        // the row sent again first.
        provider.db.execSQL(
            "UPDATE sms SET type = ${Telephony.Sms.MESSAGE_TYPE_FAILED}, " +
                "error_code = $NO_SENT_RESULT_ERROR_CODE WHERE _id = 1",
        )
        owed().owe(
            RowWrite(
                smsUri(1),
                RowWriteKind.DELIVERY_STATUS,
                mapOf(Telephony.Sms.STATUS to Telephony.Sms.STATUS_COMPLETE.toLong()),
                DELIVERY_REPORT_SELECTION,
                DELIVERY_REPORT_ARGS.toList(),
            ),
        )
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, Activity.RESULT_OK))
        provider.ignoreWrites = false

        assertEquals(2, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
    }

    @Test
    fun aResultWriteAndADeliveryWrite_areKeptSideBySide() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        owed().owe(deliveryWrite(1, Telephony.Sms.STATUS_COMPLETE))
        provider.ignoreWrites = false

        assertEquals(2, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
    }

    @Test
    fun anUntiedWrite_isTiedToItsRowWhenRetried_soItNeverLandsOnARowThatReusedTheId() {
        // Owed while its row couldn't be read (UNFY-335).
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        assertEquals("still ignored", 0, owed().retry(context))

        // Deleted, and its id reused by the app that holds the role now.
        provider.db.execSQL("DELETE FROM sms WHERE _id = 1")
        provider.db.execSQL("INSERT INTO sms (_id, type, date) VALUES (1, ${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, 2000)")
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun anUntiedWrite_neverLandsOnANewerRowThatTookItsId() {
        // Owed while its row couldn't be read, then deleted and its id reused
        // before any retry could tie it (UNFY-335).
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.db.execSQL("DELETE FROM sms WHERE _id = 1")
        provider.db.execSQL(
            "INSERT INTO sms (_id, type, date) VALUES (1, ${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, ${now + 60_000})",
        )
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun anUntiedWrite_forARowThatIsGone_isDropped() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.db.execSQL("DELETE FROM sms WHERE _id = 1")

        assertEquals(0, owed().retry(context))

        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun aRowALiveSendAwaits_isNotRetried() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context, skip = setOf(Uri.parse(smsUri(1)))))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
    }

    @Test
    fun aDeliveryStatus_waitsWhileItsRowsSendResultIsOwed() {
        provider.db.execSQL(
            "UPDATE sms SET type = ${Telephony.Sms.MESSAGE_TYPE_FAILED}, " +
                "error_code = $NO_SENT_RESULT_ERROR_CODE WHERE _id = 1",
        )
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, Activity.RESULT_OK))
        owed().owe(
            RowWrite(
                smsUri(1),
                RowWriteKind.DELIVERY_STATUS,
                mapOf(Telephony.Sms.STATUS to Telephony.Sms.STATUS_COMPLETE.toLong()),
                DELIVERY_REPORT_SELECTION,
                DELIVERY_REPORT_ARGS.toList(),
            ),
        )

        assertEquals("both still ignored", 0, owed().retry(context))
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test
    fun aWriteOwedForTheWholeWindow_isGivenUp() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        now += DELIVERY_REPORT_WAIT_MS + 1
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun aWriteTiedAtARetry_keepsTheWindowOfWhenItWasFirstOwed() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        now += DELIVERY_REPORT_WAIT_MS - 1_000
        assertEquals("tied, still ignored", 0, owed().retry(context))

        now += 2_000
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun aWriteThatReplacesAnOwedOne_keepsTheWindowOfTheFirst() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_FAILED))
        now += DELIVERY_REPORT_WAIT_MS - 1_000
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))

        now += 2_000
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun aWriteForgottenWhileARetryTiesIt_isNeverApplied() {
        // This app's new send takes the row while the retry reads it.
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.ignoreWrites = false
        provider.beforeNextQuery = { owed().forget(Uri.parse(smsUri(1))) }

        assertEquals(0, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue("not stored again", owed().rows().isEmpty())
    }

    @Test
    fun aTiedWriteReplacedDuringARetry_waitsForTheNextRetry() {
        // Row 2's result is retried first, and row 1's tied status replaced
        // meanwhile: the retry's copy of it is stale.
        provider.db.execSQL("INSERT INTO sms (_id, type, date) VALUES (2, ${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, 1000)")
        owed().owe(deliveryWrite(1, Telephony.Sms.STATUS_COMPLETE).tiedTo(1000))
        owed().owe(statusWrite(2, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.ignoreWrites = false
        provider.beforeNextQuery = { owed().owe(deliveryWrite(1, Telephony.Sms.STATUS_PENDING).tiedTo(1000)) }

        assertEquals(1, owed().retry(context))
        assertEquals("the replaced write is not applied", -1, provider.status(1))

        assertEquals(1, owed().retry(context))
        assertEquals(Telephony.Sms.STATUS_PENDING, provider.status(1))
    }

    @Test
    fun aWriteIsNeverOwedToARowThatTookItsIdDuringTheWrite() {
        // Deleted, and its id reused, between the ignored write and owing it.
        provider.beforeNextQuery = {
            provider.db.execSQL("DELETE FROM sms WHERE _id = 1")
            provider.db.execSQL(
                "INSERT INTO sms (_id, type, date) VALUES " +
                    "(1, ${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, ${System.currentTimeMillis() + 60_000})",
            )
        }

        assertEquals(RowWriteOutcome.IGNORED, recordRowWrite(context, statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT)))

        assertTrue(OwedRowWrites.forApp(context).rows().isEmpty())
    }

    @Test
    fun aWriteReplacedWhileARetryTiesIt_waitsForTheNextRetry() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.ignoreWrites = false
        provider.beforeNextQuery = { owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_FAILED)) }

        assertEquals(0, owed().retry(context))
        assertEquals("the replaced write is not applied", Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))

        assertEquals(1, owed().retry(context))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
    }

    @Test
    fun aWriteThatLands_forgetsTheStaleOneOwedToItsRow() {
        // Owed while the app had lost the role; a later report lands.
        val store = OwedRowWrites.forApp(context)
        store.owe(deliveryWrite(1, Telephony.Sms.STATUS_PENDING))
        provider.ignoreWrites = false

        assertEquals(RowWriteOutcome.WRITTEN, recordRowWrite(context, deliveryWrite(1, Telephony.Sms.STATUS_COMPLETE)))

        assertEquals(0, store.retry(context))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
        assertTrue(store.rows().isEmpty())
    }

    @Test
    fun aWriteThatLands_neverForgetsAFinalOneOwedToItsRow() {
        val store = OwedRowWrites.forApp(context)
        store.owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, SmsManager.RESULT_ERROR_NO_SERVICE))
        provider.ignoreWrites = false

        recordRowWrite(context, statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))

        assertEquals(setOf(Uri.parse(smsUri(1))), store.rows())
    }

    @Test
    fun aRetriedWriteThatLands_isDroppedEvenIfItsTieCouldNotBeStored() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        // A directory, not empty, where the store writes its next version:
        // storing the tie fails.
        File(directory.listFiles()!!.single().path + ".new").apply { mkdir() }.resolve("blocker").writeText("")
        provider.ignoreWrites = false

        assertEquals(1, owed().retry(context))

        assertTrue(
            "the tie was not stored",
            ShadowLog.getLogsForTag("OwedRowWrites").any { it.msg.startsWith("Could not keep the status write owed") },
        )
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertTrue("never applied again", owed().rows().isEmpty())
    }

    @Test
    fun anUndecodableWrite_isDropped() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        directory.listFiles()!!.single().writeText("{ not json")

        assertTrue(owed().rows().isEmpty())
        assertEquals(0, owed().retry(context))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aWriteOwedBeforeTextColumns_stillDecodes_andTextColumnsArePersisted() {
        // A write owed by an app version without text columns (UNFY-110).
        val earlier = Json.decodeFromString(
            RowWrite.serializer(),
            """{"uri":"content://sms/1","kind":"SEND_RESULT","values":{"type":2},""" +
                """"selection":"type = ?","selectionArgs":["4"],"owedAtMillis":5}""",
        )
        assertEquals(emptyMap<String, String>(), earlier.textValues)
        assertEquals(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT).copy(owedAtMillis = 5), earlier)

        val withText = statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT).copy(textValues = mapOf("m_id" to "mid-1"))
        assertEquals(
            withText,
            Json.decodeFromString(RowWrite.serializer(), Json.encodeToString(RowWrite.serializer(), withText)),
        )
        val values = withText.contentValues()
        assertEquals("mid-1", values.getAsString("m_id"))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT.toLong(), values.getAsLong(Telephony.Sms.TYPE))
    }

    private fun statusWrite(id: Long, type: Int) = RowWrite(
        smsUri(id),
        RowWriteKind.SEND_RESULT,
        mapOf(Telephony.Sms.TYPE to type.toLong()),
        "${Telephony.Sms.TYPE} = ?",
        listOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString()),
    )

    private fun deliveryWrite(id: Long, status: Int) = RowWrite(
        smsUri(id),
        RowWriteKind.DELIVERY_STATUS,
        mapOf(Telephony.Sms.STATUS to status.toLong()),
        "${Telephony.Sms.STATUS} != ?",
        listOf(Telephony.Sms.STATUS_FAILED.toString()),
    )

    private fun smsUri(id: Long): String = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()).toString()
}

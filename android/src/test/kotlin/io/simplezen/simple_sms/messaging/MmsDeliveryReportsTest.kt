package io.simplezen.simple_sms.messaging

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.mms.codec.DeliveryInd
import io.simplezen.simple_sms.mms.codec.PduHeaders
import io.simplezen.simple_sms.mms.codec.PduParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins MMS delivery reports (UNFY-110) end to end, from the WAP push
 * through [InboundMmsHandler] to the sent row's `st`, against real SQL over
 * `pdu` and `addr` tables: the SendConf's message id links a report to its
 * row, or to one whose SENT result is still owed; a group MMS is delivered
 * only once every recipient got it, and failed only once none will; a
 * verdict is terminal; a report without a verdict, or for no sent MMS,
 * changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsDeliveryReportsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var mms: SqlitePduProvider

    @Before
    fun setUp() {
        mms = Robolectric.buildContentProvider(SqlitePduProvider::class.java).create("mms").get()
        // Numbers compare in the network's country ([compareNumbers]).
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setNetworkCountryIso("us")
    }

    // ── SendConf ─────────────────────────────────────────────────────────

    @Test
    fun sendConf_yieldsItsMessageId() {
        assertEquals("mid-1", mmsMessageIdFromSendConf(sendConf(messageId = "mid-1")))
    }

    @Test
    fun sendConf_withoutAnId_orNotASendConf_orAbsent_yieldsNone() {
        assertNull(mmsMessageIdFromSendConf(sendConf(messageId = null)))
        assertNull(mmsMessageIdFromSendConf(deliveryInd("mid-1", "+15550000001", PduHeaders.STATUS_RETRIEVED)))
        assertNull(mmsMessageIdFromSendConf(byteArrayOf(0x01, 0x02)))
        assertNull(mmsMessageIdFromSendConf(null))
    }

    // ── Addresses ────────────────────────────────────────────────────────

    @Test
    fun addresses_matchAcrossTheFormatsAnMmscAndTheRowUse() {
        assertTrue(isSameMmsAddress(context, "+15550000001", "5550000001"))
        assertTrue(isSameMmsAddress(context, "Someone@Example.com", "someone@example.com"))
        assertFalse(isSameMmsAddress(context, "+15550000001", "+15550000002"))
        // A local short form is not a full number that ends with it.
        assertFalse(isSameMmsAddress(context, "0000001", "+15550000001"))
    }

    @Test
    fun reportingRecipient_isTheOnlyOne_orTheOneMatch() {
        assertEquals("5550000001", reportingRecipient(context, "unparseable", listOf("5550000001")))
        assertEquals("5550000001", reportingRecipient(context, null, listOf("5550000001")))
        val group = listOf("+15550000001", "+15550000002")
        assertEquals("+15550000002", reportingRecipient(context, "+15550000002", group))
        assertNull(reportingRecipient(context, "+15550000003", group))
        assertNull(reportingRecipient(context, null, group))
    }

    @Test
    fun deliveryInd_toAddress_reachesTheMatcherWithoutItsTypeSuffix() {
        // The parser drops `/TYPE=PLMN`, so the matcher never sees it.
        val pdu = PduParser(deliveryInd("mid-1", "+15550000002/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED), true)
            .parse() as DeliveryInd
        assertEquals("+15550000002", pdu.to?.single()?.string)
    }

    // ── Reports, end to end ──────────────────────────────────────────────

    @Test
    fun oneRecipient_deliveredReport_marksTheRowDelivered() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun aWrittenVerdict_isAnnouncedOnTheRow() {
        // The MMS provider announces updates only on content://mms-sms/, so
        // watchers of content://mms would never hear of it.
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertTrue(Uri.parse("content://mms/1") in shadowOf(context.contentResolver).notifiedUris.map { it.uri })
    }

    @Test
    fun oneRecipient_forwardedReport_isADelivery() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_FORWARDED))

        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun aVerdict_isTerminal() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))
        mms.sent(id = 2, messageId = "mid-2", recipients = listOf("5550000001"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_REJECTED))
        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-2", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-2", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_EXPIRED))

        assertEquals(PduHeaders.STATUS_REJECTED, mms.status(1))
        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(2))
    }

    @Test
    fun group_isDeliveredOnlyOnceEveryRecipientIs() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("+15550000001", "+15550000002"), cc = "+15550000003")

        push(deliveryInd("mid-1", "+15550000002/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-1", "+15550000002/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-1", "+15550000003/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        assertNull(mms.status(1))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun group_failsOnlyOnceNoRecipientWillGetIt() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("+15550000001", "+15550000002"))

        push(deliveryInd("mid-1", "+15550000002/TYPE=PLMN", PduHeaders.STATUS_EXPIRED))
        assertNull(mms.status(1))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_REJECTED))
        assertEquals(PduHeaders.STATUS_EXPIRED, mms.status(1))
    }

    @Test
    fun group_someDeliveredSomeFailed_staysWithoutAVerdict() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("+15550000001", "+15550000002"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-1", "+15550000002/TYPE=PLMN", PduHeaders.STATUS_EXPIRED))

        assertNull(mms.status(1))
    }

    @Test
    fun group_recipientStoredTwiceInTwoFormats_isOneRecipient() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("+15550000001", "5550000001", "+15550000002"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-1", "+15550000002/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun aReportForARowWhoseSendResultIsOwed_landsAfterIt() {
        // The app lost the default-SMS role as the SENT result came (UNFY-335):
        // the result, with its m_id, is owed, so the row carries no m_id yet.
        mms.row(id = 1, messageId = null, type = PduHeaders.MESSAGE_TYPE_SEND_REQ, box = Telephony.Mms.MESSAGE_BOX_OUTBOX)
        mms.recipients(1, listOf("5550000001"))
        mms.ignoreWrites = true
        recordSendResult(
            context, 1, Uri.parse("content://mms/1"), SENTMMS_ACTION, "test", android.app.Activity.RESULT_OK, "mid-1",
        )

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        assertNull(mms.status(1))

        mms.ignoreWrites = false
        assertEquals(2, OwedRowWrites.forApp(context).retry(context))

        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, mms.box(1))
        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun group_reportForNoRecipient_isIgnored() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("+15550000001", "+15550000002"))

        push(deliveryInd("mid-1", "+15550000009/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertNull(mms.status(1))
    }

    @Test
    fun reportWithoutAVerdict_changesNothing() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_DEFERRED))
        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_INDETERMINATE))

        assertNull(mms.status(1))
        // A verdict after them still counts.
        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun reportOnlyReachesTheSentMmsItNames() {
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))
        mms.sent(id = 2, messageId = "mid-2", recipients = listOf("5550000001"))
        // An inbound MMS carrying the same id (another MMSC's), and a sent
        // one whose box is not sent.
        mms.row(id = 3, messageId = "mid-1", type = PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF, box = Telephony.Mms.MESSAGE_BOX_INBOX)

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))
        push(deliveryInd("mid-9", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
        assertNull(mms.status(2))
        assertNull(mms.status(3))
    }

    @Test
    fun aProvidersOwnStatusDefault_isNoVerdict() {
        // An OEM provider may store 0, not NULL, in a new row's `st`.
        mms.sent(id = 1, messageId = "mid-1", recipients = listOf("5550000001"))
        mms.setStatus(1, 0)

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertEquals(PduHeaders.STATUS_RETRIEVED, mms.status(1))
    }

    @Test
    fun rowNotInTheSentBox_isNotWritten() {
        mms.row(id = 1, messageId = "mid-1", type = PduHeaders.MESSAGE_TYPE_SEND_REQ, box = Telephony.Mms.MESSAGE_BOX_OUTBOX)
        mms.recipients(1, listOf("5550000001"))

        push(deliveryInd("mid-1", "+15550000001/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED))

        assertNull(mms.status(1))
    }

    @Test
    fun persist_refusesANonVerdict() {
        assertThrows(IllegalArgumentException::class.java) {
            persistMmsDeliveryStatus(context, Uri.parse("content://mms/1"), "mid-1", PduHeaders.STATUS_DEFERRED)
        }
    }

    private fun push(pdu: ByteArray) {
        InboundMmsHandler().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION)
                .setType("application/vnd.wap.mms-message")
                .putExtra("data", pdu),
        )
    }

    /** A minimal SQLite-backed `mms` provider: `pdu` rows, and each row's `addr` rows. */
    class SqlitePduProvider : ContentProvider() {
        private val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL(
                "CREATE TABLE pdu (_id INTEGER PRIMARY KEY, m_id TEXT, m_type INTEGER, msg_box INTEGER, " +
                    "st INTEGER, date INTEGER, read INTEGER, seen INTEGER, date_sent INTEGER)",
            )
            execSQL("CREATE TABLE addr (_id INTEGER PRIMARY KEY, msg_id INTEGER, address TEXT, type INTEGER)")
        }

        /** Whether writes are ignored, as for an app without the default-SMS role. */
        var ignoreWrites = false

        fun row(id: Long, messageId: String?, type: Int, box: Int) {
            db.insertOrThrow("pdu", null, ContentValues().apply {
                put("_id", id)
                put(Telephony.Mms.MESSAGE_ID, messageId)
                put(Telephony.Mms.MESSAGE_TYPE, type)
                put(Telephony.Mms.MESSAGE_BOX, box)
                put(Telephony.Mms.DATE, 1_700_000_000L)
            })
        }

        fun recipients(id: Long, to: List<String>, cc: String? = null) {
            fun addr(address: String, type: Int) = db.insertOrThrow("addr", null, ContentValues().apply {
                put("msg_id", id)
                put(Telephony.Mms.Addr.ADDRESS, address)
                put(Telephony.Mms.Addr.TYPE, type)
            })
            addr("insert-address-token", PduHeaders.FROM)
            to.forEach { addr(it, PduHeaders.TO) }
            cc?.let { addr(it, PduHeaders.CC) }
        }

        fun sent(id: Long, messageId: String, recipients: List<String>, cc: String? = null) {
            row(id, messageId, PduHeaders.MESSAGE_TYPE_SEND_REQ, Telephony.Mms.MESSAGE_BOX_SENT)
            recipients(id, recipients, cc)
        }

        fun status(id: Long): Int? = db.rawQuery("SELECT st FROM pdu WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        fun setStatus(id: Long, st: Int) {
            db.execSQL("UPDATE pdu SET st = ? WHERE _id = ?", arrayOf<Any>(st, id))
        }

        fun box(id: Long): Int? = db.rawQuery("SELECT msg_box FROM pdu WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val segments = uri.pathSegments
            if (segments.size == 2 && segments[1] == "addr") {
                return db.query("addr", projection, "msg_id = ?", arrayOf(segments[0]), null, null, sortOrder)
            }
            return db.query("pdu", projection, rowWhere(uri, selection), selectionArgs, null, null, sortOrder)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            if (ignoreWrites) 0 else db.update("pdu", values, rowWhere(uri, selection), selectionArgs)

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw AssertionError("A delivery report adds no row")

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw AssertionError("A delivery report deletes no row")
    }

    companion object {
        private fun text(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII) + 0

        /** An M-Send.conf PDU (OMA MMS ENC 6.1.2), optionally naming [messageId]. */
        fun sendConf(messageId: String?): ByteArray {
            var pdu = byteArrayOf(0x8C.toByte(), PduHeaders.MESSAGE_TYPE_SEND_CONF.toByte()) +
                byteArrayOf(0x98.toByte()) + text("T-1") +
                byteArrayOf(0x8D.toByte(), 0x92.toByte()) +
                byteArrayOf(0x92.toByte(), PduHeaders.RESPONSE_STATUS_OK.toByte())
            if (messageId != null) pdu += byteArrayOf(0x8B.toByte()) + text(messageId)
            return pdu
        }

        /** An M-Delivery.ind PDU (OMA MMS ENC 6.6) for [messageId], from recipient [to]. */
        fun deliveryInd(messageId: String, to: String, status: Int): ByteArray =
            byteArrayOf(0x8C.toByte(), PduHeaders.MESSAGE_TYPE_DELIVERY_IND.toByte()) +
                byteArrayOf(0x8D.toByte(), 0x92.toByte()) +
                byteArrayOf(0x8B.toByte()) + text(messageId) +
                byteArrayOf(0x97.toByte()) + text(to) +
                // Date: a 4-octet long-integer (1_700_000_000).
                byteArrayOf(0x85.toByte(), 0x04, 0x65, 0x53, 0xF1.toByte(), 0x00) +
                byteArrayOf(0x95.toByte(), status.toByte())
    }
}

package io.simplezen.simple_sms.messaging

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [DELIVERY_REPORT_SELECTION] as real SQL against an `sms` table, the way
 * the SMS provider applies a selection to a row URI (`_id = N AND (selection)`),
 * so the guard's text — not just its arguments — is what is tested (UNFY-293).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryReportSelectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: SqliteSmsProvider

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(SqliteSmsProvider::class.java).create("sms").get()
    }

    @Test
    fun outboundRowWithoutAFailure_takesTheStatus() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT, status = Telephony.Sms.STATUS_PENDING)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, status = null)

        assertEquals(RowWriteOutcome.WRITTEN, report(1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(RowWriteOutcome.WRITTEN, report(2, Telephony.Sms.STATUS_FAILED))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
        assertEquals(Telephony.Sms.STATUS_FAILED, provider.status(2))
    }

    @Test
    fun aRecordedFailure_isNeverOverwritten() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT, status = Telephony.Sms.STATUS_FAILED)

        assertEquals(RowWriteOutcome.NOT_APPLICABLE, report(1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(Telephony.Sms.STATUS_FAILED, provider.status(1))
    }

    @Test
    fun inboundOrSendFailedRows_areNeverStamped() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = Telephony.Sms.STATUS_NONE)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_FAILED, status = Telephony.Sms.STATUS_PENDING)

        assertEquals(RowWriteOutcome.NOT_APPLICABLE, report(1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(RowWriteOutcome.NOT_APPLICABLE, report(2, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(Telephony.Sms.STATUS_NONE, provider.status(1))
        assertEquals(Telephony.Sms.STATUS_PENDING, provider.status(2))
    }

    private fun insert(id: Long, type: Int, status: Int?) {
        provider.db.insertOrThrow("sms", null, ContentValues().apply {
            put("_id", id)
            put(Telephony.Sms.TYPE, type)
            if (status == null) putNull(Telephony.Sms.STATUS) else put(Telephony.Sms.STATUS, status)
        })
    }

    private fun report(id: Long, status: Int): RowWriteOutcome =
        persistSmsDeliveryStatus(context, ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), status)

    /**
     * A minimal SQLite-backed `sms` provider applying selections like the
     * platform's. [ignoreWrites] models an app without write access (the
     * default-SMS role and its WRITE_SMS app-op), whose updates the provider
     * ignores rather than refuses.
     */
    class SqliteSmsProvider : ContentProvider() {
        var ignoreWrites = false

        /** Thrown by a write to one row (not a table-wide one), when set. */
        var rowWriteFailure: RuntimeException? = null

        /** Run once, by the next query, before it reads. */
        var beforeNextQuery: (() -> Unit)? = null

        val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY, type INTEGER, status INTEGER DEFAULT -1, " +
                    "creator TEXT, date INTEGER, error_code INTEGER DEFAULT 0)",
            )
        }

        fun status(id: Long): Int? = db.rawQuery("SELECT status FROM sms WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        fun type(id: Long): Int? = db.rawQuery("SELECT type FROM sms WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        fun errorCode(id: Long): Int? = db.rawQuery("SELECT error_code FROM sms WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (ignoreWrites) return 0
            if (ContentUris.parseId(uri) >= 0) rowWriteFailure?.let { throw it }
            return db.update("sms", values, rowWhere(uri, selection), selectionArgs)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            beforeNextQuery?.also { beforeNextQuery = null }?.invoke()
            return db.query("sms", projection, rowWhere(uri, selection), selectionArgs, null, null, sortOrder)
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}

/**
 * [selection] on the row a row URI names, joined with a bare `AND` as AOSP
 * MmsProvider joins them (SmsProvider parenthesizes each side), so a guard
 * passes only if it is safe either way.
 */
internal fun rowWhere(uri: Uri, selection: String?): String? {
    val id = ContentUris.parseId(uri)
    return when {
        id < 0 -> selection
        selection == null -> "_id = $id"
        else -> "$selection AND _id = $id"
    }
}

package io.simplezen.simple_sms.messaging

import android.provider.Telephony.Mms
import com.google.android.mms.pdu_alt.PduHeaders
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 · L5 — pins the orphan-`NotificationInd` cleanup predicate builder
 * ([buildOrphanNotificationIndSelection]) extracted from
 * `InboundMmsPersister.persistRetrievedMms` (UNFY-148).
 *
 * Getting the WHERE clause or arg order wrong would delete the wrong provider
 * rows (or silently fail to clean the orphan), so the structure is pinned.
 * Assertions reference the `Mms` column constants (not hard-coded names) to pin
 * the builder's *structure* without coupling to the framework's literal values.
 * Robolectric because the builder reads `android.provider.Telephony.Mms`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrphanCleanupSelectionTest {

    private val mType = PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND.toString()

    @Test
    fun bothIds_orBothPredicates_messageTypeArgFirst() {
        val (selection, args) = buildOrphanNotificationIndSelection("TXN-1", "MID-1")
        assertEquals(
            "${Mms.MESSAGE_TYPE}=? AND (${Mms.TRANSACTION_ID}=? OR ${Mms.MESSAGE_ID}=?)",
            selection,
        )
        // MESSAGE_TYPE arg first, then predicate-order tr_id, m_id.
        assertEquals(listOf(mType, "TXN-1", "MID-1"), args.toList())
    }

    @Test
    fun transactionIdOnly_singlePredicate() {
        val (selection, args) = buildOrphanNotificationIndSelection("TXN-1", null)
        assertEquals("${Mms.MESSAGE_TYPE}=? AND (${Mms.TRANSACTION_ID}=?)", selection)
        assertEquals(listOf(mType, "TXN-1"), args.toList())
    }

    @Test
    fun messageIdOnly_singlePredicate() {
        val (selection, args) = buildOrphanNotificationIndSelection(null, "MID-1")
        assertEquals("${Mms.MESSAGE_TYPE}=? AND (${Mms.MESSAGE_ID}=?)", selection)
        assertEquals(listOf(mType, "MID-1"), args.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun bothNull_failsFast_ratherThanEmittingInvalidSql() {
        // internal + reusable: enforce the precondition loudly rather than
        // emit `m_type=? AND ()` (invalid SQL). The call site also guards this.
        buildOrphanNotificationIndSelection(null, null)
    }

    @Test
    fun alwaysConstrainedToNotificationInd_soRetrieveConfIsNeverDeleted() {
        // The leading m_type arg pins m_type=130 (NotificationInd); the freshly
        // persisted RetrieveConf is m_type=132 and must never match.
        assertEquals("130", PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND.toString())
        val (_, args) = buildOrphanNotificationIndSelection("TXN-1", null)
        assertEquals("130", args.first())
    }
}

package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [NotifyRespInd] port: the compose constructor seeds Message-Type /
 * MMS-Version / Transaction-Id / Status, the Report-Allowed octet round-trips,
 * and a null transaction-id throws (the inherited PduHeaders text-string guard).
 */
class NotifyRespIndTest {

    @Test
    fun composeConstructor_seedsAllFields() {
        val ind = NotifyRespInd(
            PduHeaders.MMS_VERSION_1_2,
            "T-NRI-1".toByteArray(),
            PduHeaders.STATUS_RETRIEVED,
        )
        assertEquals(PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND, ind.messageType)
        assertEquals(PduHeaders.MMS_VERSION_1_2, ind.mmsVersion)
        assertEquals("T-NRI-1", String(ind.transactionId!!))
        assertEquals(PduHeaders.STATUS_RETRIEVED, ind.status)
    }

    @Test
    fun reportAllowed_roundTrips() {
        val ind = NotifyRespInd(PduHeaders.MMS_VERSION_1_2, "T".toByteArray(), PduHeaders.STATUS_RETRIEVED)
        ind.setReportAllowed(PduHeaders.VALUE_YES)
        assertEquals(PduHeaders.VALUE_YES, ind.reportAllowed)
    }

    @Test(expected = NullPointerException::class)
    fun composeConstructor_nullTransactionId_throws() {
        NotifyRespInd(PduHeaders.MMS_VERSION_1_2, null, PduHeaders.STATUS_RETRIEVED)
    }
}

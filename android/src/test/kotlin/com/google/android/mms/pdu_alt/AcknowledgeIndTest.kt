package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [AcknowledgeInd] port: the compose constructor seeds Message-Type /
 * MMS-Version / Transaction-Id, the Report-Allowed octet round-trips, and a
 * null transaction-id throws (the inherited PduHeaders text-string null guard).
 */
class AcknowledgeIndTest {

    @Test
    fun composeConstructor_seedsTypeVersionAndTransactionId() {
        val ind = AcknowledgeInd(PduHeaders.MMS_VERSION_1_2, "T-ACK-1".toByteArray())
        assertEquals(PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND, ind.messageType)
        assertEquals(PduHeaders.MMS_VERSION_1_2, ind.mmsVersion)
        assertEquals("T-ACK-1", String(ind.transactionId!!))
    }

    @Test
    fun reportAllowed_roundTrips() {
        val ind = AcknowledgeInd(PduHeaders.MMS_VERSION_1_2, "T".toByteArray())
        ind.setReportAllowed(PduHeaders.VALUE_YES)
        assertEquals(PduHeaders.VALUE_YES, ind.reportAllowed)
        ind.setReportAllowed(PduHeaders.VALUE_NO)
        assertEquals(PduHeaders.VALUE_NO, ind.reportAllowed)
    }

    @Test(expected = NullPointerException::class)
    fun composeConstructor_nullTransactionId_throws() {
        AcknowledgeInd(PduHeaders.MMS_VERSION_1_2, null)
    }
}

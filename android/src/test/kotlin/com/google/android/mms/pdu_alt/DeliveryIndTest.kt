package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [DeliveryInd] port: the no-arg constructor seeds Message-Type, and
 * the Date / Message-ID / Status / To accessors round-trip. A null Message-ID
 * throws (the inherited PduHeaders text-string null guard).
 */
class DeliveryIndTest {

    @Test
    fun constructor_seedsMessageType() {
        assertEquals(PduHeaders.MESSAGE_TYPE_DELIVERY_IND, DeliveryInd().messageType)
    }

    @Test
    fun date_status_messageId_to_roundTrip() {
        val ind = DeliveryInd()

        ind.setDate(1_700_000_000L)
        assertEquals(1_700_000_000L, ind.date)

        ind.setStatus(PduHeaders.STATUS_RETRIEVED)
        assertEquals(PduHeaders.STATUS_RETRIEVED, ind.status)

        ind.setMessageId("mid-1".toByteArray())
        assertEquals("mid-1", String(ind.messageId!!))

        ind.setTo(arrayOf(EncodedStringValue("+15555550100")))
        assertEquals(1, ind.to?.size)
        assertEquals("+15555550100", ind.to?.get(0)?.string)
    }

    @Test(expected = NullPointerException::class)
    fun setMessageId_null_throws() {
        DeliveryInd().setMessageId(null)
    }
}

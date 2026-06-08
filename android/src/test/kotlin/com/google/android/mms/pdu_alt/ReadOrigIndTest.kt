package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [ReadOrigInd] port: the no-arg constructor seeds Message-Type, the
 * Date / From (overridden) / Message-ID / Read-Status / To accessors round-trip,
 * and a null Message-ID throws (the inherited PduHeaders text-string guard).
 */
class ReadOrigIndTest {

    @Test
    fun constructor_seedsMessageType() {
        assertEquals(PduHeaders.MESSAGE_TYPE_READ_ORIG_IND, ReadOrigInd().messageType)
    }

    @Test
    fun accessors_roundTrip() {
        val ind = ReadOrigInd()

        ind.setDate(1_700_000_000L)
        assertEquals(1_700_000_000L, ind.date)

        ind.setFrom(EncodedStringValue("+15555550100"))
        assertEquals("+15555550100", ind.from?.string)

        ind.setMessageId("mid-1".toByteArray())
        assertEquals("mid-1", String(ind.messageId!!))

        ind.setReadStatus(PduHeaders.READ_STATUS_READ)
        assertEquals(PduHeaders.READ_STATUS_READ, ind.readStatus)

        ind.setTo(arrayOf(EncodedStringValue("+15555550111")))
        assertEquals(1, ind.to?.size)
        assertEquals("+15555550111", ind.to?.get(0)?.string)
    }

    @Test(expected = NullPointerException::class)
    fun setMessageId_null_throws() {
        ReadOrigInd().setMessageId(null)
    }
}

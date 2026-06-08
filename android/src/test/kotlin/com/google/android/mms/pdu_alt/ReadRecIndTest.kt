package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [ReadRecInd] port: the compose constructor seeds Message-Type / From
 * (inherited setter) / Message-ID / MMS-Version / To / Read-Status, the Date
 * accessor round-trips, and a null Message-ID throws (inherited PduHeaders guard).
 */
class ReadRecIndTest {

    @Test
    fun composeConstructor_seedsAllFields() {
        val ind = ReadRecInd(
            EncodedStringValue("+15555550100"),
            "mid-RRI".toByteArray(),
            PduHeaders.MMS_VERSION_1_2,
            PduHeaders.READ_STATUS_READ,
            arrayOf(EncodedStringValue("+15555550111")),
        )
        assertEquals(PduHeaders.MESSAGE_TYPE_READ_REC_IND, ind.messageType)
        assertEquals(PduHeaders.MMS_VERSION_1_2, ind.mmsVersion)
        assertEquals("+15555550100", ind.from?.string)
        assertEquals("mid-RRI", String(ind.messageId!!))
        assertEquals(PduHeaders.READ_STATUS_READ, ind.readStatus)
        assertEquals(1, ind.to?.size)
        assertEquals("+15555550111", ind.to?.get(0)?.string)
    }

    @Test
    fun date_roundTrips() {
        val ind = ReadRecInd(
            EncodedStringValue("+1"),
            "m".toByteArray(),
            PduHeaders.MMS_VERSION_1_2,
            PduHeaders.READ_STATUS_READ,
            arrayOf(EncodedStringValue("+2")),
        )
        ind.setDate(1_700_000_000L)
        assertEquals(1_700_000_000L, ind.date)
    }

    @Test(expected = NullPointerException::class)
    fun composeConstructor_nullMessageId_throws() {
        ReadRecInd(
            EncodedStringValue("+1"),
            null,
            PduHeaders.MMS_VERSION_1_2,
            PduHeaders.READ_STATUS_READ,
            arrayOf(EncodedStringValue("+2")),
        )
    }
}

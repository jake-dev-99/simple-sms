package com.google.android.mms.pdu_alt

import com.google.android.mms.InvalidHeaderValueException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the [GenericPdu] port: the no-arg constructor seeds a [PduHeaders], the
 * Message-Type / MMS-Version / From accessors delegate to it, and the
 * validation behaviour they inherit (MESSAGE_TYPE throws on an invalid octet,
 * MMS_VERSION clamps out-of-range to CURRENT) flows through unchanged.
 */
class GenericPduTest {

    @Test
    fun newPdu_seedsHeaders() {
        assertNotNull(GenericPdu().getPduHeaders())
    }

    @Test
    fun messageType_roundTrips_andDefaultsToZero() {
        val pdu = GenericPdu()
        assertEquals(0, pdu.messageType) // not-set octet default
        pdu.setMessageType(PduHeaders.MESSAGE_TYPE_SEND_REQ)
        assertEquals(PduHeaders.MESSAGE_TYPE_SEND_REQ, pdu.messageType)
    }

    @Test(expected = InvalidHeaderValueException::class)
    fun setMessageType_belowRange_throws() {
        // 0 is below MESSAGE_TYPE_SEND_REQ (0x80) → invalid octet.
        GenericPdu().setMessageType(0)
    }

    @Test
    fun mmsVersion_roundTrips() {
        val pdu = GenericPdu()
        pdu.setMmsVersion(PduHeaders.MMS_VERSION_1_2)
        assertEquals(PduHeaders.MMS_VERSION_1_2, pdu.mmsVersion)
    }

    @Test
    fun setMmsVersion_outOfRange_clampsToCurrent() {
        // MMS_VERSION clamps rather than throwing (inherited PduHeaders behaviour).
        val pdu = GenericPdu()
        pdu.setMmsVersion(0xFF)
        assertEquals(PduHeaders.CURRENT_MMS_VERSION, pdu.mmsVersion)
    }

    @Test
    fun from_roundTrips() {
        val pdu = GenericPdu()
        assertNull(pdu.from)
        pdu.setFrom(EncodedStringValue("+15555550100"))
        assertEquals("+15555550100", pdu.from?.string)
    }

    @Test
    fun getPduHeaders_reflectsAccessorWrites() {
        val pdu = GenericPdu()
        pdu.setMessageType(PduHeaders.MESSAGE_TYPE_SEND_CONF)
        assertEquals(
            PduHeaders.MESSAGE_TYPE_SEND_CONF,
            pdu.getPduHeaders()!!.getOctet(PduHeaders.MESSAGE_TYPE),
        )
    }
}

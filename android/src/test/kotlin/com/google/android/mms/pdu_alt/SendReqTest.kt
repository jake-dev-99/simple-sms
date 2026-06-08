package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [SendReq] port: the no-arg constructor's full header seeding, the
 * 4-arg compose constructor, and the SendReq-specific accessors (Bcc/Cc append +
 * set, Content-Type, Delivery-Report, Expiry, Message-Size, Message-Class,
 * Read-Report, setTo, Transaction-Id) round-trip.
 */
class SendReqTest {

    @Test
    fun noArgConstructor_seedsDefaultHeaders() {
        val req = SendReq()
        assertEquals(PduHeaders.MESSAGE_TYPE_SEND_REQ, req.messageType)
        assertEquals(PduHeaders.CURRENT_MMS_VERSION, req.mmsVersion)
        assertEquals("application/vnd.wap.multipart.related", String(req.contentType!!))
        assertEquals(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR, req.from?.string)
        // generateTransactionId() → "T" + hex(now)
        assertTrue("transactionId starts with T", String(req.transactionId!!).startsWith("T"))
    }

    @Test
    fun composeConstructor_setsFields() {
        val req = SendReq(
            "application/vnd.wap.multipart.related".toByteArray(),
            EncodedStringValue("+15555550100"),
            PduHeaders.MMS_VERSION_1_2,
            "T-SR-1".toByteArray(),
        )
        assertEquals(PduHeaders.MESSAGE_TYPE_SEND_REQ, req.messageType)
        assertEquals(PduHeaders.MMS_VERSION_1_2, req.mmsVersion)
        assertEquals("+15555550100", req.from?.string)
        assertEquals("T-SR-1", String(req.transactionId!!))
    }

    @Test
    fun accessors_roundTrip() {
        val req = SendReq()

        req.addBcc(EncodedStringValue("+1"))
        req.addBcc(EncodedStringValue("+2"))
        assertEquals(2, req.bcc?.size)
        req.setBcc(arrayOf(EncodedStringValue("+9")))
        assertEquals(1, req.bcc?.size)

        req.addCc(EncodedStringValue("+3"))
        assertEquals(1, req.cc?.size)
        req.setCc(arrayOf(EncodedStringValue("+4"), EncodedStringValue("+5")))
        assertEquals(2, req.cc?.size)

        req.setExpiry(604_800L)
        assertEquals(604_800L, req.expiry)

        req.setMessageSize(2048L)
        assertEquals(2048L, req.messageSize)

        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
        assertEquals(PduHeaders.MESSAGE_CLASS_PERSONAL_STR, String(req.messageClass!!))

        req.setDeliveryReport(PduHeaders.VALUE_YES)
        assertEquals(PduHeaders.VALUE_YES, req.deliveryReport)

        req.setReadReport(PduHeaders.VALUE_NO)
        assertEquals(PduHeaders.VALUE_NO, req.readReport)

        // setTo is added by SendReq (the base only has getTo + addTo).
        req.setTo(arrayOf(EncodedStringValue("+15555550111")))
        assertEquals(1, req.to?.size)
        assertEquals("+15555550111", req.to?.get(0)?.string)

        req.setTransactionId("T-X".toByteArray())
        assertEquals("T-X", String(req.transactionId!!))
    }
}

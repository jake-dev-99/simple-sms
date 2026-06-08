package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [SendConf] port: the no-arg constructor seeds Message-Type, and the
 * Message-ID / Response-Status / Transaction-Id accessors round-trip. A null
 * Message-ID throws (the inherited PduHeaders text-string guard).
 */
class SendConfTest {

    @Test
    fun constructor_seedsMessageType() {
        assertEquals(PduHeaders.MESSAGE_TYPE_SEND_CONF, SendConf().messageType)
    }

    @Test
    fun accessors_roundTrip() {
        val conf = SendConf()

        conf.setMessageId("mid-SC".toByteArray())
        assertEquals("mid-SC", String(conf.messageId!!))

        // RESPONSE_STATUS_OK is in range, so it round-trips unchanged.
        conf.setResponseStatus(PduHeaders.RESPONSE_STATUS_OK)
        assertEquals(PduHeaders.RESPONSE_STATUS_OK, conf.responseStatus)

        conf.setTransactionId("T-SC".toByteArray())
        assertEquals("T-SC", String(conf.transactionId!!))
    }

    @Test(expected = NullPointerException::class)
    fun setMessageId_null_throws() {
        SendConf().setMessageId(null)
    }
}

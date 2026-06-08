package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [RetrieveConf] port: the no-arg constructor seeds Message-Type, the
 * RetrieveConf-specific accessors round-trip (Cc append, Content-Type,
 * Delivery-Report, From [overridden], Message-Class, Message-ID, Read-Report,
 * Retrieve-Status, Retrieve-Text, Transaction-Id), and an inherited
 * MultimediaMessagePdu accessor (subject) still works.
 */
class RetrieveConfTest {

    @Test
    fun constructor_seedsMessageType() {
        assertEquals(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF, RetrieveConf().messageType)
    }

    @Test
    fun specificAccessors_roundTrip() {
        val conf = RetrieveConf()

        conf.addCc(EncodedStringValue("+15555550100"))
        conf.addCc(EncodedStringValue("+15555550111"))
        assertEquals(2, conf.cc?.size)

        conf.setContentType("application/vnd.wap.multipart.related".toByteArray())
        assertEquals("application/vnd.wap.multipart.related", String(conf.contentType!!))

        conf.setDeliveryReport(PduHeaders.VALUE_YES)
        assertEquals(PduHeaders.VALUE_YES, conf.deliveryReport)

        conf.setFrom(EncodedStringValue("+15555550122"))
        assertEquals("+15555550122", conf.from?.string)

        conf.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
        assertEquals(PduHeaders.MESSAGE_CLASS_PERSONAL_STR, String(conf.messageClass!!))

        conf.setMessageId("MID-1".toByteArray())
        assertEquals("MID-1", String(conf.messageId!!))

        conf.setReadReport(PduHeaders.VALUE_NO)
        assertEquals(PduHeaders.VALUE_NO, conf.readReport)

        conf.setRetrieveStatus(PduHeaders.RETRIEVE_STATUS_OK)
        assertEquals(PduHeaders.RETRIEVE_STATUS_OK, conf.retrieveStatus)

        conf.setRetrieveText(EncodedStringValue("ok"))
        assertEquals("ok", conf.retrieveText?.string)

        conf.setTransactionId("T-RC".toByteArray())
        assertEquals("T-RC", String(conf.transactionId!!))
    }

    @Test
    fun inheritedAccessor_subject_stillWorks() {
        val conf = RetrieveConf()
        conf.setSubject(EncodedStringValue("hello"))
        assertEquals("hello", conf.subject?.string)
    }
}

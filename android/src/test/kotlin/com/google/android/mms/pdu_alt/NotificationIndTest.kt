package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [NotificationInd] port: the no-arg constructor seeds Message-Type and
 * the full accessor set round-trips (Content-Class, Content-Location, Expiry,
 * From [overridden], Message-Class, Message-Size, Subject, Transaction-Id,
 * Delivery-Report). A null Content-Location throws (inherited PduHeaders guard).
 */
class NotificationIndTest {

    @Test
    fun constructor_seedsMessageType() {
        assertEquals(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND, NotificationInd().messageType)
    }

    @Test
    fun accessors_roundTrip() {
        val ind = NotificationInd()

        ind.setContentClass(PduHeaders.CONTENT_CLASS_TEXT)
        assertEquals(PduHeaders.CONTENT_CLASS_TEXT, ind.contentClass)

        ind.setContentLocation("http://mms/x".toByteArray())
        assertEquals("http://mms/x", String(ind.contentLocation!!))

        ind.setExpiry(604_800L)
        assertEquals(604_800L, ind.expiry)

        ind.setFrom(EncodedStringValue("+15555550100"))
        assertEquals("+15555550100", ind.from?.string)

        ind.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
        assertEquals(PduHeaders.MESSAGE_CLASS_PERSONAL_STR, String(ind.messageClass!!))

        ind.setMessageSize(12_345L)
        assertEquals(12_345L, ind.messageSize)

        ind.setSubject(EncodedStringValue("hi"))
        assertEquals("hi", ind.subject?.string)

        ind.setTransactionId("T-NI".toByteArray())
        assertEquals("T-NI", String(ind.transactionId!!))

        ind.setDeliveryReport(PduHeaders.VALUE_YES)
        assertEquals(PduHeaders.VALUE_YES, ind.deliveryReport)
    }

    @Test(expected = NullPointerException::class)
    fun setContentLocation_null_throws() {
        NotificationInd().setContentLocation(null)
    }
}

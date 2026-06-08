package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the [MultimediaMessagePdu] port: the no-arg constructor leaves a null
 * body, the `(header, body)` constructor stores the body, and the Subject / To
 * (append) / Priority / Date / Body accessors round-trip.
 */
class MultimediaMessagePduTest {

    @Test
    fun noArgConstructor_hasNullBody() {
        assertNull(MultimediaMessagePdu().body)
    }

    @Test
    fun headerBodyConstructor_storesBody() {
        val body = PduBody()
        val pdu = MultimediaMessagePdu(PduHeaders(), body)
        assertSame(body, pdu.body)
    }

    @Test
    fun accessors_roundTrip() {
        val pdu = MultimediaMessagePdu()

        pdu.setSubject(EncodedStringValue("hello"))
        assertEquals("hello", pdu.subject?.string)

        // addTo appends.
        pdu.addTo(EncodedStringValue("+15555550100"))
        pdu.addTo(EncodedStringValue("+15555550111"))
        assertEquals(2, pdu.to?.size)
        assertEquals("+15555550100", pdu.to?.get(0)?.string)
        assertEquals("+15555550111", pdu.to?.get(1)?.string)

        pdu.setPriority(PduHeaders.PRIORITY_NORMAL)
        assertEquals(PduHeaders.PRIORITY_NORMAL, pdu.priority)

        pdu.setDate(1_700_000_000L)
        assertEquals(1_700_000_000L, pdu.date)

        val body = PduBody()
        pdu.setBody(body)
        assertSame(body, pdu.body)
    }
}

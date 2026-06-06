package com.google.android.mms.pdu_alt

import com.google.android.mms.InvalidHeaderValueException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the [PduHeaders] port: the octet validation/clamping switch, the
 * text-string / encoded-string-value(s) / long-integer typed accessors with
 * their field-allowlist `RuntimeException`s and null `NullPointerException`s,
 * the "not set" defaults (octet→0, long→-1), and a sample of the constant table
 * (incl. the intentional vendored duplicates).
 */
class PduHeadersTest {

    @Test
    fun octet_defaultZero_andRoundTrip() {
        val h = PduHeaders()
        assertEquals(0, h.getOctet(PduHeaders.PRIORITY))
        h.setOctet(PduHeaders.PRIORITY_NORMAL, PduHeaders.PRIORITY)
        assertEquals(PduHeaders.PRIORITY_NORMAL, h.getOctet(PduHeaders.PRIORITY))
    }

    @Test(expected = InvalidHeaderValueException::class)
    fun octet_invalidPriority_throws() {
        PduHeaders().setOctet(0x99, PduHeaders.PRIORITY)
    }

    @Test(expected = RuntimeException::class)
    fun octet_nonOctetField_throws() {
        // DATE is a long-integer field, not an octet field.
        PduHeaders().setOctet(0x80, PduHeaders.DATE)
    }

    @Test
    fun octet_yesNoFields_validateValueYesNo() {
        val h = PduHeaders()
        h.setOctet(PduHeaders.VALUE_YES, PduHeaders.DELIVERY_REPORT)
        assertEquals(PduHeaders.VALUE_YES, h.getOctet(PduHeaders.DELIVERY_REPORT))
        var threw = false
        try {
            h.setOctet(0x83, PduHeaders.DELIVERY_REPORT) // neither YES(0x80) nor NO(0x81)
        } catch (e: InvalidHeaderValueException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    @Test
    fun octet_mmsVersion_clampsInvalidToCurrent() {
        val h = PduHeaders()
        h.setOctet(0x99, PduHeaders.MMS_VERSION) // out of [1_0..1_3] → CURRENT
        assertEquals(PduHeaders.CURRENT_MMS_VERSION, h.getOctet(PduHeaders.MMS_VERSION))
    }

    @Test
    fun octet_retrieveStatus_clampsToTransientFailure() {
        val h = PduHeaders()
        // 0xC5 is > TRANSIENT_NETWORK_PROBLEM(0xC2) and < PERMANENT_FAILURE(0xE0).
        h.setOctet(0xC5, PduHeaders.RETRIEVE_STATUS)
        assertEquals(
            PduHeaders.RETRIEVE_STATUS_ERROR_TRANSIENT_FAILURE,
            h.getOctet(PduHeaders.RETRIEVE_STATUS),
        )
    }

    @Test
    fun octet_responseStatus_clampsHighToPermanentFailure() {
        val h = PduHeaders()
        // 0xEC is > LACK_OF_PREPAID(0xEB) and <= PERMANENT_END(0xFF).
        h.setOctet(0xEC, PduHeaders.RESPONSE_STATUS)
        assertEquals(
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_FAILURE,
            h.getOctet(PduHeaders.RESPONSE_STATUS),
        )
    }

    @Test
    fun textString_roundTrip_andFieldGuards() {
        val h = PduHeaders()
        val tid = "T-1".toByteArray(Charsets.US_ASCII)
        h.setTextString(tid, PduHeaders.TRANSACTION_ID)
        assertArrayEquals(tid, h.getTextString(PduHeaders.TRANSACTION_ID))
        assertNull(h.getTextString(PduHeaders.MESSAGE_ID))

        var threwField = false
        try {
            h.setTextString(tid, PduHeaders.DATE) // not a text-string field
        } catch (e: RuntimeException) {
            threwField = true
        }
        assertEquals(true, threwField)

        var threwNull = false
        try {
            h.setTextString(null, PduHeaders.TRANSACTION_ID)
        } catch (e: NullPointerException) {
            threwNull = true
        }
        assertEquals(true, threwNull)
    }

    @Test
    fun encodedStringValue_roundTrip() {
        val h = PduHeaders()
        val subj = EncodedStringValue("hello")
        h.setEncodedStringValue(subj, PduHeaders.SUBJECT)
        assertEquals("hello", h.getEncodedStringValue(PduHeaders.SUBJECT)?.string)
    }

    @Test
    fun encodedStringValues_setGetAppend() {
        val h = PduHeaders()
        val a = EncodedStringValue("+1")
        val b = EncodedStringValue("+2")
        h.setEncodedStringValues(arrayOf(a, b), PduHeaders.TO)
        assertEquals(2, h.getEncodedStringValues(PduHeaders.TO)?.size)
        h.appendEncodedStringValue(EncodedStringValue("+3"), PduHeaders.TO)
        assertEquals(3, h.getEncodedStringValues(PduHeaders.TO)?.size)
        // append onto an empty field initialises the list.
        h.appendEncodedStringValue(a, PduHeaders.CC)
        assertEquals(1, h.getEncodedStringValues(PduHeaders.CC)?.size)
        assertNull(h.getEncodedStringValues(PduHeaders.BCC))
    }

    @Test
    fun longInteger_defaultMinusOne_andRoundTrip() {
        val h = PduHeaders()
        assertEquals(-1L, h.getLongInteger(PduHeaders.DATE))
        h.setLongInteger(1_700_000_000L, PduHeaders.DATE)
        assertEquals(1_700_000_000L, h.getLongInteger(PduHeaders.DATE))

        var threw = false
        try {
            h.setLongInteger(1L, PduHeaders.PRIORITY) // not a long-integer field
        } catch (e: RuntimeException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    @Test
    fun constants_andVendoredDuplicates() {
        assertEquals(0x8C, PduHeaders.MESSAGE_TYPE)
        assertEquals(0x80, PduHeaders.MESSAGE_TYPE_SEND_REQ)
        assertEquals(0x12, PduHeaders.MMS_VERSION_1_2)
        assertEquals(0x13, PduHeaders.MMS_VERSION_1_3)
        assertEquals(PduHeaders.MMS_VERSION_1_2, PduHeaders.CURRENT_MMS_VERSION)
        // Vendored duplicate values, preserved.
        assertEquals(0x90, PduHeaders.READ_REPLY)
        assertEquals(PduHeaders.READ_REPLY, PduHeaders.READ_REPORT)
        assertEquals(
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_LIMITATIONS_NOT_MET,
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_REQUEST_NOT_ACCEPTED,
        )
        assertEquals("personal", PduHeaders.MESSAGE_CLASS_PERSONAL_STR)
        assertEquals("insert-address-token", PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR)
    }
}

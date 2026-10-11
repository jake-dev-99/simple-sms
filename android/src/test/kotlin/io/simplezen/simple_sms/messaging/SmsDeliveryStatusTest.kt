package io.simplezen.simple_sms.messaging

import android.provider.Telephony
import android.telephony.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SMS delivery-report golden (UNFY-293). The delivery `PendingIntent` carries
 * the status report (`pdu`, `format`); its status maps to the provider's
 * `STATUS_COMPLETE` / `STATUS_PENDING` / `STATUS_FAILED`, and anything unknown
 * maps to null so the row is left as it is rather than guessed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsDeliveryStatusTest {

    @Test
    fun gsmTpStatus_rangesMapToCompletePendingFailed() {
        val cases = mapOf(
            0x00 to Telephony.Sms.STATUS_COMPLETE, // received by the recipient
            0x1F to Telephony.Sms.STATUS_COMPLETE,
            0x20 to Telephony.Sms.STATUS_PENDING, // SC still trying
            0x3F to Telephony.Sms.STATUS_PENDING,
            0x40 to Telephony.Sms.STATUS_FAILED, // permanent error
            0x60 to Telephony.Sms.STATUS_FAILED, // temporary error, SC gave up
        )
        cases.forEach { (tpStatus, expected) ->
            assertEquals("TP-Status 0x%02X".format(tpStatus), expected,
                deliveryStatusFromReport(tpStatus, SmsMessage.FORMAT_3GPP))
        }
    }

    @Test
    fun cdmaErrorClassAndMessageStatus_mapToDeliveryStatus() {
        fun cdma(errorClass: Int, messageStatus: Int) = (errorClass shl 24) or (messageStatus shl 16)
        val format = SmsMessage.FORMAT_3GPP2
        assertEquals(Telephony.Sms.STATUS_COMPLETE, deliveryStatusFromReport(cdma(0, 2), format))
        assertEquals("a delivery ack without the optional status is a delivery",
            Telephony.Sms.STATUS_COMPLETE, deliveryStatusFromReport(cdma(0, 0), format))
        assertEquals(Telephony.Sms.STATUS_PENDING, deliveryStatusFromReport(cdma(2, 2), format))
        assertEquals(Telephony.Sms.STATUS_FAILED, deliveryStatusFromReport(cdma(3, 2), format))
        assertEquals("cancelled is terminal", Telephony.Sms.STATUS_FAILED, deliveryStatusFromReport(cdma(0, 3), format))
        assertNull("an unknown error class is not guessed", deliveryStatusFromReport(cdma(1, 2), format))
        assertNull("undefined values are not guessed", deliveryStatusFromReport(cdma(0xFF, 0xFF), format))
        assertNull(deliveryStatusFromReport(cdma(0, 0xFF), format))
    }

    @Test
    fun parsesARealGsmStatusReportPdu() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE,
            smsDeliveryStatus(gsmStatusReportPdu(tpStatus = 0x00), SmsMessage.FORMAT_3GPP))
        assertEquals(Telephony.Sms.STATUS_FAILED,
            smsDeliveryStatus(gsmStatusReportPdu(tpStatus = 0x41), SmsMessage.FORMAT_3GPP))
    }

    @Test
    fun missingOrUnparseableReport_isUnknown() {
        assertNull(smsDeliveryStatus(null, SmsMessage.FORMAT_3GPP))
        assertNull(smsDeliveryStatus(gsmStatusReportPdu(tpStatus = 0x00), null))
        assertNull(smsDeliveryStatus(byteArrayOf(0x00), SmsMessage.FORMAT_3GPP))
    }

    companion object {
        /** A 3GPP TS 23.040 SMS-STATUS-REPORT with no SMSC address. */
        fun gsmStatusReportPdu(tpStatus: Int): ByteArray = hex(
            "00" + // SMSC address length: none
                "06" + // first octet: TP-MTI = status report, TP-MMS
                "01" + // TP-MR
                "0B91" + "1326880736F4" + // TP-RA: 11 international digits
                "21301010000000" + // TP-SCTS
                "21301010000500" + // TP-DT
                "%02X".format(tpStatus), // TP-ST
        )

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

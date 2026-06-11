package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MMS *sent* result-code mapping golden (UNFY-178).
 *
 * MMS send results are `SmsManager.MMS_ERROR_*` values — a different enum
 * from the SMS sent (`RESULT_*`) and the delivery enums. The three collide
 * numerically: e.g. resultCode 5 is `MMS_ERROR_IO_ERROR` on an MMS_SENT
 * broadcast but means something else under the delivery enum. The original
 * bug routed MMS_SENT through `getDeliveredStatusString`, so a real failed
 * group MMS (resultCode 5) surfaced as the bogus "DELIVERED_STATUS_UNKNOWN
 * (5)" and the message still showed as sent. This pins the dedicated MMS
 * mapping so that regression can't return silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundMmsSentStatusTest {

    private val receiver = OutboundMessagingReceiver()

    @Test
    fun resultOk_mapsTo_sentSuccess() {
        assertEquals("SENT_SUCCESS", receiver.getMmsSentStatusString(Activity.RESULT_OK))
    }

    @Test
    fun ioError_mapsTo_mmsIoError_notDeliveryUnknown() {
        // The exact device repro: resultCode 5 on an MMS_SENT broadcast.
        // Must map to the MMS error name, never the SMS-delivery fallback.
        val status = receiver.getMmsSentStatusString(SmsManager.MMS_ERROR_IO_ERROR)
        assertEquals("ERROR_MMS_IO_ERROR", status)
        assertTrue(
            "must not leak the delivery-enum fallback for a sent failure",
            !status.startsWith("DELIVERED_"),
        )
    }

    @Test
    fun noDataNetwork_mapsTo_mmsNoDataNetwork() {
        assertEquals(
            "ERROR_MMS_NO_DATA_NETWORK",
            receiver.getMmsSentStatusString(SmsManager.MMS_ERROR_NO_DATA_NETWORK),
        )
    }

    @Test
    fun unmodelledCode_fallsThroughTo_mmsUnknownError_withCode() {
        // An unmapped code surfaces the raw value rather than masking it —
        // mirrors the "surface, don't silently default" convention.
        assertEquals(
            "MMS_SENT_UNKNOWN_ERROR (9999)",
            receiver.getMmsSentStatusString(9999),
        )
    }
}

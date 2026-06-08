package com.android.internal.telephony

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [TelephonyProperties] port: a representative sample of the property
 * strings (incl. the only live-consumed one, [TelephonyProperties.PROPERTY_OPERATOR_ISROAMING],
 * read by `DownloadManager.isRoaming`) and the lone non-`PROPERTY_`-prefixed
 * constant ([TelephonyProperties.CURRENT_ACTIVE_PHONE]). Values must match the
 * vendored constant strings exactly — they are OS-level `SystemProperties` keys.
 */
class TelephonyPropertiesTest {

    @Test
    fun keyConstants_matchVendoredValues() {
        assertEquals("gsm.operator.isroaming", TelephonyProperties.PROPERTY_OPERATOR_ISROAMING)
        assertEquals("gsm.version.baseband", TelephonyProperties.PROPERTY_BASEBAND_VERSION)
        assertEquals("gsm.operator.numeric", TelephonyProperties.PROPERTY_OPERATOR_NUMERIC)
        // Note the deliberately un-prefixed "operator." (not "gsm.operator.").
        assertEquals("operator.ismanual", TelephonyProperties.PROPERTY_OPERATOR_ISMANUAL)
        assertEquals("gsm.sim.state", TelephonyProperties.PROPERTY_SIM_STATE)
        assertEquals("gsm.current.phone-type", TelephonyProperties.CURRENT_ACTIVE_PHONE)
        assertEquals("ro.telephony.call_ring.multiple", TelephonyProperties.PROPERTY_RIL_SENDS_MULTIPLE_CALL_RING)
        assertEquals("telephony.test.ignore.nitz", TelephonyProperties.PROPERTY_IGNORE_NITZ)
    }
}

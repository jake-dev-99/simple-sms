package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the WAP well-known content-type table of the [PduContentTypes] port.
 * The array is indexed by the on-wire assigned number, so both the exact
 * length (the parser bounds-checks `index < contentTypes.length`) and the
 * index→string mapping are load-bearing — a shifted or dropped entry would
 * silently mislabel MMS part content types on the wire.
 */
class PduContentTypesTest {

    @Test
    fun length_matchesAssignedNumberRange() {
        // 0x00..0x52 inclusive = 83 entries.
        assertEquals(83, PduContentTypes.contentTypes.size)
    }

    @Test
    fun boundaryEntries_matchVendoredValues() {
        assertEquals("*/*", PduContentTypes.contentTypes[0x00])
        assertEquals("application/mikey", PduContentTypes.contentTypes[0x52])
    }

    @Test
    fun spotCheck_knownIndices() {
        assertEquals("text/plain", PduContentTypes.contentTypes[0x03])
        assertEquals("image/jpeg", PduContentTypes.contentTypes[0x1E])
        assertEquals("image/png", PduContentTypes.contentTypes[0x20])
        // The MMS message type itself — the one the codec round-trips most.
        assertEquals("application/vnd.wap.mms-message", PduContentTypes.contentTypes[0x3E])
        assertEquals("audio/*", PduContentTypes.contentTypes[0x4F])
        assertEquals("video/*", PduContentTypes.contentTypes[0x50])
    }
}

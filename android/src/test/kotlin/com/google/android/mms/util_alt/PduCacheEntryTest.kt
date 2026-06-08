package com.google.android.mms.util_alt

import com.google.android.mms.pdu_alt.GenericPdu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the [PduCacheEntry] port: the three fields are stored and returned
 * verbatim via the vendored getter shapes, and a null pdu is accepted (the
 * vendored field is a plain reference).
 */
class PduCacheEntryTest {

    @Test
    fun holdsAndReturnsItsThreeFields() {
        val pdu = GenericPdu()
        val entry = PduCacheEntry(pdu, 1, 42L)
        assertSame(pdu, entry.pdu)
        assertEquals(1, entry.messageBox)
        assertEquals(42L, entry.threadId)
    }

    @Test
    fun allowsNullPdu() {
        val entry = PduCacheEntry(null, 0, 0L)
        assertNull(entry.pdu)
        assertEquals(0, entry.messageBox)
        assertEquals(0L, entry.threadId)
    }
}

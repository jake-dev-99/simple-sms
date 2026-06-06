package com.google.android.mms.pdu_alt

import java.io.UnsupportedEncodingException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the IANA MIBenum ↔ charset-name lookups of the [CharacterSets] port and
 * the load-bearing constants the codec depends on. Both directions throw
 * `UnsupportedEncodingException` for an unknown key (except the null-name → -1
 * short-circuit), exactly as the vendored implementation.
 */
class CharacterSetsTest {

    @Test
    fun constants_matchVendoredValues() {
        assertEquals(0x00, CharacterSets.ANY_CHARSET)
        assertEquals(0x6A, CharacterSets.UTF_8)
        assertEquals(CharacterSets.UTF_8, CharacterSets.DEFAULT_CHARSET)
        assertEquals("utf-8", CharacterSets.MIMENAME_UTF_8)
        assertEquals("utf-8", CharacterSets.DEFAULT_CHARSET_NAME)
        assertEquals("iso-8859-1", CharacterSets.MIMENAME_ISO_8859_1)
        assertEquals("*", CharacterSets.MIMENAME_ANY_CHARSET)
    }

    @Test
    fun getMimeName_mapsKnownMibEnum() {
        assertEquals("utf-8", CharacterSets.getMimeName(CharacterSets.UTF_8))
        assertEquals("*", CharacterSets.getMimeName(CharacterSets.ANY_CHARSET))
        assertEquals("iso-10646-ucs-2", CharacterSets.getMimeName(CharacterSets.UCS2))
    }

    @Test(expected = UnsupportedEncodingException::class)
    fun getMimeName_throwsForUnknownMibEnum() {
        CharacterSets.getMimeName(0x99)
    }

    @Test
    fun getMibEnumValue_mapsKnownName() {
        assertEquals(CharacterSets.UTF_8, CharacterSets.getMibEnumValue("utf-8"))
        assertEquals(CharacterSets.ISO_8859_1, CharacterSets.getMibEnumValue("iso-8859-1"))
    }

    @Test
    fun getMibEnumValue_nullName_returnsMinusOne() {
        assertEquals(-1, CharacterSets.getMibEnumValue(null))
    }

    @Test(expected = UnsupportedEncodingException::class)
    fun getMibEnumValue_throwsForUnknownName() {
        CharacterSets.getMibEnumValue("not-a-real-charset")
    }
}

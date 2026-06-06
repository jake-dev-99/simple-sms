package com.google.android.mms.pdu_alt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the byte-level decode behaviour of the [QuotedPrintable] port — the
 * quirks a faithful port must preserve: hex un-escaping, the soft-line-break
 * (`=\r\n`) skip, and the two "decoding unsuccessful → null" paths (an invalid
 * hex digit, and a truncated escape that runs off the end of the array).
 */
class QuotedPrintableTest {

    private fun decode(s: String): ByteArray? =
        QuotedPrintable.decodeQuotedPrintable(s.toByteArray(Charsets.US_ASCII))

    @Test
    fun nullInput_returnsNull() {
        assertNull(QuotedPrintable.decodeQuotedPrintable(null))
    }

    @Test
    fun plainBytes_passThroughUnchanged() {
        assertArrayEquals("plain text".toByteArray(Charsets.US_ASCII), decode("plain text"))
    }

    @Test
    fun hexEscape_isUnescaped() {
        // =3D -> '=' (0x3D), =20 -> ' ' (0x20).
        assertArrayEquals("a=b".toByteArray(Charsets.US_ASCII), decode("a=3Db"))
        assertArrayEquals("a b".toByteArray(Charsets.US_ASCII), decode("a=20b"))
    }

    @Test
    fun hexEscape_isCaseInsensitive() {
        // Character.digit accepts lower- and upper-case hex; 0x0A is a newline.
        assertArrayEquals(byteArrayOf(0x0A), decode("=0a"))
        assertArrayEquals(byteArrayOf(0x0A), decode("=0A"))
    }

    @Test
    fun softLineBreak_isDropped() {
        // '=' followed by CRLF is a soft line break: the '=', CR and LF all vanish.
        assertArrayEquals("ab".toByteArray(Charsets.US_ASCII), decode("a=\r\nb"))
    }

    @Test
    fun invalidHexDigit_returnsNull() {
        // 'G' is not a hex digit -> Character.digit returns -1 -> null.
        assertNull(decode("=G0"))
        assertNull(decode("=0G"))
    }

    @Test
    fun truncatedEscape_returnsNull() {
        // '=' with fewer than two following bytes runs off the end -> AIOOBE -> null.
        assertNull(decode("=3"))
        assertNull(decode("="))
    }
}

package com.google.android.mms.pdu_alt

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins the decode behaviour of the [Base64] port — the RFC 2045 quirks a
 * faithful port must preserve: the 0/1/2-pad quadruple cases, the
 * discard-non-alphabet grooming, and the all-pad → empty edge case.
 */
class Base64Test {

    private fun decode(s: String): ByteArray =
        Base64.decodeBase64(s.toByteArray(Charsets.US_ASCII))

    @Test
    fun noPad_decodesFullTriple() {
        // "TWFu" -> "Man"
        assertArrayEquals("Man".toByteArray(Charsets.US_ASCII), decode("TWFu"))
    }

    @Test
    fun onePad_decodesTwoBytes() {
        // "TWE=" -> "Ma"
        assertArrayEquals("Ma".toByteArray(Charsets.US_ASCII), decode("TWE="))
    }

    @Test
    fun twoPad_decodesOneByte() {
        // "TQ==" -> "M"
        assertArrayEquals("M".toByteArray(Charsets.US_ASCII), decode("TQ=="))
    }

    @Test
    fun nonBase64Chars_areDiscarded() {
        // RFC 2045: characters outside the alphabet (here a space and a newline)
        // are ignored, so the groomed "TWFu" still decodes to "Man".
        assertArrayEquals("Man".toByteArray(Charsets.US_ASCII), decode("TW Fu"))
        assertArrayEquals("Man".toByteArray(Charsets.US_ASCII), decode("TWFu\n"))
    }

    @Test
    fun emptyInput_decodesEmpty() {
        assertArrayEquals(ByteArray(0), Base64.decodeBase64(ByteArray(0)))
    }

    @Test
    fun allPadding_decodesEmpty() {
        // The padding-strip loop reduces lastData to 0 -> empty result.
        assertArrayEquals(ByteArray(0), decode("===="))
    }

    @Test
    fun multiQuadruple_decodes() {
        // "TWFuTWFu" -> "ManMan"
        assertArrayEquals("ManMan".toByteArray(Charsets.US_ASCII), decode("TWFuTWFu"))
    }
}

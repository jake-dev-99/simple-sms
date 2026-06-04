package com.klinker.android.send_message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [StripAccents.transliterate] — the pure GSM-7 substitution core extracted
 * from the vendored Klinker `StripAccents`. The vectors are real `source → GSM`
 * pairs from the 138-char parallel tables, chosen to be cascade-free (their GSM
 * targets are plain ASCII letters that never re-appear as later sources), and
 * include the **last** table index so a truncated table copy would fail here.
 *
 * Plain JUnit: `transliterate` has no Android dependency. (The public
 * `stripAccents` adds only the `SmsMessage.calculateLength` multi-segment guard
 * in front of this core.)
 */
class StripAccentsTest {

    @Test
    fun singleChars_mapToGsmEquivalents() {
        assertEquals("A", StripAccents.transliterate("α")) // index 0
        assertEquals("B", StripAccents.transliterate("β")) // index 1
        assertEquals("z", StripAccents.transliterate("ż")) // index 69 (middle)
        assertEquals("e", StripAccents.transliterate("ë")) // index 136
        assertEquals("e", StripAccents.transliterate("Ë")) // index 137 (last — proves full table)
    }

    @Test
    fun cascadeFreeRun_transliteratesEachChar() {
        // αβεζηικμ → ABEZHIKM (every target is ASCII, so no re-substitution).
        assertEquals("ABEZHIKM", StripAccents.transliterate("αβεζηικμ"))
    }

    @Test
    fun nonTableCharacters_passThroughUnchanged() {
        // ASCII and unmapped text is untouched; mapped chars within are rewritten.
        assertEquals("hi A!", StripAccents.transliterate("hi α!"))
        assertEquals("plain ascii 123", StripAccents.transliterate("plain ascii 123"))
    }

    @Test
    fun emptyString_isUnchanged() {
        assertEquals("", StripAccents.transliterate(""))
    }
}

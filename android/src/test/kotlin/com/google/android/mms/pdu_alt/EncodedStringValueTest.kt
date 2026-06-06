package com.google.android.mms.pdu_alt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the behaviour of the [EncodedStringValue] port, with emphasis on the
 * Java→Kotlin friction points: charset-aware decode (ANY_CHARSET → default,
 * known MIBenum, iso-8859-1 byte), the defensive byte-array copies, deep
 * `clone`, and the regex/trailing-empty semantics of `split`/`extract`.
 */
class EncodedStringValueTest {

    @Test
    fun byteCtor_defaultsToUtf8() {
        val esv = EncodedStringValue("hello".toByteArray(Charsets.UTF_8))
        assertEquals(CharacterSets.DEFAULT_CHARSET, esv.characterSet)
        assertEquals("hello", esv.string)
    }

    @Test
    fun stringCtor_roundTripsAsUtf8() {
        val esv = EncodedStringValue("héllo")
        assertEquals(CharacterSets.UTF_8, esv.characterSet)
        assertEquals("héllo", esv.string)
        assertArrayEquals("héllo".toByteArray(Charsets.UTF_8), esv.getTextString())
    }

    @Test
    fun charsetCtor_makesDefensiveCopies() {
        val src = "abc".toByteArray(Charsets.UTF_8)
        val esv = EncodedStringValue(CharacterSets.UTF_8, src)
        src[0] = 'z'.code.toByte() // mutate the input after construction
        assertEquals("abc", esv.string) // stored copy is unaffected
        val got = esv.getTextString()
        got[0] = 'z'.code.toByte() // mutate the returned array
        assertEquals("abc", esv.string) // internal state is unaffected
    }

    @Test(expected = NullPointerException::class)
    fun charsetCtor_nullData_throwsNpe() {
        EncodedStringValue(CharacterSets.UTF_8, null)
    }

    @Test(expected = NullPointerException::class)
    fun setTextString_null_throwsNpe() {
        EncodedStringValue("x").setTextString(null)
    }

    @Test
    fun getString_anyCharset_usesDefault() {
        val esv = EncodedStringValue(CharacterSets.ANY_CHARSET, "plain".toByteArray(Charsets.UTF_8))
        assertEquals("plain", esv.string)
    }

    @Test
    fun getString_iso8859_1() {
        // 0xE9 decodes to 'é' under iso-8859-1.
        val esv = EncodedStringValue(CharacterSets.ISO_8859_1, byteArrayOf(0xE9.toByte()))
        assertEquals("é", esv.string)
    }

    @Test
    fun appendTextString_concatenates() {
        val esv = EncodedStringValue("foo")
        esv.appendTextString("bar".toByteArray(Charsets.UTF_8))
        assertEquals("foobar", esv.string)
    }

    @Test
    fun setters_update() {
        val esv = EncodedStringValue("a")
        esv.characterSet = CharacterSets.ISO_8859_1
        assertEquals(CharacterSets.ISO_8859_1, esv.characterSet)
        esv.setTextString("xyz".toByteArray(Charsets.UTF_8))
        assertEquals("xyz", esv.string)
    }

    @Test
    fun clone_isDeepCopy() {
        val esv = EncodedStringValue(CharacterSets.UTF_8, "data".toByteArray(Charsets.UTF_8))
        val c = esv.clone() as EncodedStringValue
        assertEquals(esv.string, c.string)
        assertEquals(esv.characterSet, c.characterSet)
        esv.setTextString("other".toByteArray(Charsets.UTF_8))
        assertEquals("data", c.string) // clone is independent
    }

    @Test
    fun split_regexAndTrailingEmptyStrip() {
        val parts = EncodedStringValue("a,b,c").split(",")!!
        assertEquals(3, parts.size)
        assertEquals("a", parts[0].string)
        assertEquals("c", parts[2].string)
    }

    @Test
    fun extract_splitsOnSemicolonSkippingEmpty() {
        val arr = EncodedStringValue.extract("a;;b;")!!
        // The ";;" empty segment is filtered (length > 0) and the trailing ";"
        // empty is stripped by the regex split.
        assertEquals(2, arr.size)
        assertEquals("a", arr[0].string)
        assertEquals("b", arr[1].string)
    }

    @Test
    fun extract_allEmpty_returnsNull() {
        assertNull(EncodedStringValue.extract(""))
    }

    @Test
    fun concat_joinsWithSemicolon() {
        val arr = arrayOf(
            EncodedStringValue("a"),
            EncodedStringValue("b"),
            EncodedStringValue("c"),
        )
        assertEquals("a;b;c", EncodedStringValue.concat(arr))
    }

    @Test
    fun copy_nullReturnsNull_elseDeepCopies() {
        assertNull(EncodedStringValue.copy(null))
        val esv = EncodedStringValue(CharacterSets.UTF_8, "z".toByteArray(Charsets.UTF_8))
        val c = EncodedStringValue.copy(esv)!!
        assertEquals("z", c.string)
    }

    @Test
    fun encodeStrings_mapsArray() {
        val arr = EncodedStringValue.encodeStrings(arrayOf("p", "q"))!!
        assertEquals(2, arr.size)
        assertEquals("p", arr[0].string)
        assertEquals(CharacterSets.UTF_8, arr[1].characterSet)
    }

    @Test
    fun encodeStrings_empty_returnsNull() {
        assertNull(EncodedStringValue.encodeStrings(arrayOf()))
    }
}

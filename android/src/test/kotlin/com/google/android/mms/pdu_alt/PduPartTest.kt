package com.google.android.mms.pdu_alt

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the behaviour of the [PduPart] port: the defensive data copies, the
 * 0-default charset, the `setContentId` `<…>` wrapping quirk, the NPE-throwing
 * setters, and the `generateLocation` name → filename → content-location → cid
 * fallback chain. (`Uri.parse` needs Robolectric; the rest is pure JVM.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduPartTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun data_defensiveCopyOnSetAndGet() {
        val part = PduPart()
        val src = bytes("hello")
        part.setData(src)
        src[0] = 'z'.code.toByte() // mutate input after set
        assertArrayEquals(bytes("hello"), part.data)
        val got = part.data!!
        got[0] = 'z'.code.toByte() // mutate returned copy
        assertArrayEquals(bytes("hello"), part.data)
    }

    @Test
    fun data_nullWhenUnset_andSetNullIsNoOp() {
        val part = PduPart()
        assertNull(part.data)
        assertEquals(0, part.dataLength)
        part.setData(null) // no-op
        assertNull(part.data)
        part.setData(bytes("abc"))
        assertEquals(3, part.dataLength)
    }

    @Test
    fun dataUri_setGet() {
        val part = PduPart()
        assertNull(part.dataUri)
        val uri = Uri.parse("content://mms/part/1")
        part.setDataUri(uri)
        assertEquals(uri, part.dataUri)
    }

    @Test
    fun charset_defaultsToZero() {
        val part = PduPart()
        assertEquals(0, part.charset)
        part.setCharset(CharacterSets.UTF_8)
        assertEquals(CharacterSets.UTF_8, part.charset)
    }

    @Test
    fun setContentId_wrapsBareIdInAngleBrackets() {
        val part = PduPart()
        part.setContentId(bytes("abc"))
        assertArrayEquals(bytes("<abc>"), part.contentId)
    }

    @Test
    fun setContentId_passesThroughAlreadyWrapped() {
        val part = PduPart()
        part.setContentId(bytes("<abc>"))
        assertArrayEquals(bytes("<abc>"), part.contentId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setContentId_emptyThrows() {
        PduPart().setContentId(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun setContentId_nullThrows() {
        PduPart().setContentId(null)
    }

    @Test(expected = NullPointerException::class)
    fun setContentType_nullThrows() {
        PduPart().setContentType(null)
    }

    @Test
    fun headerByteAccessors_roundTrip() {
        val part = PduPart()
        part.setContentType(bytes("image/png"))
        part.setName(bytes("pic.png"))
        part.setFilename(bytes("pic.png"))
        part.setContentLocation(bytes("loc"))
        part.setContentDisposition(PduPart.DISPOSITION_ATTACHMENT)
        part.setContentTransferEncoding(bytes("base64"))
        assertArrayEquals(bytes("image/png"), part.contentType)
        assertArrayEquals(bytes("pic.png"), part.name)
        assertArrayEquals(bytes("pic.png"), part.filename)
        assertArrayEquals(bytes("loc"), part.contentLocation)
        assertArrayEquals(PduPart.DISPOSITION_ATTACHMENT, part.contentDisposition)
        assertArrayEquals(bytes("base64"), part.contentTransferEncoding)
    }

    @Test
    fun generateLocation_prefersNameThenFilenameThenLocationThenCid() {
        // name wins
        PduPart().apply {
            setName(bytes("theName"))
            setFilename(bytes("theFile"))
            setContentLocation(bytes("theLoc"))
            assertEquals("theName", generateLocation())
        }
        // filename when no name
        PduPart().apply {
            setFilename(bytes("theFile"))
            setContentLocation(bytes("theLoc"))
            assertEquals("theFile", generateLocation())
        }
        // content-location when no name/filename
        PduPart().apply {
            setContentLocation(bytes("theLoc"))
            assertEquals("theLoc", generateLocation())
        }
        // cid: + content-id (wrapped) as last resort
        PduPart().apply {
            setContentId(bytes("abc"))
            assertEquals("cid:<abc>", generateLocation())
        }
    }

    @Test
    fun constants_matchVendoredValues() {
        assertEquals(0x81, PduPart.P_CHARSET)
        assertEquals(0x91, PduPart.P_CONTENT_TYPE)
        assertEquals(0xC0, PduPart.P_CONTENT_ID)
        assertEquals(0xC5, PduPart.P_CONTENT_DISPOSITION)
        assertEquals(0xC8, PduPart.P_CONTENT_TRANSFER_ENCODING)
        assertEquals("base64", PduPart.P_BASE64)
        assertEquals("Content-Transfer-Encoding", PduPart.CONTENT_TRANSFER_ENCODING)
        assertArrayEquals(bytes("from-data"), PduPart.DISPOSITION_FROM_DATA)
        assertArrayEquals(bytes("attachment"), PduPart.DISPOSITION_ATTACHMENT)
        assertArrayEquals(bytes("inline"), PduPart.DISPOSITION_INLINE)
    }
}

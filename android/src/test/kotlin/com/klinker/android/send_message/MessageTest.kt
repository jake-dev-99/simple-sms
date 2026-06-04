package com.klinker.android.send_message

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the behaviour of the Kotlin [Message] port against the vendored Klinker
 * `Message.java` — constructor defaults, the single-address space split, the
 * media adders (mime types + names), array growth, and `bitmapToByteArray`.
 *
 * Robolectric because [Message] references `android.graphics.Bitmap` /
 * `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageTest {

    @Test
    fun defaultConstructor_matchesVendoredDefaults() {
        val m = Message()
        assertEquals("", m.text)
        assertArrayEquals(arrayOf(""), m.addresses)
        assertEquals(0, m.images!!.size)
        assertNull(m.subject)
        assertTrue(m.save)
        assertEquals(0, m.delay)
        assertTrue(m.getParts().isEmpty())
        assertNull(m.fromAddress)
    }

    @Test
    fun textAndAddressArray_setsCoreFields() {
        val m = Message("hi", arrayOf("1", "2"))
        assertEquals("hi", m.text)
        assertArrayEquals(arrayOf("1", "2"), m.addresses)
        assertEquals(0, m.images!!.size)
        assertNull(m.subject)
        assertTrue(m.save)
    }

    @Test
    fun textAndAddressArrayWithSubject_setsSubject() {
        val m = Message("hi", arrayOf("1"), "subj")
        assertEquals("subj", m.subject)
    }

    @Test
    fun singleAddressConstructor_splitsOnSpace() {
        val m = Message("hi", "555 666 777")
        assertArrayEquals(arrayOf("555", "666", "777"), m.addresses)
    }

    @Test
    fun addAddress_appendsToEnd() {
        val m = Message("hi", arrayOf("1"))
        m.addAddress("2")
        assertArrayEquals(arrayOf("1", "2"), m.addresses)
    }

    @Test
    fun setAddress_replacesListWithSingle() {
        val m = Message("hi", arrayOf("1", "2"))
        m.setAddress("9")
        assertArrayEquals(arrayOf("9"), m.addresses)
    }

    @Test
    fun addMedia_addsPartWithMimeAndName() {
        val m = Message("hi", arrayOf("1"))
        m.addMedia(byteArrayOf(1, 2, 3), "application/pdf", "doc.pdf")
        assertEquals(1, m.getParts().size)
        val p = m.getParts()[0]
        assertArrayEquals(byteArrayOf(1, 2, 3), p.getMedia())
        assertEquals("application/pdf", p.getContentType())
        assertEquals("doc.pdf", p.getName())
    }

    @Test
    fun addAudioAndVideo_useExpectedMimeTypes() {
        val m = Message("hi", arrayOf("1"))
        m.addAudio(byteArrayOf(1))
        m.addVideo(byteArrayOf(2))
        assertEquals("audio/wav", m.getParts()[0].getContentType())
        assertNull(m.getParts()[0].getName())
        assertEquals("video/3gpp", m.getParts()[1].getContentType())
    }

    @Test
    fun bitmapToByteArray_nullReturnsEmpty() {
        assertEquals(0, Message.bitmapToByteArray(null).size)
    }

    @Test
    fun bitmapToByteArray_nonNullCompressesToJpegBytes() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertTrue(Message.bitmapToByteArray(bmp).isNotEmpty())
    }

    @Test
    fun addImage_growsImageArray() {
        val m = Message("hi", arrayOf("1"))
        assertEquals(0, m.images!!.size)
        m.addImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        m.addImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        assertEquals(2, m.images!!.size)
    }
}

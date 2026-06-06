package com.google.android.mms.pdu_alt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [PduBody] container behaviour: ordered append/insert/remove, the
 * `partsNum` count, index lookup, and the four content-id / content-location /
 * name / filename lookup maps populated as parts are added.
 * (Robolectric only because [PduPart] references `android.net.Uri`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduBodyTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun partWith(
        contentLocation: String? = null,
        name: String? = null,
        filename: String? = null,
        contentId: String? = null,
    ): PduPart = PduPart().apply {
        contentLocation?.let { setContentLocation(bytes(it)) }
        name?.let { setName(bytes(it)) }
        filename?.let { setFilename(bytes(it)) }
        contentId?.let { setContentId(bytes(it)) }
    }

    @Test
    fun appendAndCount() {
        val body = PduBody()
        assertEquals(0, body.partsNum)
        val p0 = partWith(contentLocation = "a")
        val p1 = partWith(contentLocation = "b")
        assertTrue(body.addPart(p0))
        assertTrue(body.addPart(p1))
        assertEquals(2, body.partsNum)
        assertSame(p0, body.getPart(0))
        assertSame(p1, body.getPart(1))
    }

    @Test
    fun insertAtIndex() {
        val body = PduBody()
        val p0 = partWith(contentLocation = "a")
        val p1 = partWith(contentLocation = "b")
        body.addPart(p0)
        body.addPart(0, p1) // insert at front
        assertEquals(2, body.partsNum)
        assertSame(p1, body.getPart(0))
        assertSame(p0, body.getPart(1))
    }

    @Test
    fun removeAndRemoveAll() {
        val body = PduBody()
        val p0 = partWith(contentLocation = "a")
        val p1 = partWith(contentLocation = "b")
        body.addPart(p0)
        body.addPart(p1)
        assertSame(p0, body.removePart(0))
        assertEquals(1, body.partsNum)
        body.removeAll()
        assertEquals(0, body.partsNum)
    }

    @Test
    fun partIndex() {
        val body = PduBody()
        val p0 = partWith(contentLocation = "a")
        val p1 = partWith(contentLocation = "b")
        body.addPart(p0)
        body.addPart(p1)
        assertEquals(1, body.getPartIndex(p1))
        assertEquals(-1, body.getPartIndex(partWith(contentLocation = "x")))
    }

    @Test
    fun lookupMaps() {
        val body = PduBody()
        val part = partWith(
            contentLocation = "loc1",
            name = "name1",
            filename = "file1",
            contentId = "<cid1>", // already-wrapped → stored verbatim
        )
        body.addPart(part)
        assertSame(part, body.getPartByContentLocation("loc1"))
        assertSame(part, body.getPartByName("name1"))
        assertSame(part, body.getPartByFileName("file1"))
        assertSame(part, body.getPartByContentId("<cid1>"))
        assertNull(body.getPartByContentLocation("nope"))
    }

    @Test
    fun addPart_returnAndNull() {
        val body = PduBody()
        assertTrue(body.addPart(partWith(name = "x")))
        var threw = false
        try {
            body.addPart(null)
        } catch (e: NullPointerException) {
            threw = true
        }
        assertTrue("addPart(null) must throw NPE", threw)
    }

    @Test
    fun emptyBody_hasNoParts() {
        assertFalse(PduBody().partsNum > 0)
    }
}

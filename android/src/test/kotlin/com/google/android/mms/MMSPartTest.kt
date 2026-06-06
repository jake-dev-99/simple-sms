package com.google.android.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the [MMSPart] data-holder port: the field defaults (`Name`/`MimeType` →
 * `""`, `Data`/`Path` → null) and that the public `@JvmField`s are read/write.
 */
class MMSPartTest {

    @Test
    fun defaults() {
        val p = MMSPart()
        assertEquals("", p.Name)
        assertEquals("", p.MimeType)
        assertNull(p.Data)
        assertNull(p.Path)
    }

    @Test
    fun fieldsAreMutable() {
        val p = MMSPart()
        p.Name = "pic.png"
        p.MimeType = "image/png"
        p.Data = byteArrayOf(1, 2, 3)
        assertEquals("pic.png", p.Name)
        assertEquals("image/png", p.MimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), p.Data)
    }
}

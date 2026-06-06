package com.google.android.mms

import java.io.ObjectStreamClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [MmsException] / [InvalidHeaderValueException] port: the constructor
 * overloads (message / cause / both), the type hierarchy the codec relies on
 * (`InvalidHeaderValueException` is-a `MmsException` is-a `Exception`), and the
 * `serialVersionUID`s (kept stable for serialization compatibility).
 */
class MmsExceptionTest {

    @Test
    fun mmsException_constructors() {
        assertNull(MmsException().message)
        assertEquals("boom", MmsException("boom").message)
        val cause = IllegalStateException("c")
        assertSame(cause, MmsException(cause).cause)
        val e = MmsException("msg", cause)
        assertEquals("msg", e.message)
        assertSame(cause, e.cause)
    }

    @Test
    fun invalidHeaderValueException_constructorsAndHierarchy() {
        assertNull(InvalidHeaderValueException().message)
        val e = InvalidHeaderValueException("bad")
        assertEquals("bad", e.message)
        assertTrue("must be an MmsException", e is MmsException)
        assertTrue("must be an Exception", e is Exception)
    }

    @Test
    fun serialVersionUids_areStable() {
        assertEquals(
            -7323249827281485390L,
            ObjectStreamClass.lookup(MmsException::class.java).serialVersionUID,
        )
        assertEquals(
            -2053384496042052262L,
            ObjectStreamClass.lookup(InvalidHeaderValueException::class.java).serialVersionUID,
        )
    }
}

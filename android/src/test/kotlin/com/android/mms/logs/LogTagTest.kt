package com.android.mms.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Pins the [LogTag] port: the `"Mms"`-aliased tag constants and ship-time
 * flags, and the `debug`/`warn`/`error` formatters — the thread-id prefix and
 * the `String[]` pretty-printing. The formatters route through Android [android.util.Log],
 * so the messages are captured via Robolectric's [ShadowLog].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogTagTest {

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    private fun threadPrefix(): String = "[" + Thread.currentThread().id + "] "

    @Test
    fun constants_allAliasMmsAndShipFlagsHold() {
        assertEquals("Mms", LogTag.TAG)
        assertEquals("Mms", LogTag.TRANSACTION)
        assertEquals("Mms", LogTag.APP)
        assertEquals("Mms", LogTag.THREAD_CACHE)
        assertEquals("Mms", LogTag.THUMBNAIL_CACHE)
        assertEquals("Mms", LogTag.PDU_CACHE)
        assertEquals("Mms", LogTag.WIDGET)
        assertEquals("Mms", LogTag.CONTACT)
        assertEquals("Mms", LogTag.STRICT_MODE_TAG)

        assertFalse(LogTag.VERBOSE)
        assertTrue(LogTag.SEVERE_WARNING)
        assertFalse(LogTag.DEBUG_SEND)
        assertFalse(LogTag.DEBUG_DUMP)
        assertFalse(LogTag.ALLOW_DUMP_IN_LOGS)
    }

    @Test
    fun debug_prefixesThreadIdAndFormats() {
        LogTag.debug("hello %s", "world")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals("Mms", logs[0].tag)
        assertEquals(threadPrefix() + "hello world", logs[0].msg)
    }

    @Test
    fun warn_prettyPrintsStringArrayArgs() {
        // A String[] arg passed as a single vararg element is rendered as
        // "[a, b, c]" by prettyArray before String.format runs.
        LogTag.warn("arr=%s", arrayOf("a", "b", "c"))

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(threadPrefix() + "arr=[a, b, c]", logs[0].msg)
    }

    @Test
    fun error_prettyPrintsEmptyStringArrayAsEmptyBrackets() {
        LogTag.error("arr=%s", arrayOf<String>())

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(threadPrefix() + "arr=[]", logs[0].msg)
    }
}

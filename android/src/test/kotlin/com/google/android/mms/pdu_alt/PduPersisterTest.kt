package com.google.android.mms.pdu_alt

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the pure static helpers of the [PduPersister] port — the parts that don't
 * require a live MMS ContentProvider:
 * - `toIsoString` / `getBytes` round-trip via ISO-8859-1 (the byte<->String
 *   bridge used throughout persist/load),
 * - `cutString` code-point-aware truncation,
 * - `convertUriToPath` for the file/http schemes.
 *
 * The provider-backed flows (`persist`/`load`/`updateParts`/…) need a real MMS
 * provider and are not unit-tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduPersisterTest {

    @Test
    fun toIsoString_getBytes_roundTrip() {
        // Bytes 0x00..0xFF map 1:1 through ISO-8859-1.
        val bytes = ByteArray(256) { it.toByte() }
        val s = PduPersister.toIsoString(bytes)
        assertEquals(256, s.length)
        val back = PduPersister.getBytes(s)
        assertEquals(bytes.toList(), back.toList())
    }

    @Test
    fun cutString_truncatesByCodePointBudget() {
        assertEquals("", PduPersister.cutString("", 5))
        assertEquals("hello", PduPersister.cutString("hello", 10))
        assertEquals("hel", PduPersister.cutString("hello", 3))
    }

    @Test
    fun cutString_doesNotSplitSurrogatePairs() {
        // A single astral code point counts as 2 UTF-16 chars; with a budget of 1
        // it can't fit, so nothing is appended.
        val emoji = "😀" // grinning face
        assertEquals("", PduPersister.cutString(emoji, 1))
        assertEquals(emoji, PduPersister.cutString(emoji, 2))
    }

    @Test
    fun convertUriToPath_fileAndHttpSchemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("/sdcard/x.mp4", PduPersister.convertUriToPath(context, Uri.parse("file:///sdcard/x.mp4")))
        assertEquals(
            "http://test.com/x.mp4",
            PduPersister.convertUriToPath(context, Uri.parse("http://test.com/x.mp4")),
        )
        // null uri → null path.
        assertEquals(null, PduPersister.convertUriToPath(context, null))
    }
}

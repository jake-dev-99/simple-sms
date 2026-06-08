package android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [Downloads.Impl] port's status-classification helpers and
 * `statusToString` mapping (the only behaviour beyond constants). Constant values
 * themselves are load-bearing provider-contract data and are exercised indirectly
 * through these helpers. Robolectric for the framework-derived `VISIBILITY_*`
 * initialisers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsTest {

    @Test
    fun statusClassifiers_matchHttpFamilies() {
        assertTrue(Downloads.Impl.isStatusInformational(100))
        assertFalse(Downloads.Impl.isStatusInformational(200))

        assertTrue(Downloads.Impl.isStatusSuccess(200))
        assertFalse(Downloads.Impl.isStatusSuccess(199))
        assertFalse(Downloads.Impl.isStatusSuccess(300))

        assertTrue(Downloads.Impl.isStatusClientError(404))
        assertFalse(Downloads.Impl.isStatusClientError(500))

        assertTrue(Downloads.Impl.isStatusServerError(500))
        assertFalse(Downloads.Impl.isStatusServerError(400))

        assertTrue(Downloads.Impl.isStatusError(492))
        assertFalse(Downloads.Impl.isStatusError(200))

        assertTrue(Downloads.Impl.isStatusCompleted(200))
        assertTrue(Downloads.Impl.isStatusCompleted(491))
        assertFalse(Downloads.Impl.isStatusCompleted(190))
    }

    @Test
    fun statusToString_mapsKnownCodes_andFallsBackToNumber() {
        assertEquals("PENDING", Downloads.Impl.statusToString(Downloads.Impl.STATUS_PENDING))
        assertEquals("SUCCESS", Downloads.Impl.statusToString(Downloads.Impl.STATUS_SUCCESS))
        assertEquals("FILE_ERROR", Downloads.Impl.statusToString(Downloads.Impl.STATUS_FILE_ERROR))
        // STATUS_FILE_ALREADY_EXISTS_ERROR and MIN_ARTIFICIAL_ERROR_STATUS share 488.
        assertEquals(
            "FILE_ALREADY_EXISTS_ERROR",
            Downloads.Impl.statusToString(Downloads.Impl.STATUS_FILE_ALREADY_EXISTS_ERROR),
        )
        // Unknown code → its numeric string.
        assertEquals("99999", Downloads.Impl.statusToString(99999))
    }

    @Test
    fun keyConstants_haveExpectedValues() {
        assertEquals(200, Downloads.Impl.STATUS_SUCCESS)
        assertEquals(492, Downloads.Impl.STATUS_FILE_ERROR)
        assertEquals(406, Downloads.Impl.STATUS_NOT_ACCEPTABLE)
        assertEquals(491, Downloads.Impl.STATUS_UNKNOWN_ERROR)
        assertEquals("status", Downloads.Impl.COLUMN_STATUS)
        assertEquals("content://downloads/my_downloads", Downloads.Impl.CONTENT_URI.toString())
    }
}

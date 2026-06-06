package com.android.mms.util

import android.content.Context
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the deterministic logic of the [DownloadManager] port: the
 * `getAutoDownloadState(prefs, roaming)` decision (auto-download is honored
 * regardless of roaming, per the vendored `alwaysAuto = true`) and the public
 * STATE_* constants. The provider read/write paths and the singleton are
 * Android-integration, verified by the native compile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test
    fun getAutoDownloadState_defaultsTrue() {
        assertTrue(DownloadManager.getAutoDownloadState(prefs(), false))
        assertTrue(DownloadManager.getAutoDownloadState(prefs(), true))
    }

    @Test
    fun getAutoDownloadState_trueIsHonoredEvenWhenRoaming() {
        prefs().edit().putBoolean("auto_download_mms", true).commit()
        assertTrue(DownloadManager.getAutoDownloadState(prefs(), true))
        assertTrue(DownloadManager.getAutoDownloadState(prefs(), false))
    }

    @Test
    fun getAutoDownloadState_falseDisablesRegardlessOfRoaming() {
        prefs().edit().putBoolean("auto_download_mms", false).commit()
        assertFalse(DownloadManager.getAutoDownloadState(prefs(), false))
        assertFalse(DownloadManager.getAutoDownloadState(prefs(), true))
    }

    @Test
    fun stateConstants_matchVendoredValues() {
        assertEquals(0x04, DownloadManager.DEFERRED_MASK)
        assertEquals(0x00, DownloadManager.STATE_UNKNOWN)
        assertEquals(0x80, DownloadManager.STATE_UNSTARTED)
        assertEquals(0x81, DownloadManager.STATE_DOWNLOADING)
        assertEquals(0x82, DownloadManager.STATE_TRANSIENT_FAILURE)
        assertEquals(0x87, DownloadManager.STATE_PERMANENT_FAILURE)
        assertEquals(0x88, DownloadManager.STATE_PRE_DOWNLOADING)
        assertEquals(0x89, DownloadManager.STATE_SKIP_RETRYING)
    }
}

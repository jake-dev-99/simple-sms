package com.klinker.android.send_message

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
 * Pins the deterministic behaviour of the Kotlin [Utils] port: the
 * SharedPreferences -> [Settings] mapping in `getDefaultSendSettings`,
 * `hasKitKat`, and `doesThreadIdExist` for an absent thread.
 *
 * The reflection-based data toggles, the telephony number lookups, and
 * `getOrCreateThreadId` are thin Android-integration wrappers (verified by the
 * native compile); meaningful unit tests would require heavy framework shadowing
 * for little marginal value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun getDefaultSendSettings_appliesPrefDefaults() {
        val s = Utils.getDefaultSendSettings(context)
        // Pref-absent defaults mirror the vendored mapping.
        assertEquals("", s.mmsc)
        assertEquals("", s.proxy)
        assertEquals("", s.port)
        assertEquals("", s.agent)
        assertEquals("", s.userProfileUrl)
        assertEquals("", s.uaProfTagName)
        assertTrue(s.group) // getBoolean("group_message", true)
        assertFalse(s.deliveryReports)
        assertFalse(s.split)
        assertFalse(s.splitCounter)
        assertFalse(s.stripUnicode)
        assertEquals("", s.signature)
        // Hard-coded by getDefaultSendSettings (not from prefs).
        assertTrue(s.sendLongAsMms)
        assertEquals(3, s.sendLongAsMmsAfter)
    }

    @Test
    fun getDefaultSendSettings_readsStoredPrefs() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("mmsc_url", "http://mmsc")
            .putString("mms_proxy", "10.0.0.1")
            .putBoolean("group_message", false)
            .putBoolean("strip_unicode", true)
            .commit()

        val s = Utils.getDefaultSendSettings(context)
        assertEquals("http://mmsc", s.mmsc)
        assertEquals("10.0.0.1", s.proxy)
        assertFalse(s.group)
        assertTrue(s.stripUnicode)
    }

    @Test
    fun hasKitKat_trueOnModernSdk() {
        assertTrue(Utils.hasKitKat())
    }

    @Test
    fun doesThreadIdExist_falseForAbsentThread() {
        assertFalse(Utils.doesThreadIdExist(context, 999_999L))
    }
}

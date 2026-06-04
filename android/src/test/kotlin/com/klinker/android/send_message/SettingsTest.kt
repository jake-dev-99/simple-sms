package com.klinker.android.send_message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the behaviour of the Kotlin [Settings] port against the vendored Klinker
 * `Settings.java` — defaults, the full/copy constructors, and the three
 * deliberately-preserved quirks (copy ctor skips `useSystemSending`; full ctor
 * forces the user-agent fields to `""`; subscriptionId null-handling).
 *
 * Robolectric because the `useSystemSending` / `setSubscriptionId` setters read
 * `Build.VERSION.SDK_INT`; at SDK 34 both gates (LOLLIPOP, LOLLIPOP_MR1) pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {

    @Test
    fun defaultConstructor_matchesVendoredDefaults() {
        val s = Settings()
        assertEquals("", s.mmsc)
        assertEquals("", s.proxy)
        assertEquals("0", s.port)
        assertEquals("", s.agent)
        assertEquals("", s.userProfileUrl)
        assertEquals("", s.uaProfTagName)
        assertTrue(s.group)
        assertFalse(s.deliveryReports)
        assertFalse(s.split)
        assertFalse(s.splitCounter)
        assertFalse(s.stripUnicode)
        assertEquals("", s.signature)
        assertEquals("", s.preText)
        assertTrue(s.sendLongAsMms)
        assertEquals(3, s.sendLongAsMmsAfter)
        assertTrue("default useSystemSending is true (Lollipop+)", s.useSystemSending)
        assertEquals(Settings.DEFAULT_SUBSCRIPTION_ID, s.getSubscriptionId())
        assertEquals(-1, Settings.DEFAULT_SUBSCRIPTION_ID)
    }

    @Test
    fun fullConstructor_forcesUserAgentFieldsToEmpty_andHonorsArgs() {
        val s = Settings(
            "mmsc", "proxy", "80", false, true, true, true, true, "sig", "pre",
            false, 7, false, 5,
        )
        assertEquals("mmsc", s.mmsc)
        assertEquals("proxy", s.proxy)
        assertEquals("80", s.port)
        assertFalse(s.group)
        assertTrue(s.deliveryReports)
        assertEquals("sig", s.signature)
        assertEquals("pre", s.preText)
        assertFalse(s.sendLongAsMms)
        assertEquals(7, s.sendLongAsMmsAfter)
        assertFalse(s.useSystemSending)
        assertEquals(5, s.getSubscriptionId())
        // Quirk: user-agent fields are always forced to "" by the full ctor.
        assertEquals("", s.agent)
        assertEquals("", s.userProfileUrl)
        assertEquals("", s.uaProfTagName)
    }

    @Test
    fun fullConstructor_nullSubscriptionId_fallsBackToDefault() {
        val s = Settings(
            "", "", "0", true, false, false, false, false, "", "", true, 3, true, null,
        )
        assertEquals(Settings.DEFAULT_SUBSCRIPTION_ID, s.getSubscriptionId())
    }

    @Test
    fun copyConstructor_copiesFields_butNotUseSystemSending() {
        val source = Settings().apply {
            mmsc = "m"
            agent = "ua"
            userProfileUrl = "url"
            uaProfTagName = "tag"
            stripUnicode = true
            setSubscriptionId(9)
            useSystemSending = true // true on the source...
        }
        val copy = Settings(source)

        assertEquals("m", copy.mmsc)
        assertEquals("ua", copy.agent)
        assertEquals("url", copy.userProfileUrl)
        assertEquals("tag", copy.uaProfTagName)
        assertTrue(copy.stripUnicode)
        assertEquals(9, copy.getSubscriptionId()) // subscriptionId copied directly
        // Quirk: the vendored copy ctor never copies useSystemSending, so a copy
        // always starts false regardless of the source.
        assertFalse("copy must NOT inherit useSystemSending", copy.useSystemSending)
    }

    @Test
    fun setSubscriptionId_nullResetsToDefault_valueIsKept() {
        val s = Settings()
        s.setSubscriptionId(42)
        assertEquals(42, s.getSubscriptionId())
        s.setSubscriptionId(null)
        assertEquals(Settings.DEFAULT_SUBSCRIPTION_ID, s.getSubscriptionId())
    }

    @Test
    fun useSystemSending_settableViaProperty() {
        val s = Settings()
        s.useSystemSending = false
        assertFalse(s.useSystemSending)
        s.useSystemSending = true
        assertTrue(s.useSystemSending)
    }
}

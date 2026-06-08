package com.android.mms.service_alt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [SystemPropertiesProxy] port. The reflective bridge resolves
 * `android.os.SystemProperties` under Robolectric, so the default-return paths
 * are exercisable for an unset key. The most important pin is the **null-`def`
 * path**: `get(context, key, null)` must return `null` (the vendored
 * `new String(def)` NPEs before the reflective invoke, caught → default), which
 * is exactly what `DownloadManager.isRoaming` relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemPropertiesProxyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val unsetKey = "ro.simplesms.nonexistent.test"

    @Test
    fun get_nullDef_returnsNull() {
        // Load-bearing: mirrors the vendored new String(null) NPE → catch → return def(null).
        assertNull(SystemPropertiesProxy.get(context, unsetKey, null))
    }

    @Test
    fun get_unsetKey_returnsEmptyString() {
        assertEquals("", SystemPropertiesProxy.get(context, unsetKey))
    }

    @Test
    fun get_unsetKeyWithDefault_returnsDefault() {
        assertEquals("fallback", SystemPropertiesProxy.get(context, unsetKey, "fallback"))
    }

    @Test
    fun getInt_unsetKey_returnsDefault() {
        assertEquals(42, SystemPropertiesProxy.getInt(context, unsetKey, 42))
    }

    @Test
    fun getLong_unsetKey_returnsDefault() {
        assertEquals(7L, SystemPropertiesProxy.getLong(context, unsetKey, 7L))
    }

    @Test
    fun getBoolean_unsetKey_returnsDefault() {
        assertEquals(true, SystemPropertiesProxy.getBoolean(context, unsetKey, true))
    }
}

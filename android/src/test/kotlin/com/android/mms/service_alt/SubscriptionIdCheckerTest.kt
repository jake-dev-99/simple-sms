package com.android.mms.service_alt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [SubscriptionIdChecker] port: the lazy `@Synchronized` singleton and a
 * crash-free `check()` probe. Needs Robolectric for the `Context`/`ContentResolver`.
 * No MMS provider is registered in the test environment, so the probe's
 * `SqliteWrapper.query` returns null and the flag stays false — this also
 * exercises the nullable-`Cursor.use` path that mirrors Java try-with-resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionIdCheckerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun getInstance_isSingleton() {
        val a = SubscriptionIdChecker.getInstance(context)
        val b = SubscriptionIdChecker.getInstance(context)
        assertSame(a, b)
    }

    @Test
    fun canUseSubscriptionId_falseWhenNoProvider() {
        // Robolectric registers no MMS provider, so the probe query returns null
        // and the flag is left at its default.
        assertFalse(SubscriptionIdChecker.getInstance(context).canUseSubscriptionId())
    }
}

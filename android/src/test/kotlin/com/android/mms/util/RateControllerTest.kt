package com.android.mms.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [RateController] port's lifecycle: `getInstance` before `init` throws,
 * `init` is idempotent (second call keeps the first instance), and
 * `isLimitSurpassed` returns false when the `Rate` provider yields no cursor.
 * The threaded confirmation handshake (`isAllowedByUser`, which registers a
 * receiver, starts an activity and blocks) is not unit-tested here.
 *
 * The singleton is process-static, so [reset] clears it reflectively before each
 * test for order-independence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RateControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun reset() {
        val field = RateController::class.java.getDeclaredField("sInstance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun getInstance_beforeInit_throws() {
        assertThrows(IllegalStateException::class.java) {
            RateController.getInstance()
        }
    }

    @Test
    fun init_isIdempotent_keepsFirstInstance() {
        RateController.init(context)
        val first = RateController.getInstance()
        RateController.init(context) // no-op: "Already initialized."
        assertSame(first, RateController.getInstance())
    }

    @Test
    fun isLimitSurpassed_falseWhenNoRateProvider() {
        RateController.init(context)
        assertFalse(RateController.getInstance().isLimitSurpassed())
    }
}

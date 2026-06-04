package com.klinker.android.send_message

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the receiver ports: the [StatusUpdatedReceiver] dispatch contract
 * (background thread, `onMessageStatusUpdated` then `updateInInternalDatabase`)
 * and the public [MmsSentReceiver] constants/hierarchy that `Transaction`
 * depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiversTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Recorder : StatusUpdatedReceiver() {
        val latch = CountDownLatch(2)
        val order = mutableListOf<String>()

        override fun onMessageStatusUpdated(context: Context, intent: Intent, receiverResultCode: Int) {
            order.add("status")
            latch.countDown()
        }

        override fun updateInInternalDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
            order.add("db")
            latch.countDown()
        }
    }

    @Test
    fun onReceive_dispatchesBothHooksInOrder() {
        val r = Recorder()
        r.onReceive(context, Intent())
        assertTrue("hooks did not run", r.latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("status", "db"), r.order)
    }

    @Test
    fun mmsSentReceiver_constants() {
        // The values Transaction.buildPdu uses to build the sent-broadcast intent.
        // (The StatusUpdatedReceiver/BroadcastReceiver hierarchy is enforced at
        // compile time, so an instance check here would be tautological.)
        assertEquals("com.klinker.android.messaging.MMS_SENT", MmsSentReceiver.MMS_SENT)
        assertEquals("content_uri", MmsSentReceiver.EXTRA_CONTENT_URI)
        assertEquals("file_path", MmsSentReceiver.EXTRA_FILE_PATH)
    }
}

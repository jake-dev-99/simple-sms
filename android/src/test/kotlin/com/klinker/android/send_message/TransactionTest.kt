package com.klinker.android.send_message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the testable logic of the [Transaction] port: the pure `splitByLength`
 * chunker (incl. the split-counter prefixing) and the `checkMMS` image
 * short-circuit. The send path itself is Android-integration (SmsManager /
 * PduPersister / ContentResolver) and is exercised by the native build +
 * downstream device testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun newTransaction() = Transaction(context, Settings())

    @Test
    fun splitByLength_chunksWithoutCounter() {
        val parts = newTransaction().splitByLength("abcdefgh", 3, false)
        assertArrayEquals(arrayOf("abc", "def", "gh"), parts)
    }

    @Test
    fun splitByLength_addsCounterPrefixWhenMultipleChunks() {
        val parts = newTransaction().splitByLength("abcdefgh", 3, true)
        assertArrayEquals(arrayOf("(1/3) abc", "(2/3) def", "(3/3) gh"), parts)
    }

    @Test
    fun splitByLength_singleChunk_noCounterPrefixEvenWhenRequested() {
        // counter only applies when there is more than one chunk.
        val parts = newTransaction().splitByLength("abc", 10, true)
        assertArrayEquals(arrayOf("abc"), parts)
    }

    @Test
    fun checkMMS_trueWhenImageAttached() {
        val msg = Message("hi", arrayOf("5555555")).apply {
            addImage(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
        }
        // images short-circuits the predicate before any SmsMessage length math.
        assertTrue(newTransaction().checkMMS(msg))
    }

    @Test
    fun checkMMS_trueWhenSubjectSet() {
        val msg = Message("hi", arrayOf("5555555"), "a subject")
        assertTrue(newTransaction().checkMMS(msg))
    }
}

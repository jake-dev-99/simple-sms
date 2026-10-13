package io.simplezen.simple_sms.mms.sending

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.mms.MMSPart
import io.simplezen.simple_sms.mms.codec.PduHeaders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    fun buildPdu_requestsADeliveryReport() {
        // The MMSC then reports when each recipient got the MMS (UNFY-110).
        newTransaction()
        val part = MMSPart().apply {
            Name = "text"
            MimeType = "text/plain"
            Data = "hi".toByteArray()
        }
        val req = Transaction.buildPdu(context, null, arrayOf("+15555550100"), null, listOf(part))
        assertEquals(PduHeaders.VALUE_YES, req.deliveryReport)
        assertEquals(PduHeaders.VALUE_NO, req.readReport)
    }

    @Test
    fun mmsSentResultPendingIntent_isMutable_soThePlatformCanAttachTheSendConf() {
        val pendingIntent = mmsSentResultPendingIntent(context, 1, Intent("test").setPackage(context.packageName))
        val flags = shadowOf(pendingIntent).flags
        assertEquals(PendingIntent.FLAG_MUTABLE, flags and PendingIntent.FLAG_MUTABLE)
        assertEquals(0, flags and PendingIntent.FLAG_IMMUTABLE)
    }

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

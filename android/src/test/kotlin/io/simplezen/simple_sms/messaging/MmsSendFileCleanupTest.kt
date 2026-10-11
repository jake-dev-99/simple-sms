package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.mms.sending.MmsSentReceiver
import io.simplezen.simple_sms.mms.sending.deleteMmsSendFile
import io.simplezen.simple_sms.mms.sending.deleteStaleMmsSendFiles
import io.simplezen.simple_sms.mms.sending.useMmsSendFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * UNFY-311: the encoded PDU an outbound MMS is sent from is deleted once its
 * SENT result arrives, whatever the result; at once when the platform never
 * took it; or by the stale-outbox sweep when its result was lost — and nothing
 * outside the cache's send files is ever deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsSendFileCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A send file the platform took, as the send path leaves it. */
    private fun sendFile(): File {
        var taken: File? = null
        useMmsSendFile(context) { file ->
            file.writeBytes(byteArrayOf(1, 2, 3))
            taken = file
        }
        return taken!!
    }

    private fun deliverMmsSent(resultCode: Int, filePath: String?) {
        val intent = statusReceiverIntent(context, SENTMMS_ACTION)
            .putExtra("messageID", 7)
            .putExtra("uri", "content://mms/7")
        if (filePath != null) intent.putExtra(MmsSentReceiver.EXTRA_FILE_PATH, filePath)
        context.sendOrderedBroadcast(intent, null, null, null, resultCode, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aSentMms_deletesItsSendFile() {
        val file = sendFile()

        deliverMmsSent(Activity.RESULT_OK, file.path)

        assertFalse(file.exists())
    }

    @Test
    fun aFailedMms_deletesItsSendFileToo() {
        val file = sendFile()

        deliverMmsSent(SmsManager.MMS_ERROR_IO_ERROR, file.path)

        assertFalse(file.exists())
    }

    @Test
    fun aResultWithoutAMessageId_stillDeletesTheSendFile() {
        // Recording the result gives up early; the file still goes.
        val file = sendFile()
        val intent = statusReceiverIntent(context, SENTMMS_ACTION)
            .putExtra(MmsSentReceiver.EXTRA_FILE_PATH, file.path)

        context.sendOrderedBroadcast(intent, null, null, null, Activity.RESULT_OK, null, null)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(file.exists())
    }

    @Test
    fun anSmsResult_deletesNothing() {
        val file = sendFile()
        val intent = statusReceiverIntent(context, SENTSMS_ACTION)
            .putExtra("messageID", 7)
            .putExtra("uri", "content://sms/7")
            .putExtra(MmsSentReceiver.EXTRA_FILE_PATH, file.path)

        context.sendOrderedBroadcast(intent, null, null, null, Activity.RESULT_OK, null, null)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(file.exists())
    }

    @Test
    fun onlyCacheSendFiles_areDeleted() {
        val outside = File(context.filesDir, "send.123.dat").apply { writeBytes(byteArrayOf(1)) }
        val otherName = File(context.cacheDir, "photo.jpg").apply { writeBytes(byteArrayOf(1)) }
        val nested = File(File(context.cacheDir, "sub").apply { mkdirs() }, "send.1.dat")
            .apply { writeBytes(byteArrayOf(1)) }

        assertFalse(deleteMmsSendFile(context, outside.path))
        assertFalse(deleteMmsSendFile(context, otherName.path))
        assertFalse(deleteMmsSendFile(context, nested.path))
        assertFalse(deleteMmsSendFile(context, null))

        assertTrue(outside.exists())
        assertTrue(otherName.exists())
        assertTrue(nested.exists())
    }

    @Test
    fun aFileThePlatformTook_isLeftForItsResult() {
        val file = sendFile()

        assertTrue(file.exists())
        assertTrue(file.parentFile == context.cacheDir)
        assertTrue(file.name.startsWith("send.") && file.name.endsWith(".dat"))
    }

    @Test
    fun aSendThatThrows_deletesItsFileAndRethrows() {
        var used: File? = null

        val thrown = assertThrows(SecurityException::class.java) {
            useMmsSendFile(context) { file ->
                file.writeBytes(byteArrayOf(1))
                used = file
                throw SecurityException("role revoked")
            }
        }

        assertEquals("role revoked", thrown.message)
        assertFalse(used!!.exists())
    }

    @Test
    fun aSendThatErrors_deletesItsFileToo() {
        var used: File? = null

        assertThrows(OutOfMemoryError::class.java) {
            useMmsSendFile(context) { file ->
                used = file
                throw OutOfMemoryError("PDU too large")
            }
        }

        assertFalse(used!!.exists())
    }

    @Test
    fun theSweepDeletesSendFilesWhoseResultWasLost() {
        val now = 100 * STALE_OUTBOX_AGE_MS
        val lost = sendFile().apply { setLastModified(now - STALE_OUTBOX_AGE_MS - 1) }
        val inFlight = sendFile().apply { setLastModified(now - 1) }
        val other = File(context.cacheDir, "photo.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - STALE_OUTBOX_AGE_MS - 1)
        }

        assertEquals(1, deleteStaleMmsSendFiles(context, STALE_OUTBOX_AGE_MS, now))

        assertFalse(lost.exists())
        assertTrue(inFlight.exists())
        assertTrue(other.exists())
    }
}

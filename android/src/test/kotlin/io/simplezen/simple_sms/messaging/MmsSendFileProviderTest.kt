package io.simplezen.simple_sms.messaging

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * MMS send-file ContentProvider contract (UNFY-182).
 *
 * `Transaction.sendMmsThroughSystem` hands `SmsManager.sendMultimediaMessage`
 * a `content://` URI that the system MMS service must open to read the PDU.
 * The vendored code built that URI against `<pkg>.MmsFileProvider` — a
 * Klinker ContentProvider deleted during the vendoring cleanup (its only
 * references were a manifest entry and a runtime string, so dead-code
 * analysis missed it). Result: MmsService resolved nothing, read no PDU, and
 * every outbound MMS failed with MMS_ERROR_IO_ERROR before any network
 * attempt.
 *
 * These tests pin the replacement contract: a `send.*.dat` PDU file in
 * `cacheDir` resolves through the androidx FileProvider this plugin's
 * manifest actually declares (`${applicationId}.provider`, cache-path mapped
 * in res/xml/file_paths.xml — the same mechanism the working MMS download
 * path uses), and the bytes are readable back through the ContentResolver
 * exactly as MmsService will read them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsSendFileProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sendPduCacheFile_resolvesThroughDeclaredProvider() {
        val sendFile = File(context.cacheDir, "send.1234567890.dat")
        sendFile.writeBytes(byteArrayOf(0x8C.toByte(), 0x80.toByte()))
        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                sendFile,
            )
            assertNotNull("send file must resolve to a content URI", uri)
            assertEquals(
                "URI must target the authority the manifest declares",
                context.packageName + ".provider",
                uri.authority,
            )
        } finally {
            sendFile.delete()
        }
    }

    @Test
    fun sendPduBytes_readBackThroughContentResolver_asMmsServiceWill() {
        // MmsService reads the PDU via ContentResolver.openInputStream on the
        // URI we pass to sendMultimediaMessage. Round-trip the exact access
        // pattern: bytes written to the cache file must come back identical
        // through the provider. (The old MmsFileProvider URI failed this at
        // the resolve step — there was no provider behind the authority.)
        val pdu = byteArrayOf(0x8C.toByte(), 0x80.toByte(), 0x98.toByte(), 0x01)
        val sendFile = File(context.cacheDir, "send.987654321.dat")
        sendFile.writeBytes(pdu)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                sendFile,
            )
            val readBack = context.contentResolver.openInputStream(uri).use {
                requireNotNull(it) { "provider returned no stream for $uri" }
                it.readBytes()
            }
            assertArrayEquals(
                "PDU bytes must round-trip through the provider",
                pdu,
                readBack,
            )
        } finally {
            sendFile.delete()
        }
    }
}

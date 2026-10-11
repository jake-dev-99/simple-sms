package io.simplezen.simple_sms.messaging

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSendCorrelationsTest {
    @Test fun failedCommitAbortsBeforeReturningToTheRadioHandoff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val actual = context.getSharedPreferences("failed_commit", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            OutboundSendCorrelations(failing).record(Uri.parse("content://sms/14"), "job")
        }
    }

    @Test fun durableIdentityDistinguishesTablesAndRetries() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/outbox/7"), "job-one")
        store.record(Uri.parse("content://mms/7"), "job-two")
        store.record(Uri.parse("content://sms/8"), "job-one")
        val recovered = OutboundSendCorrelations(context).lookup(listOf(
            "content://sms/sent/7", "content://mms/7", "content://sms/8", "content://sms/9"))
        assertEquals("job-one", recovered["content://sms/sent/7"]?.get("clientCorrelationId"))
        assertEquals("content://sms/8", recovered["content://sms/sent/7"]?.get("latestMessageUri"))
        assertEquals("job-two", recovered["content://mms/7"]?.get("clientCorrelationId"))
        assertFalse(recovered.containsKey("content://sms/9"))
    }

    @Test fun absentAndMalformedIdentitiesAreNeverGuessed() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OutboundSendCorrelations(context)
        store.record(Uri.parse("content://sms/12"), null)
        assertTrue(store.lookup(listOf("content://sms/12")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            store.record(Uri.parse("content://sms/13"), " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.record(Uri.parse("content://contacts/7"), "job")
        }
    }
}

package com.google.android.mms.util_alt

import android.net.Uri
import android.provider.Telephony.Mms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [PduCache] port: the singleton, the `UriMatcher` routing through
 * `put`/`purge`, `normalizeKey` collapsing per-box `_ID` URIs onto the canonical
 * `Mms.CONTENT_URI/<id>` key, the updating flag, and the three purge fan-outs
 * (single entry, by message-box, by thread). Needs Robolectric for `Uri` /
 * `UriMatcher`. The cache is a process-wide singleton, so each test starts from
 * a clean slate via [reset].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduCacheTest {

    private lateinit var cache: PduCache

    @Before
    fun reset() {
        cache = PduCache.getInstance()
        cache.purgeAll()
    }

    private fun entry(messageBox: Int = Mms.MESSAGE_BOX_INBOX, threadId: Long = 42L) =
        PduCacheEntry(null, messageBox, threadId)

    @Test
    fun getInstance_isSingleton() {
        assertSame(cache, PduCache.getInstance())
    }

    @Test
    fun put_then_get_roundTrips_onCanonicalIdUri() {
        val uri = Uri.parse("content://mms/123")
        val e = entry()
        assertTrue(cache.put(uri, e))
        assertSame(e, cache.get(uri))
    }

    @Test
    fun put_perBoxIdUri_normalizesKeyToCanonicalMmsId() {
        // put via an inbox/_ID uri; normalizeKey collapses it to content://mms/5,
        // so the entry is retrievable under the canonical key (and not the raw
        // per-box uri — get does not normalize).
        val inboxUri = Uri.parse("content://mms/inbox/5")
        val e = entry()
        assertTrue(cache.put(inboxUri, e))
        assertSame(e, cache.get(Uri.parse("content://mms/5")))
        assertNull(cache.get(inboxUri))
    }

    @Test
    fun setUpdating_togglesIsUpdating() {
        val uri = Uri.parse("content://mms/9")
        assertTrue(!cache.isUpdating(uri))
        cache.setUpdating(uri, true)
        assertTrue(cache.isUpdating(uri))
        // put() clears the updating flag for the same uri (its final step).
        cache.put(uri, entry())
        assertTrue(!cache.isUpdating(uri))
    }

    @Test
    fun purge_singleEntry_byCanonicalIdUri() {
        val uri = Uri.parse("content://mms/123")
        val e = entry()
        cache.put(uri, e)
        assertSame(e, cache.purge(uri))
        assertNull(cache.get(uri))
    }

    @Test
    fun purge_byMessageBox_dropsEntriesInThatBox() {
        val uri = Uri.parse("content://mms/7")
        cache.put(uri, entry(messageBox = Mms.MESSAGE_BOX_INBOX))
        // content://mms/inbox -> MMS_INBOX -> purgeByMessageBox(INBOX).
        assertNull(cache.purge(Uri.parse("content://mms/inbox")))
        assertNull(cache.get(uri))
    }

    @Test
    fun purge_byThreadId_dropsEntriesInThatThread() {
        val uri = Uri.parse("content://mms/8")
        cache.put(uri, entry(threadId = 77L))
        // content://mms-sms/conversations/77 -> MMS_CONVERSATION_ID.
        assertNull(cache.purge(Uri.parse("content://mms-sms/conversations/77")))
        assertNull(cache.get(uri))
    }

    @Test
    fun purgeAll_viaMmsAll_clearsEverything() {
        cache.put(Uri.parse("content://mms/1"), entry())
        cache.put(Uri.parse("content://mms/2"), entry())
        // content://mms -> MMS_ALL -> purgeAll().
        assertNull(cache.purge(Uri.parse("content://mms")))
        assertEquals0(cache.size())
        assertNull(cache.get(Uri.parse("content://mms/1")))
    }

    private fun assertEquals0(actual: Int) {
        org.junit.Assert.assertEquals(0, actual)
    }
}

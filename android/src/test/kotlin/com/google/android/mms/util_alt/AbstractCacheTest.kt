package com.google.android.mms.util_alt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [AbstractCache] port: put/get round-trip, miss → null, the null-key
 * guard, purge/purgeAll/size, and the 500-item cap (a put past the cap returns
 * false and is not stored). Exercised through a minimal concrete subclass since
 * [AbstractCache] is abstract; runs as plain JUnit (the `Log.v` calls are dead
 * behind `LOCAL_LOGV = false`).
 */
class AbstractCacheTest {

    private class TestCache : AbstractCache<String?, Int>()

    @Test
    fun put_then_get_roundTrips() {
        val cache = TestCache()
        assertTrue(cache.put("a", 1))
        assertEquals(1, cache.get("a"))
    }

    @Test
    fun get_missingKey_returnsNull() {
        assertNull(TestCache().get("nope"))
    }

    @Test
    fun put_nullKey_returnsFalse_andIsNotCached() {
        val cache = TestCache()
        assertFalse(cache.put(null, 9))
        assertEquals(0, cache.size())
    }

    @Test
    fun purge_removesEntry_andReturnsItsValue() {
        val cache = TestCache()
        cache.put("a", 1)
        assertEquals(1, cache.purge("a"))
        assertNull(cache.get("a"))
        // Purging an absent key returns null.
        assertNull(cache.purge("missing"))
    }

    @Test
    fun purgeAll_clearsEverything() {
        val cache = TestCache()
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(2, cache.size())
        cache.purgeAll()
        assertEquals(0, cache.size())
    }

    @Test
    fun put_respectsMaxCachedItemsCap() {
        val cache = TestCache()
        for (i in 0 until 500) {
            assertTrue("put $i should succeed under the 500 cap", cache.put("k$i", i))
        }
        assertEquals(500, cache.size())
        // The 501st put is rejected and not stored.
        assertFalse(cache.put("overflow", 999))
        assertEquals(500, cache.size())
        assertNull(cache.get("overflow"))
    }
}

package io.simplezen.simple_sms.mms.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Purpose: pin that the persister's temporary message IDs never repeat, so concurrent persists cannot claim each other's parts (UNFY-281). */
class DummyIdsTest {
    /** Purpose: a clock that stands still, as two persists in one millisecond see it, still gives different IDs. */
    @Test
    fun next_inTheSameMillisecond_strictlyIncreases() {
        val ids = DummyIds { 1_000L }

        assertEquals(listOf(1_000L, 1_001L, 1_002L), List(3) { ids.next() })
    }

    /** Purpose: an ID follows the clock once it moves past the last ID. */
    @Test
    fun next_followsTheClockWhenItMovesAhead() {
        var time = 1_000L
        val ids = DummyIds { time }
        ids.next()
        ids.next()

        time = 5_000L

        assertEquals(5_000L, ids.next())
    }

    /** Purpose: a clock that steps back never repeats or lowers an ID. */
    @Test
    fun next_whenTheClockStepsBack_keepsIncreasing() {
        var time = 5_000L
        val ids = DummyIds { time }
        val first = ids.next()

        time = 100L

        assertEquals(first + 1, ids.next())
    }

    /** Purpose: IDs taken by threads at the same instant are all different. */
    @Test
    fun next_isUniqueAcrossThreads() {
        val ids = DummyIds()
        val taken = Collections.synchronizedList(mutableListOf<Long>())
        val start = CountDownLatch(1)
        val workers = List(8) {
            thread {
                start.await()
                repeat(2_000) { taken += ids.next() }
            }
        }
        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(16_000, taken.toSet().size)
        assertTrue(taken.all { it > 0 })
    }
}

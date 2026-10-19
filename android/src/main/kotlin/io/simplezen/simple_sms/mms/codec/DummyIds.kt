package io.simplezen.simple_sms.mms.codec

import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary message IDs for the parts of one [PduPersister.persist], which saves
 * a message's parts under an ID of its own before it inserts the message row,
 * then moves them to the row's real ID (Simple Zen modification, UNFY-281).
 *
 * An ID is the time in milliseconds made strictly increasing, so two persists in
 * the same millisecond, such as a draft save and a send, never share one and
 * cannot claim each other's parts.
 */
internal class DummyIds(private val now: () -> Long = System::currentTimeMillis) {
    private val last = AtomicLong()

    /** The next ID: after every one this instance returned, and not before [now]. */
    fun next(): Long = last.updateAndGet { previous -> maxOf(now(), previous + 1) }
}

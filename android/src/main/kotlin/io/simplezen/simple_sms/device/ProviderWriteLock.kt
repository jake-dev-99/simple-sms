package io.simplezen.simple_sms.device

/**
 * Serializes this app's verified provider writes that shape a thread — deletes
 * and draft writes — across every engine in the process.
 *
 * Each engine registers its own handlers on its own background queue, so
 * without this a thread delete could run between a draft save's thread check
 * and its write (leaving a draft outside every conversation), and two engines'
 * saves for one thread could interleave. Only the default SMS app can write
 * the provider, so holding this lock leaves no other writer to race.
 */
internal object ProviderWriteLock

package com.android.mms.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [ExternalLogger] port: listener fan-out for both message and
 * exception logging, and that a removed listener stops receiving callbacks.
 *
 * [ExternalLogger] is a process-wide singleton, so each test removes the
 * listeners it adds in [tearDown] to keep the global list clean across tests.
 */
class ExternalLoggerTest {

    private class RecordingListener : ExternalLogger.LoggingListener {
        val messages = mutableListOf<Pair<String, String>>()
        val exceptions = mutableListOf<Pair<String, Throwable>>()

        override fun onLogException(tag: String, e: Throwable) {
            exceptions.add(tag to e)
        }

        override fun onLogMessage(tag: String, message: String) {
            messages.add(tag to message)
        }
    }

    private val listener = RecordingListener()

    @After
    fun tearDown() {
        ExternalLogger.removeListener(listener)
    }

    @Test
    fun registeredListener_receivesMessagesAndExceptions() {
        ExternalLogger.addListener(listener)

        ExternalLogger.logMessage("TAG", "hello")
        val boom = RuntimeException("boom")
        ExternalLogger.logException("TAG", boom)

        assertEquals(listOf("TAG" to "hello"), listener.messages)
        assertEquals(listOf<Pair<String, Throwable>>("TAG" to boom), listener.exceptions)
    }

    @Test
    fun removedListener_stopsReceiving() {
        ExternalLogger.addListener(listener)
        ExternalLogger.removeListener(listener)

        ExternalLogger.logMessage("TAG", "ignored")
        ExternalLogger.logException("TAG", RuntimeException("ignored"))

        assertEquals(0, listener.messages.size)
        assertEquals(0, listener.exceptions.size)
    }
}

package com.android.mms.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * First-party Kotlin port of the vendored `ExternalLogger` (Phase 5). A static
 * fan-out hook that lets a host app observe codec log messages/exceptions.
 * Behaviour-faithful 1:1: a `CopyOnWriteArrayList` of listeners plus
 * add/remove and the two fan-out calls.
 *
 * Ported as an `object` with `@JvmStatic` methods and a nested
 * [LoggingListener] interface so the lone live consumer (the still-Java
 * `PduParser`, which calls `ExternalLogger.logMessage(...)`) and any host
 * registering an `ExternalLogger.LoggingListener` keep resolving exactly as
 * the static Java class did.
 */
object ExternalLogger {
    private val sListener = CopyOnWriteArrayList<LoggingListener>()

    interface LoggingListener {
        fun onLogException(tag: String, e: Throwable)
        fun onLogMessage(tag: String, message: String)
    }

    @JvmStatic
    fun addListener(listener: LoggingListener) {
        sListener.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: LoggingListener) {
        sListener.remove(listener)
    }

    @JvmStatic
    fun logException(tag: String, e: Throwable) {
        for (listener in sListener) {
            listener.onLogException(tag, e)
        }
    }

    @JvmStatic
    fun logMessage(tag: String, message: String) {
        for (listener in sListener) {
            listener.onLogMessage(tag, message)
        }
    }
}

/*
 * Copyright 2014 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.logs

import android.util.Log

/**
 * First-party Kotlin port of the vendored `LogTag` (Phase 5). Behaviour-faithful
 * 1:1: the family of `"Mms"`-aliased tag constants, the ship-time debug flags,
 * and the thread-id-prefixed `debug`/`warn`/`error` formatters.
 *
 * Ported as an `object` with `@JvmStatic` methods and `const val` constants so
 * the consumers (`LogTag.TAG` in the Kotlin `DownloadManager` and the still-Java
 * `RateController`) keep resolving exactly as the static Java class did.
 */
object LogTag {
    const val TAG = "Mms"

    const val TRANSACTION = TAG
    const val APP = TAG
    const val THREAD_CACHE = TAG
    const val THUMBNAIL_CACHE = TAG
    const val PDU_CACHE = TAG
    const val WIDGET = TAG
    const val CONTACT = TAG

    /**
     * Log tag for enabling/disabling StrictMode violation log.
     * To enable: adb shell setprop log.tag.Mms:strictmode DEBUG
     */
    const val STRICT_MODE_TAG = TAG
    const val VERBOSE = false
    const val SEVERE_WARNING = true // Leave this true

    @Suppress("unused")
    private const val SHOW_SEVERE_WARNING_DIALOG = false // Set to false before ship
    const val DEBUG_SEND = false // Set to false before ship
    const val DEBUG_DUMP = false // Set to false before ship
    const val ALLOW_DUMP_IN_LOGS = false // Set to false before ship

    private fun prettyArray(array: Array<String>): String {
        if (array.isEmpty()) {
            return "[]"
        }

        val sb = StringBuilder("[")
        val len = array.size - 1
        for (i in 0 until len) {
            sb.append(array[i])
            sb.append(", ")
        }
        sb.append(array[len])
        sb.append("]")

        return sb.toString()
    }

    private fun logFormat(format: String, vararg args: Any?): String {
        val a = Array<Any?>(args.size) { args[it] }
        for (i in a.indices) {
            val arg = a[i]
            if (arg != null && arg.javaClass == Array<String>::class.java) {
                @Suppress("UNCHECKED_CAST")
                a[i] = prettyArray(arg as Array<String>)
            }
        }
        var s = String.format(format, *a)
        s = "[" + Thread.currentThread().id + "] " + s
        return s
    }

    @JvmStatic
    fun debug(format: String, vararg args: Any?) {
        Log.d(TAG, logFormat(format, *args))
    }

    @JvmStatic
    fun warn(format: String, vararg args: Any?) {
        Log.w(TAG, logFormat(format, *args))
    }

    @JvmStatic
    fun error(format: String, vararg args: Any?) {
        Log.e(TAG, logFormat(format, *args))
    }
}

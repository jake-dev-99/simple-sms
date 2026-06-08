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

package com.android.mms.util

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SqliteWrapper
import android.provider.Telephony.Mms.Rate
import android.util.Log
import com.android.mms.logs.LogTag

/**
 * First-party Kotlin port of the vendored `RateController` (Phase 5) — the MMS
 * send-rate limiter (records send times in the `Rate` provider, asks the user to
 * confirm once the hourly limit is surpassed via a broadcast round-trip).
 * Behaviour-faithful 1:1: the same lazy `init`/`getInstance` singleton, the same
 * `Rate` provider read/write, and the same broadcast/`wait`/`notifyAll`
 * confirmation handshake.
 *
 * Faithful-port notes:
 * - `init`/`getInstance` are `@JvmStatic` (consumed from Kotlin `Transaction`;
 *   `getInstance` still throws `IllegalStateException("Uninitialized.")`).
 * - **Preserved threading quirks:** the anonymous `BroadcastReceiver`
 *   `synchronized(this)` / `notifyAll()` lock the *receiver's* monitor, not the
 *   controller's — so the notify never wakes `waitForAnswer`, which instead polls
 *   `mAnswer` via a timed `wait(1000L)`. `mAnswer` is a plain (non-volatile)
 *   field written under the receiver monitor and read under the controller
 *   monitor. Carried over verbatim. `(this as java.lang.Object).wait/notifyAll`
 *   is the standard Kotlin form for the vendored `Object` monitor calls.
 * - `isLimitSurpassed` keeps the vendored double-`close()` (explicit close before
 *   `return`, plus the `finally` close) — `Cursor.close()` is idempotent.
 * - TODO(layering): the `Rate` provider read/write go directly through
 *   `SqliteWrapper`; they will route through `simple_query` in the separate
 *   layering re-route change, not in this fidelity port.
 */
class RateController private constructor(private val mContext: Context) {

    private var mAnswer = 0

    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        override fun onReceive(context: Context, intent: Intent) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "Intent received: $intent")
            }

            if (RATE_LIMIT_CONFIRMED_ACTION == intent.action) {
                synchronized(this) {
                    mAnswer = if (intent.getBooleanExtra("answer", false)) {
                        ANSWER_YES
                    } else {
                        ANSWER_NO
                    }
                    (this as java.lang.Object).notifyAll()
                }
            }
        }
    }

    fun update() {
        val values = ContentValues(1)
        values.put(Rate.SENT_TIME, System.currentTimeMillis())
        SqliteWrapper.insert(
            mContext,
            mContext.contentResolver,
            Rate.CONTENT_URI,
            values,
        )
    }

    fun isLimitSurpassed(): Boolean {
        val oneHourAgo = System.currentTimeMillis() - ONE_HOUR
        val c = SqliteWrapper.query(
            mContext,
            mContext.contentResolver,
            Rate.CONTENT_URI,
            arrayOf("COUNT(*) AS rate"),
            Rate.SENT_TIME + ">" + oneHourAgo,
            null,
            null,
        )
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    val limit = c.getInt(0) >= RATE_LIMIT
                    c.close()
                    return limit
                }
            } finally {
                c.close()
            }
        }
        return false
    }

    @Synchronized
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun isAllowedByUser(): Boolean {
        while (sMutexLock) {
            try {
                (this as java.lang.Object).wait()
            } catch (e: InterruptedException) {
                // Ignore it.
            }
        }
        sMutexLock = true

        mContext.registerReceiver(
            mBroadcastReceiver,
            IntentFilter(RATE_LIMIT_CONFIRMED_ACTION),
        )

        mAnswer = NO_ANSWER
        try {
            val intent = Intent(RATE_LIMIT_SURPASSED_ACTION)
            // Using NEW_TASK here is necessary because we're calling
            // startActivity from outside an activity.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mContext.startActivity(intent)
            return waitForAnswer() == ANSWER_YES
        } finally {
            mContext.unregisterReceiver(mBroadcastReceiver)
            sMutexLock = false
            (this as java.lang.Object).notifyAll()
        }
    }

    @Synchronized
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitForAnswer(): Int {
        var t = 0
        while ((mAnswer == NO_ANSWER) && (t < ANSWER_TIMEOUT)) {
            try {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "Waiting for answer..." + t / 1000)
                }
                (this as java.lang.Object).wait(1000L)
            } catch (e: InterruptedException) {
                // Ignore it.
            }
            t += 1000
        }
        return mAnswer
    }

    companion object {
        private const val TAG = LogTag.TAG

        @Suppress("unused")
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        private const val RATE_LIMIT = 100
        private const val ONE_HOUR = 1000L * 60 * 60

        private const val NO_ANSWER = 0
        private const val ANSWER_YES = 1
        private const val ANSWER_NO = 2

        const val ANSWER_TIMEOUT = 20000
        const val RATE_LIMIT_SURPASSED_ACTION = "com.android.mms.RATE_LIMIT_SURPASSED"
        const val RATE_LIMIT_CONFIRMED_ACTION = "com.android.mms.RATE_LIMIT_CONFIRMED"

        private var sInstance: RateController? = null
        private var sMutexLock = false

        @JvmStatic
        fun init(context: Context) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "RateController.init()")
            }

            if (sInstance != null) {
                Log.w(TAG, "Already initialized.")
                return
            }
            sInstance = RateController(context)
        }

        @JvmStatic
        fun getInstance(): RateController {
            if (sInstance == null) {
                throw IllegalStateException("Uninitialized.")
            }
            return sInstance!!
        }
    }
}

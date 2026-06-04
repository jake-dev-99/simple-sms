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

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SqliteWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.provider.Telephony.Mms
import android.util.Log
import android.widget.Toast
import com.android.internal.telephony.TelephonyProperties
import com.android.mms.logs.LogTag
import com.android.mms.service_alt.SystemPropertiesProxy
import com.google.android.mms.MmsException
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduPersister
import io.simplezen.simple_sms.R
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj

/**
 * Tracks MMS auto-download state and the per-message download status. First-party
 * Kotlin port of the vendored AOSP/Klinker `DownloadManager`; behaviour preserved.
 *
 * The `getState` read routes through `simple_query` (Rule 1: reads via
 * simple-query); the `markState` status writes (`SqliteWrapper.delete/update`)
 * stay direct, as the contract scopes the routing rule to reads.
 */
class DownloadManager private constructor(private val mContext: Context) {

    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private val mPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(mContext)
    private val mAutoDownload: Boolean = getAutoDownloadState(mContext, mPreferences)

    init {
        if (LOCAL_LOGV) {
            Log.v(TAG, "mAutoDownload ------> $mAutoDownload")
        }
    }

    fun isAuto(): Boolean = mAutoDownload

    fun markState(uri: Uri, state: Int) {
        var state = state
        // Notify user if the message has expired.
        try {
            val nInd = PduPersister.getPduPersister(mContext).load(uri) as NotificationInd
            if (nInd.expiry < System.currentTimeMillis() / 1000L &&
                (state == STATE_DOWNLOADING || state == STATE_PRE_DOWNLOADING)
            ) {
                mHandler.post {
                    Toast.makeText(mContext, R.string.service_message_not_found, Toast.LENGTH_LONG)
                        .show()
                }
                SqliteWrapper.delete(mContext, mContext.contentResolver, uri, null, null)
                return
            }
        } catch (e: MmsException) {
            Log.e(TAG, e.message, e)
            return
        }

        // Notify user if downloading permanently failed.
        if (state == STATE_PERMANENT_FAILURE) {
            mHandler.post {
                try {
                    Toast.makeText(mContext, getMessage(uri), Toast.LENGTH_LONG).show()
                } catch (e: MmsException) {
                    Log.e(TAG, e.message, e)
                }
            }
        } else if (!mAutoDownload) {
            state = state or DEFERRED_MASK
        }

        // Use the STATUS field to store the state of the downloading process
        // because it's useless for M-Notification.ind.
        val values = ContentValues(1)
        values.put(Mms.STATUS, state)
        SqliteWrapper.update(mContext, mContext.contentResolver, uri, values, null, null)
    }

    fun showErrorCodeToast(errorStr: Int) {
        mHandler.post {
            try {
                Toast.makeText(mContext, errorStr, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Caught an exception in showErrorCodeToast")
            }
        }
    }

    @Throws(MmsException::class)
    private fun getMessage(uri: Uri): String {
        val ind = PduPersister.getPduPersister(mContext).load(uri) as NotificationInd

        val v: EncodedStringValue? = ind.subject
        val subject = v?.string ?: mContext.getString(R.string.no_subject)
        val from = mContext.getString(R.string.unknown_sender)

        return mContext.getString(R.string.dl_failure_notification, subject, from)
    }

    fun getState(uri: Uri): Int {
        // Read via simple_query (Rule 1: reads route through simple-query).
        // Read the single projected column positionally (by value, not by a
        // hard-coded key) and coerce Number-or-String — matching the vendored
        // `cursor.getInt(0)`, which is column-name-agnostic and parses a
        // TEXT-typed status. Guards the OEM column-aliasing/typing footgun.
        val raw = Query(mContext)
            .query(QueryObj(contentUri = uri.toString(), projection = listOf(Mms.STATUS)))
            .firstOrNull()?.values?.firstOrNull()
        val status = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
        if (status != null) {
            return status and DEFERRED_MASK.inv()
        }
        return STATE_UNSTARTED
    }

    companion object {
        private val TAG = LogTag.TAG
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        const val DEFERRED_MASK = 0x04

        const val STATE_UNKNOWN = 0x00
        const val STATE_UNSTARTED = 0x80
        const val STATE_DOWNLOADING = 0x81
        const val STATE_TRANSIENT_FAILURE = 0x82
        const val STATE_PERMANENT_FAILURE = 0x87
        const val STATE_PRE_DOWNLOADING = 0x88

        // TransactionService will skip downloading Mms if auto-download is off
        const val STATE_SKIP_RETRYING = 0x89

        private var sInstance: DownloadManager? = null

        @JvmStatic
        fun init(context: Context) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "DownloadManager.init()")
            }

            if (sInstance != null) {
                Log.w(TAG, "Already initialized.")
            }
            sInstance = DownloadManager(context)
        }

        @JvmStatic
        fun getInstance(): DownloadManager {
            return sInstance ?: throw IllegalStateException("Uninitialized.")
        }

        @JvmStatic
        fun getAutoDownloadState(context: Context, prefs: SharedPreferences): Boolean {
            return getAutoDownloadState(prefs, isRoaming(context))
        }

        @JvmStatic
        fun getAutoDownloadState(prefs: SharedPreferences, roaming: Boolean): Boolean {
            val autoDownload = prefs.getBoolean("auto_download_mms", true)

            if (LOCAL_LOGV) {
                Log.v(TAG, "auto download without roaming -> $autoDownload")
            }

            if (autoDownload) {
                val alwaysAuto = true

                if (LOCAL_LOGV) {
                    Log.v(TAG, "auto download during roaming -> $alwaysAuto")
                }

                if (!roaming || alwaysAuto) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun isRoaming(context: Context): Boolean {
            // TODO: fix and put in Telephony layer
            val roaming = SystemPropertiesProxy.get(
                context,
                TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                null,
            )
            if (LOCAL_LOGV) {
                Log.v(TAG, "roaming ------> $roaming")
            }
            return "true" == roaming
        }
    }
}

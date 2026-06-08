package com.android.mms.service_alt

import android.content.Context
import android.database.sqlite.SQLiteException
import android.provider.Telephony
import android.util.Log
import com.google.android.mms.util_alt.SqliteWrapper

/**
 * First-party Kotlin port of the vendored `SubscriptionIdChecker` (Phase 5) — a
 * lazily-initialised singleton that probes whether the device's MMS provider
 * exposes the `Telephony.Mms.SUBSCRIPTION_ID` column (some API-22 devices don't).
 * Behaviour-faithful 1:1: the same one-shot `check()` on first `getInstance`, the
 * same `SQLiteException`-swallow-to-false, and the same `canUseSubscriptionId()`
 * result the still-Java `PduPersister` reads.
 *
 * Faithful-port notes:
 * - `getInstance(context)` is `@JvmStatic @Synchronized` so the Java
 *   `PduPersister` keeps calling `SubscriptionIdChecker.getInstance(mContext)`.
 * - Java try-with-resources → Kotlin `.use {}`; `Cursor.use` accepts a nullable
 *   receiver and skips `close()` on null, matching the Java resource semantics
 *   exactly (a null query result runs the body with the flag left false).
 * - Layering carve-out (UNFY-156): stays on `SqliteWrapper`/`ContentResolver` by
 *   design. The probe's correctness depends on the read THROWING
 *   `SQLiteException` when `Telephony.Mms.SUBSCRIPTION_ID` is absent;
 *   `simple_query`'s `ContentQuery` would swallow that into an empty result and
 *   silently flip the flag to always-true. Not a lookup read.
 */
class SubscriptionIdChecker private constructor() {
    private var mCanUseSubscriptionId = false

    // I met a device which does not have Telephony.Mms.SUBSCRIPTION_ID event if it's API Level is 22.
    private fun check(context: Context) {
        try {
            // Layering carve-out (UNFY-156): direct by design — the probe relies on
            // SQLiteException-on-missing-column, which ContentQuery would swallow.
            SqliteWrapper.query(
                context,
                context.contentResolver,
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms.SUBSCRIPTION_ID),
                null,
                null,
                null,
            ).use { c ->
                if (c != null) {
                    mCanUseSubscriptionId = true
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "SubscriptionIdChecker.check() fail")
        }
    }

    fun canUseSubscriptionId(): Boolean {
        return mCanUseSubscriptionId
    }

    companion object {
        private const val TAG = "SubscriptionIdChecker"

        private var sInstance: SubscriptionIdChecker? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): SubscriptionIdChecker {
            if (sInstance == null) {
                sInstance = SubscriptionIdChecker()
                sInstance!!.check(context)
            }
            return sInstance!!
        }
    }
}

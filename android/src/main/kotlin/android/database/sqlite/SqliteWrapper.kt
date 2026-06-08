/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.database.sqlite

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * First-party Kotlin port of the vendored `android.database.sqlite.SqliteWrapper`
 * (Phase 5, `@hide`) — the static `ContentResolver` wrapper that catches
 * `SQLiteException`s, shows a "Low Memory" toast on the low-memory signature, and
 * otherwise rethrows. Behaviour-faithful 1:1: same per-op catch/log, same
 * sentinel returns (null / -1 / false), same `checkSQLiteException` policy.
 *
 * (Sibling to `com.google.android.mms.util_alt.SqliteWrapper`; this one omits the
 * `isLowMemory(Context)` overload, exactly as the vendored original does.)
 *
 * Faithful-port notes:
 * - Modeled as a Kotlin `object` with `@JvmStatic` methods so the still-Java
 *   `RateController` and the Kotlin `DownloadManager` keep calling
 *   `SqliteWrapper.query(...)` etc. unchanged.
 * - TODO(layering): these are direct `ContentResolver` calls; they will route
 *   through `simple_query` in the separate layering re-route change (per the
 *   1:1-port-now / re-route-later decision), not in this fidelity port.
 */
object SqliteWrapper {
    private const val TAG = "SqliteWrapper"
    private const val SQLITE_EXCEPTION_DETAIL_MESSAGE = "unable to open database file"

    // FIXME: need to optimize this method.
    private fun isLowMemory(e: SQLiteException): Boolean {
        return e.message == SQLITE_EXCEPTION_DETAIL_MESSAGE
    }

    @JvmStatic
    fun checkSQLiteException(context: Context, e: SQLiteException) {
        if (isLowMemory(e)) {
            Toast.makeText(
                context,
                "Low Memory",
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            throw e
        }
    }

    @JvmStatic
    fun query(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        return try {
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Catch a SQLiteException when query: ", e)
            checkSQLiteException(context, e)
            null
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun requery(context: Context, cursor: Cursor): Boolean {
        return try {
            cursor.requery()
        } catch (e: SQLiteException) {
            Log.e(TAG, "Catch a SQLiteException when requery: ", e)
            checkSQLiteException(context, e)
            false
        }
    }

    @JvmStatic
    fun update(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        values: ContentValues?,
        where: String?,
        selectionArgs: Array<String>?,
    ): Int {
        return try {
            resolver.update(uri, values, where, selectionArgs)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Catch a SQLiteException when update: ", e)
            checkSQLiteException(context, e)
            -1
        }
    }

    @JvmStatic
    fun delete(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        where: String?,
        selectionArgs: Array<String>?,
    ): Int {
        return try {
            resolver.delete(uri, where, selectionArgs)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Catch a SQLiteException when delete: ", e)
            checkSQLiteException(context, e)
            -1
        }
    }

    @JvmStatic
    fun insert(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        values: ContentValues?,
    ): Uri? {
        return try {
            resolver.insert(uri, values)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Catch a SQLiteException when insert: ", e)
            checkSQLiteException(context, e)
            null
        }
    }
}

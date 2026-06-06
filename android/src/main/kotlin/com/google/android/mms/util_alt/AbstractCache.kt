/*
 * Copyright (C) 2015 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.mms.util_alt

import android.util.Log
import java.util.HashMap

/**
 * First-party Kotlin port of the vendored `AbstractCache` (Phase 5 · UNFY-134).
 * Behaviour-faithful 1:1: a simple bounded (max 500) hit-counting cache backed
 * by a `HashMap`. `abstract` (the vendored had no abstract methods — it's
 * abstract only to force subclassing); the still-Java `PduCache` extends it as
 * `AbstractCache<Uri, PduCacheEntry>`, so the `protected` constructor and the
 * public `put`/`get`/`purge`/`purgeAll`/`size` API are preserved for that
 * subclass and its callers.
 */
abstract class AbstractCache<K, V> protected constructor() {

    private val mCacheMap: HashMap<K, CacheEntry<V>> = HashMap()

    open fun put(key: K, value: V): Boolean {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Trying to put $key into cache.")
        }

        if (mCacheMap.size >= MAX_CACHED_ITEMS) {
            // TODO Should remove the oldest or least hit cached entry
            // and then cache the new one.
            if (LOCAL_LOGV) {
                Log.v(TAG, "Failed! size limitation reached.")
            }
            return false
        }

        if (key != null) {
            val cacheEntry = CacheEntry<V>()
            cacheEntry.value = value
            mCacheMap[key] = cacheEntry

            if (LOCAL_LOGV) {
                Log.v(TAG, "$key cached, ${mCacheMap.size} items total.")
            }
            return true
        }
        return false
    }

    open fun get(key: K): V? {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Trying to get $key from cache.")
        }

        if (key != null) {
            val cacheEntry = mCacheMap[key]
            if (cacheEntry != null) {
                cacheEntry.hit++
                if (LOCAL_LOGV) {
                    Log.v(TAG, "$key hit ${cacheEntry.hit} times.")
                }
                return cacheEntry.value
            }
        }
        return null
    }

    open fun purge(key: K): V? {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Trying to purge $key")
        }

        val v = mCacheMap.remove(key)

        if (LOCAL_LOGV) {
            Log.v(TAG, "${mCacheMap.size} items cached.")
        }

        return if (v != null) v.value else null
    }

    open fun purgeAll() {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Purging cache, ${mCacheMap.size} items dropped.")
        }
        mCacheMap.clear()
    }

    open fun size(): Int {
        return mCacheMap.size
    }

    private class CacheEntry<V> {
        var hit = 0
        var value: V? = null
    }

    companion object {
        private const val TAG = "AbstractCache"

        @Suppress("unused")
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        private const val MAX_CACHED_ITEMS = 500
    }
}

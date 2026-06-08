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

import android.content.ContentUris
import android.content.UriMatcher
import android.net.Uri
import android.provider.Telephony.Mms
import android.util.Log
import java.util.HashMap
import java.util.HashSet

/**
 * First-party Kotlin port of the vendored `PduCache` (Phase 5 · UNFY-134) — the
 * process-wide singleton cache of parsed PDUs keyed by MMS content `Uri`, on top
 * of [AbstractCache]. Behaviour-faithful 1:1: the same `UriMatcher` routing, the
 * same message-box / thread bookkeeping, the same `normalizeKey` collapse of
 * per-box `_ID` URIs onto the canonical `Mms.CONTENT_URI/<id>` key, and the same
 * `getInstance()` / `@Synchronized` monitor discipline the still-Java
 * `PduPersister` relies on (it locks on the instance and calls
 * `wait()`/`notifyAll()` around these methods).
 *
 * Faithful-port notes:
 * - `getInstance()` is `@JvmStatic` so the Java `PduPersister` keeps calling
 *   `PduCache.getInstance()`; instance methods are `@Synchronized` (lock on
 *   `this`, exactly like Java's `synchronized` instance methods).
 * - **Preserved quirk:** `removeFromMessageBoxes` reads `mThreads` (not
 *   `mMessageBoxes`), keyed by the message-box id widened to `Long`. Carried
 *   over verbatim — see the note at that method.
 * - `K` is non-null `Uri`; the only spot the vendored code could feed a null key
 *   to `super.put` is when `normalizeKey` returns null, in which case Java's
 *   `super.put(null)` returns false with no side effect. The port reproduces
 *   that result without passing null (guarded `if (finalKey != null)`).
 */
class PduCache private constructor() : AbstractCache<Uri, PduCacheEntry>() {

    private val mMessageBoxes: HashMap<Int, HashSet<Uri>> = HashMap()
    private val mThreads: HashMap<Long, HashSet<Uri>> = HashMap()
    private val mUpdating: HashSet<Uri> = HashSet()

    @Synchronized
    override fun put(uri: Uri, entry: PduCacheEntry): Boolean {
        val msgBoxId = entry.messageBox
        var msgBox = mMessageBoxes[msgBoxId]
        if (msgBox == null) {
            msgBox = HashSet()
            mMessageBoxes[msgBoxId] = msgBox
        }

        val threadId = entry.threadId
        var thread = mThreads[threadId]
        if (thread == null) {
            thread = HashSet()
            mThreads[threadId] = thread
        }

        val finalKey = normalizeKey(uri)
        // The vendored code unconditionally calls super.put(finalKey); when
        // finalKey is null that returns false with no side effect, so guarding
        // on non-null here is behaviourally identical and keeps K non-null.
        var result = false
        if (finalKey != null) {
            result = super.put(finalKey, entry)
            if (result) {
                msgBox.add(finalKey)
                thread.add(finalKey)
            }
        }
        setUpdating(uri, false)
        return result
    }

    @Synchronized
    fun setUpdating(uri: Uri, updating: Boolean) {
        if (updating) {
            mUpdating.add(uri)
        } else {
            mUpdating.remove(uri)
        }
    }

    @Synchronized
    fun isUpdating(uri: Uri): Boolean {
        return mUpdating.contains(uri)
    }

    @Synchronized
    override fun purge(uri: Uri): PduCacheEntry? {
        val match = URI_MATCHER.match(uri)
        return when (match) {
            MMS_ALL_ID -> purgeSingleEntry(uri)
            MMS_INBOX_ID,
            MMS_SENT_ID,
            MMS_DRAFTS_ID,
            MMS_OUTBOX_ID,
            -> {
                val msgId = uri.lastPathSegment
                purgeSingleEntry(Uri.withAppendedPath(Mms.CONTENT_URI, msgId))
            }
            // Implicit batch of purge, return null.
            MMS_ALL,
            MMS_CONVERSATION,
            -> {
                purgeAll()
                null
            }
            MMS_INBOX,
            MMS_SENT,
            MMS_DRAFTS,
            MMS_OUTBOX,
            -> {
                purgeByMessageBox(MATCH_TO_MSGBOX_ID_MAP[match])
                null
            }
            MMS_CONVERSATION_ID -> {
                purgeByThreadId(ContentUris.parseId(uri))
                null
            }
            else -> null
        }
    }

    private fun purgeSingleEntry(key: Uri): PduCacheEntry? {
        mUpdating.remove(key)
        val entry = super.purge(key)
        if (entry != null) {
            removeFromThreads(key, entry)
            removeFromMessageBoxes(key, entry)
            return entry
        }
        return null
    }

    @Synchronized
    override fun purgeAll() {
        super.purgeAll()

        mMessageBoxes.clear()
        mThreads.clear()
        mUpdating.clear()
    }

    /**
     * @param uri The Uri to be normalized.
     * @return Uri The normalized key of cached entry.
     */
    private fun normalizeKey(uri: Uri): Uri? {
        val match = URI_MATCHER.match(uri)
        var normalizedKey: Uri? = null

        when (match) {
            MMS_ALL_ID -> normalizedKey = uri
            MMS_INBOX_ID,
            MMS_SENT_ID,
            MMS_DRAFTS_ID,
            MMS_OUTBOX_ID,
            -> {
                val msgId = uri.lastPathSegment
                normalizedKey = Uri.withAppendedPath(Mms.CONTENT_URI, msgId)
            }
            else -> return null
        }

        if (LOCAL_LOGV) {
            Log.v(TAG, "$uri -> $normalizedKey")
        }
        return normalizedKey
    }

    private fun purgeByMessageBox(msgBoxId: Int?) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Purge cache in message box: $msgBoxId")
        }

        if (msgBoxId != null) {
            val msgBox = mMessageBoxes.remove(msgBoxId)
            if (msgBox != null) {
                for (key in msgBox) {
                    mUpdating.remove(key)
                    val entry = super.purge(key)
                    if (entry != null) {
                        removeFromThreads(key, entry)
                    }
                }
            }
        }
    }

    private fun removeFromThreads(key: Uri, entry: PduCacheEntry) {
        val thread = mThreads[entry.threadId]
        if (thread != null) {
            thread.remove(key)
        }
    }

    private fun purgeByThreadId(threadId: Long) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Purge cache in thread: $threadId")
        }

        val thread = mThreads.remove(threadId)
        if (thread != null) {
            for (key in thread) {
                mUpdating.remove(key)
                val entry = super.purge(key)
                if (entry != null) {
                    removeFromMessageBoxes(key, entry)
                }
            }
        }
    }

    private fun removeFromMessageBoxes(key: Uri, entry: PduCacheEntry) {
        // Vendored quirk preserved verbatim: this reads `mThreads` (not
        // `mMessageBoxes`), keyed by the message-box id widened to a Long, so it
        // effectively never matches a real message-box set. Carried over 1:1.
        val msgBox = mThreads[entry.messageBox.toLong()]
        if (msgBox != null) {
            msgBox.remove(key)
        }
    }

    companion object {
        private const val TAG = "PduCache"

        @Suppress("unused")
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        private const val MMS_ALL = 0
        private const val MMS_ALL_ID = 1
        private const val MMS_INBOX = 2
        private const val MMS_INBOX_ID = 3
        private const val MMS_SENT = 4
        private const val MMS_SENT_ID = 5
        private const val MMS_DRAFTS = 6
        private const val MMS_DRAFTS_ID = 7
        private const val MMS_OUTBOX = 8
        private const val MMS_OUTBOX_ID = 9
        private const val MMS_CONVERSATION = 10
        private const val MMS_CONVERSATION_ID = 11

        private val URI_MATCHER: UriMatcher
        private val MATCH_TO_MSGBOX_ID_MAP: HashMap<Int, Int>

        private var sInstance: PduCache? = null

        init {
            URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH)
            URI_MATCHER.addURI("mms", null, MMS_ALL)
            URI_MATCHER.addURI("mms", "#", MMS_ALL_ID)
            URI_MATCHER.addURI("mms", "inbox", MMS_INBOX)
            URI_MATCHER.addURI("mms", "inbox/#", MMS_INBOX_ID)
            URI_MATCHER.addURI("mms", "sent", MMS_SENT)
            URI_MATCHER.addURI("mms", "sent/#", MMS_SENT_ID)
            URI_MATCHER.addURI("mms", "drafts", MMS_DRAFTS)
            URI_MATCHER.addURI("mms", "drafts/#", MMS_DRAFTS_ID)
            URI_MATCHER.addURI("mms", "outbox", MMS_OUTBOX)
            URI_MATCHER.addURI("mms", "outbox/#", MMS_OUTBOX_ID)
            URI_MATCHER.addURI("mms-sms", "conversations", MMS_CONVERSATION)
            URI_MATCHER.addURI("mms-sms", "conversations/#", MMS_CONVERSATION_ID)

            MATCH_TO_MSGBOX_ID_MAP = HashMap()
            MATCH_TO_MSGBOX_ID_MAP[MMS_INBOX] = Mms.MESSAGE_BOX_INBOX
            MATCH_TO_MSGBOX_ID_MAP[MMS_SENT] = Mms.MESSAGE_BOX_SENT
            MATCH_TO_MSGBOX_ID_MAP[MMS_DRAFTS] = Mms.MESSAGE_BOX_DRAFTS
            MATCH_TO_MSGBOX_ID_MAP[MMS_OUTBOX] = Mms.MESSAGE_BOX_OUTBOX
        }

        @JvmStatic
        @Synchronized
        fun getInstance(): PduCache {
            if (sInstance == null) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "Constructing new PduCache instance.")
                }
                sInstance = PduCache()
            }
            return sInstance!!
        }
    }
}

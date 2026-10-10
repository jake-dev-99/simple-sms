/*
 * Copyright (C) 2015 Jacob Klinker
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

// Modified by Simple Zen: Kotlin port maintained in the first-party MMS namespace (UNFY-123).

package io.simplezen.simple_sms.mms.sending

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import io.simplezen.simple_sms.mms.storage.SqliteWrapper
import java.io.File

/**
 * Marks a sent MMS as sent in the Telephony provider and cleans up its temp
 * send-file. First-party Kotlin port of the vendored Klinker `MmsSentReceiver`;
 * behaviour preserved. The public constants are consumed by `Transaction` when
 * building the sent-broadcast intent.
 */
class MmsSentReceiver : StatusUpdatedReceiver() {

    override fun updateInInternalDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        Log.v(TAG, "MMS has finished sending, marking it as so, in the database")

        val uri = Uri.parse(intent.getStringExtra(EXTRA_CONTENT_URI))
        Log.v(TAG, uri.toString())

        val values = ContentValues(1)
        values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
        // Provider write (not a read) carried over from the vendored source.
        SqliteWrapper.update(context, context.contentResolver, uri, values, null, null)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        // getStringExtra is nullable; log via toString() (logs "null" if
        // absent, as the vendored Log.v did) and let File(...) NPE on a missing
        // path exactly as the vendored `new File(filePath)` would.
        Log.v(TAG, filePath.toString())
        File(filePath!!).delete()
    }

    override fun onMessageStatusUpdated(context: Context, intent: Intent, receiverResultCode: Int) {
    }

    companion object {
        private const val TAG = "MmsSentReceiver"

        const val MMS_SENT = "io.simplezen.simple_sms.mms.sending.MMS_SENT"
        const val EXTRA_CONTENT_URI = "content_uri"
        const val EXTRA_FILE_PATH = "file_path"
    }
}

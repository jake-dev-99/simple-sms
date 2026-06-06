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

import android.content.Context
import android.drm.DrmConvertedStatus
import android.drm.DrmManagerClient
import android.provider.Downloads
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

/**
 * First-party Kotlin port of the vendored AOSP/Klinker `DrmConvertSession`
 * (Phase 5 · UNFY-134). Behaviour-faithful 1:1: a forward-lock DRM conversion
 * session over [DrmManagerClient] (open → convert buffers → close, writing the
 * converted tail back into the file). The lone live consumer is the still-Java
 * `PduPersister`, which calls the static [open] factory and the instance
 * [convert]/[close] methods.
 *
 * `mDrmClient` is typed nullable to preserve the vendored `close`'s defensive
 * `mDrmClient != null` guard verbatim; [convert] dereferences it with `!!`,
 * matching the vendored direct `mDrmClient.convertData(...)` call (same NPE if
 * it were ever null — which the private ctor prevents).
 */
class DrmConvertSession private constructor(
    private val mDrmClient: DrmManagerClient?,
    private val mConvertSessionId: Int,
) {

    /**
     * Convert a buffer of data to protected format.
     *
     * @param inBuffer Buffer filled with data to convert.
     * @param size     The number of bytes that shall be converted.
     * @return A Buffer filled with converted data, if execution is ok, in all
     *         other case null.
     */
    fun convert(inBuffer: ByteArray?, size: Int): ByteArray? {
        var result: ByteArray? = null
        if (inBuffer != null) {
            var convertedStatus: DrmConvertedStatus? = null
            try {
                convertedStatus = if (size != inBuffer.size) {
                    val buf = ByteArray(size)
                    System.arraycopy(inBuffer, 0, buf, 0, size)
                    mDrmClient!!.convertData(mConvertSessionId, buf)
                } else {
                    mDrmClient!!.convertData(mConvertSessionId, inBuffer)
                }

                if (convertedStatus != null &&
                    convertedStatus.statusCode == DrmConvertedStatus.STATUS_OK &&
                    convertedStatus.convertedData != null
                ) {
                    result = convertedStatus.convertedData
                }
            } catch (e: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "Buffer with data to convert is illegal. Convertsession: $mConvertSessionId",
                    e,
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not convert data. Convertsession: $mConvertSessionId", e)
            }
        } else {
            throw IllegalArgumentException("Parameter inBuffer is null")
        }
        return result
    }

    /**
     * Ends a conversion session of a file.
     *
     * @param filename The filename of the converted file.
     * @return Downloads.Impl.STATUS_SUCCESS if execution is ok.
     *         Downloads.Impl.STATUS_FILE_ERROR in case converted file can not
     *         be accessed. Downloads.Impl.STATUS_NOT_ACCEPTABLE if a problem
     *         occurs when accessing drm framework.
     *         Downloads.Impl.STATUS_UNKNOWN_ERROR if a general error occurred.
     */
    fun close(filename: String?): Int {
        val convertedStatus: DrmConvertedStatus?
        var result = Downloads.Impl.STATUS_UNKNOWN_ERROR
        if (mDrmClient != null && mConvertSessionId >= 0) {
            try {
                convertedStatus = mDrmClient.closeConvertSession(mConvertSessionId)
                if (convertedStatus == null ||
                    convertedStatus.statusCode != DrmConvertedStatus.STATUS_OK ||
                    convertedStatus.convertedData == null
                ) {
                    result = Downloads.Impl.STATUS_NOT_ACCEPTABLE
                } else {
                    var rndAccessFile: RandomAccessFile? = null
                    try {
                        rndAccessFile = RandomAccessFile(filename, "rw")
                        rndAccessFile.seek(convertedStatus.offset.toLong())
                        rndAccessFile.write(convertedStatus.convertedData)
                        result = Downloads.Impl.STATUS_SUCCESS
                    } catch (e: FileNotFoundException) {
                        result = Downloads.Impl.STATUS_FILE_ERROR
                        Log.w(TAG, "File: $filename could not be found.", e)
                    } catch (e: IOException) {
                        result = Downloads.Impl.STATUS_FILE_ERROR
                        Log.w(TAG, "Could not access File: $filename .", e)
                    } catch (e: IllegalArgumentException) {
                        result = Downloads.Impl.STATUS_FILE_ERROR
                        Log.w(TAG, "Could not open file in mode: rw", e)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Access to File: $filename was denied denied by SecurityManager.", e)
                    } finally {
                        if (rndAccessFile != null) {
                            try {
                                rndAccessFile.close()
                            } catch (e: IOException) {
                                result = Downloads.Impl.STATUS_FILE_ERROR
                                Log.w(TAG, "Failed to close File:$filename.", e)
                            }
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not close convertsession. Convertsession: $mConvertSessionId", e)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "DrmConvertSession"

        /**
         * Start of converting a file.
         *
         * @param context  The context of the application running the convert session.
         * @param mimeType Mimetype of content that shall be converted.
         * @return A convert session or null in case an error occurs.
         */
        @JvmStatic
        fun open(context: Context?, mimeType: String?): DrmConvertSession? {
            var drmClient: DrmManagerClient? = null
            var convertSessionId = -1
            if (context != null && mimeType != null && mimeType != "") {
                try {
                    drmClient = DrmManagerClient(context)
                    try {
                        convertSessionId = drmClient.openConvertSession(mimeType)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Conversion of Mimetype: $mimeType is not supported.", e)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Could not access Open DrmFramework.", e)
                    }
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "DrmManagerClient instance could not be created, context is Illegal.")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "DrmManagerClient didn't initialize properly.")
                }
            }

            return if (drmClient == null || convertSessionId < 0) {
                null
            } else {
                DrmConvertSession(drmClient, convertSessionId)
            }
        }
    }
}

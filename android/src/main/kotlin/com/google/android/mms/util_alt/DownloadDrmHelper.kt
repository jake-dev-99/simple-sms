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
import android.drm.DrmManagerClient
import android.util.Log

/**
 * First-party Kotlin port of the vendored AOSP/Klinker `DownloadDrmHelper`
 * (Phase 5 · UNFY-134). Behaviour-faithful 1:1: a small bundle of DRM MIME
 * helpers over [DrmManagerClient]. The lone live consumer is the still-Java
 * `PduPersister` (`isDrmConvertNeeded`); the `object` + `@JvmStatic` + `const`
 * shape keeps `DownloadDrmHelper.isDrmConvertNeeded(...)` resolving from Java
 * exactly as the static class did.
 */
object DownloadDrmHelper {
    private const val TAG = "DownloadDrmHelper"

    /** The MIME type of special DRM files */
    const val MIMETYPE_DRM_MESSAGE = "application/vnd.oma.drm.message"

    /** The extensions of special DRM files */
    const val EXTENSION_DRM_MESSAGE = ".dm"

    const val EXTENSION_INTERNAL_FWDL = ".fl"

    /**
     * Checks if the Media Type is a DRM Media Type
     *
     * @param mimetype Media Type to check
     * @return True if the Media Type is DRM else false
     */
    @JvmStatic
    fun isDrmMimeType(context: Context?, mimetype: String?): Boolean {
        var result = false
        if (context != null) {
            try {
                val drmClient = DrmManagerClient(context)
                if (mimetype != null && mimetype.isNotEmpty()) {
                    result = drmClient.canHandle("", mimetype)
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "DrmManagerClient instance could not be created, context is Illegal.")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "DrmManagerClient didn't initialize properly.")
            }
        }
        return result
    }

    /**
     * Checks if the Media Type needs to be DRM converted
     *
     * @param mimetype Media type of the content
     * @return True if convert is needed else false
     */
    @JvmStatic
    fun isDrmConvertNeeded(mimetype: String?): Boolean {
        return MIMETYPE_DRM_MESSAGE == mimetype
    }

    /**
     * Modifies the file extension for a DRM Forward Lock file NOTE: This
     * function shouldn't be called if the file shouldn't be DRM converted
     */
    @JvmStatic
    fun modifyDrmFwLockFileExtension(filename: String?): String? {
        var name = filename
        if (name != null) {
            val extensionIndex: Int = name.lastIndexOf(".")
            if (extensionIndex != -1) {
                name = name.substring(0, extensionIndex)
            }
            name = name + EXTENSION_INTERNAL_FWDL
        }
        return name
    }

    /**
     * Gets the original mime type of DRM protected content.
     *
     * @param context        The context
     * @param path           Path to the file
     * @param containingMime The current mime type of of the file i.e. the
     *                       containing mime type
     * @return The original mime type of the file if DRM protected else the
     *         currentMime
     */
    @JvmStatic
    fun getOriginalMimeType(context: Context, path: String?, containingMime: String?): String? {
        var result = containingMime
        val drmClient = DrmManagerClient(context)
        try {
            if (drmClient.canHandle(path, null)) {
                result = drmClient.getOriginalMimeType(path)
            }
        } catch (ex: IllegalArgumentException) {
            Log.w(TAG, "Can't get original mime type since path is null or empty string.")
        } catch (ex: IllegalStateException) {
            Log.w(TAG, "DrmManagerClient didn't initialize properly.")
        }
        return result
    }
}

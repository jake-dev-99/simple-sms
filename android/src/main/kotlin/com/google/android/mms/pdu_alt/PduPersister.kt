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

package com.google.android.mms.pdu_alt

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteException
import android.drm.DrmManagerClient
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Mms.Addr
import android.provider.Telephony.Mms.Part
import android.provider.Telephony.MmsSms
import android.provider.Telephony.MmsSms.PendingMessages
import android.provider.Telephony.Threads
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import com.android.mms.service_alt.SubscriptionIdChecker
import com.google.android.mms.ContentType
import com.google.android.mms.InvalidHeaderValueException
import com.google.android.mms.MmsException
import com.google.android.mms.util_alt.DownloadDrmHelper
import com.google.android.mms.util_alt.DrmConvertSession
import com.google.android.mms.util_alt.PduCache
import com.google.android.mms.util_alt.PduCacheEntry
import com.google.android.mms.util_alt.SqliteWrapper
import com.klinker.android.send_message.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * This class is the high-level manager of PDU storage — first-party Kotlin port
 * of the vendored `PduPersister` (Phase 5). Behaviour-faithful 1:1: the same
 * header/column maps, the same `load`/`persist`/`updateHeaders`/`updateParts`/
 * `move` provider read/write flows, the same `PDU_CACHE_INSTANCE` monitor
 * discipline, and the same address/part persistence.
 *
 * Faithful-port notes:
 * - `getPduPersister` is `@JvmStatic`; the static singleton, `toIsoString`,
 *   `getBytes`, `convertUriToPath` stay accessible as before.
 * - `PDU_CACHE_INSTANCE.wait()/notifyAll()` (Java `Object` monitor calls under
 *   `synchronized(PDU_CACHE_INSTANCE)`) become `(... as java.lang.Object)` casts,
 *   the standard Kotlin form.
 * - `toIsoString`/`getBytes` take nullable params and `!!` internally, preserving
 *   the vendored NPE-on-null of `new String(null, …)` / `null.getBytes(…)` while
 *   keeping call sites clean. The unreachable `UnsupportedEncodingException`
 *   catches ("Impossible to reach here!") and the disabled-by-default `assert`
 *   in `load` are dropped.
 * - TODO(layering): every provider read/write here goes directly through
 *   `SqliteWrapper` / `ContentResolver`; they route through `simple_query` in the
 *   separate layering re-route change, not in this fidelity port.
 */
class PduPersister private constructor(private val mContext: Context) {

    private val mContentResolver: ContentResolver = mContext.contentResolver

    @Suppress("DEPRECATION")
    private val mDrmManagerClient: DrmManagerClient = DrmManagerClient(mContext)

    private val mTelephonyManager: TelephonyManager =
        mContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private fun setEncodedStringValueToHeaders(
        c: Cursor,
        columnIndex: Int,
        headers: PduHeaders,
        mapColumn: Int,
    ) {
        val s = c.getString(columnIndex)
        if ((s != null) && (s.isNotEmpty())) {
            val charsetColumnIndex = CHARSET_COLUMN_INDEX_MAP[mapColumn]!!
            val charset = c.getInt(charsetColumnIndex)
            val value = EncodedStringValue(charset, getBytes(s))
            headers.setEncodedStringValue(value, mapColumn)
        }
    }

    private fun setTextStringToHeaders(
        c: Cursor,
        columnIndex: Int,
        headers: PduHeaders,
        mapColumn: Int,
    ) {
        val s = c.getString(columnIndex)
        if (s != null) {
            headers.setTextString(getBytes(s), mapColumn)
        }
    }

    @Throws(InvalidHeaderValueException::class)
    private fun setOctetToHeaders(
        c: Cursor,
        columnIndex: Int,
        headers: PduHeaders,
        mapColumn: Int,
    ) {
        if (!c.isNull(columnIndex)) {
            val b = c.getInt(columnIndex)
            headers.setOctet(b, mapColumn)
        }
    }

    private fun setLongToHeaders(
        c: Cursor,
        columnIndex: Int,
        headers: PduHeaders,
        mapColumn: Int,
    ) {
        if (!c.isNull(columnIndex)) {
            val l = c.getLong(columnIndex)
            headers.setLongInteger(l, mapColumn)
        }
    }

    private fun getIntegerFromPartColumn(c: Cursor, columnIndex: Int): Int? {
        if (!c.isNull(columnIndex)) {
            return c.getInt(columnIndex)
        }
        return null
    }

    private fun getByteArrayFromPartColumn(c: Cursor, columnIndex: Int): ByteArray? {
        if (!c.isNull(columnIndex)) {
            return getBytes(c.getString(columnIndex))
        }
        return null
    }

    @Throws(MmsException::class)
    private fun loadParts(msgId: Long): Array<PduPart?>? {
        val c = SqliteWrapper.query(
            mContext, mContentResolver,
            Uri.parse("content://mms/$msgId/part"),
            PART_PROJECTION, null, null, null,
        )

        var parts: Array<PduPart?>? = null

        try {
            if ((c == null) || (c.count == 0)) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "loadParts($msgId): no part to load.")
                }
                return null
            }

            val partCount = c.count
            var partIdx = 0
            parts = arrayOfNulls(partCount)
            while (c.moveToNext()) {
                val part = PduPart()
                val charset = getIntegerFromPartColumn(c, PART_COLUMN_CHARSET)
                if (charset != null) {
                    part.setCharset(charset)
                }

                val contentDisposition = getByteArrayFromPartColumn(
                    c, PART_COLUMN_CONTENT_DISPOSITION,
                )
                if (contentDisposition != null) {
                    part.setContentDisposition(contentDisposition)
                }

                val contentId = getByteArrayFromPartColumn(c, PART_COLUMN_CONTENT_ID)
                if (contentId != null) {
                    part.setContentId(contentId)
                }

                val contentLocation = getByteArrayFromPartColumn(
                    c, PART_COLUMN_CONTENT_LOCATION,
                )
                if (contentLocation != null) {
                    part.setContentLocation(contentLocation)
                }

                val contentType = getByteArrayFromPartColumn(c, PART_COLUMN_CONTENT_TYPE)
                if (contentType != null) {
                    part.setContentType(contentType)
                } else {
                    throw MmsException("Content-Type must be set.")
                }

                val fileName = getByteArrayFromPartColumn(c, PART_COLUMN_FILENAME)
                if (fileName != null) {
                    part.setFilename(fileName)
                }

                val name = getByteArrayFromPartColumn(c, PART_COLUMN_NAME)
                if (name != null) {
                    part.setName(name)
                }

                // Construct a Uri for this part.
                val partId = c.getLong(PART_COLUMN_ID)
                val partURI = Uri.parse("content://mms/part/$partId")
                part.setDataUri(partURI)

                // For images/audio/video, we won't keep their data in Part
                // because their renderer accept Uri as source.
                val type = toIsoString(contentType)
                if (!ContentType.isImageType(type) &&
                    !ContentType.isAudioType(type) &&
                    !ContentType.isVideoType(type)
                ) {
                    val baos = ByteArrayOutputStream()
                    var `is`: InputStream? = null

                    // Store simple string values directly in the database instead of an
                    // external file.  This makes the text searchable and retrieval slightly
                    // faster.
                    if (ContentType.TEXT_PLAIN == type || ContentType.APP_SMIL == type ||
                        ContentType.TEXT_HTML == type
                    ) {
                        val text = c.getString(PART_COLUMN_TEXT)
                        val blob = EncodedStringValue(if (text != null) text else "")
                            .getTextString()
                        baos.write(blob, 0, blob.size)
                    } else {
                        try {
                            `is` = mContentResolver.openInputStream(partURI)

                            val buffer = ByteArray(256)
                            var len = `is`!!.read(buffer)
                            while (len >= 0) {
                                baos.write(buffer, 0, len)
                                len = `is`.read(buffer)
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to load part data", e)
                            c.close()
                            throw MmsException(e)
                        } finally {
                            if (`is` != null) {
                                try {
                                    `is`.close()
                                } catch (e: IOException) {
                                    Log.e(TAG, "Failed to close stream", e)
                                } // Ignore
                            }
                        }
                    }
                    part.setData(baos.toByteArray())
                }
                parts!![partIdx++] = part
            }
        } finally {
            c?.close()
        }

        return parts
    }

    private fun loadAddress(msgId: Long, headers: PduHeaders) {
        val c = SqliteWrapper.query(
            mContext, mContentResolver,
            Uri.parse("content://mms/$msgId/addr"),
            arrayOf(Addr.ADDRESS, Addr.CHARSET, Addr.TYPE),
            null, null, null,
        )

        if (c != null) {
            try {
                while (c.moveToNext()) {
                    val addr = c.getString(0)
                    if (!TextUtils.isEmpty(addr)) {
                        val addrType = c.getInt(2)
                        when (addrType) {
                            PduHeaders.FROM ->
                                headers.setEncodedStringValue(
                                    EncodedStringValue(c.getInt(1), getBytes(addr)),
                                    addrType,
                                )
                            PduHeaders.TO, PduHeaders.CC, PduHeaders.BCC ->
                                headers.appendEncodedStringValue(
                                    EncodedStringValue(c.getInt(1), getBytes(addr)),
                                    addrType,
                                )
                            else ->
                                Log.e(TAG, "Unknown address type: $addrType")
                        }
                    }
                }
            } finally {
                c.close()
            }
        }
    }

    /**
     * Load a PDU from storage by given Uri.
     *
     * @param uri The Uri of the PDU to be loaded.
     * @return A generic PDU object, it may be cast to dedicated PDU.
     * @throws MmsException Failed to load some fields of a PDU.
     */
    @Throws(MmsException::class)
    fun load(uri: Uri): GenericPdu? {
        var pdu: GenericPdu? = null
        var cacheEntry: PduCacheEntry? = null
        var msgBox = 0
        var threadId = DUMMY_THREAD_ID
        try {
            synchronized(PDU_CACHE_INSTANCE) {
                if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "load: $uri blocked by isUpdating()")
                    }
                    try {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (PDU_CACHE_INSTANCE as java.lang.Object).wait()
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "load: ", e)
                    }
                    cacheEntry = PDU_CACHE_INSTANCE.get(uri)
                    if (cacheEntry != null) {
                        return cacheEntry!!.pdu
                    }
                }
                // Tell the cache to indicate to other callers that this item
                // is currently being updated.
                PDU_CACHE_INSTANCE.setUpdating(uri, true)
            }

            val c = SqliteWrapper.query(
                mContext, mContentResolver, uri,
                PDU_PROJECTION, null, null, null,
            )
            val headers = PduHeaders()
            var set: Set<Map.Entry<Int, Int>>
            val msgId = ContentUris.parseId(uri)

            try {
                if ((c == null) || (c.count != 1) || !c.moveToFirst()) {
                    throw MmsException("Bad uri: $uri")
                }

                msgBox = c.getInt(PDU_COLUMN_MESSAGE_BOX)
                threadId = c.getLong(PDU_COLUMN_THREAD_ID)

                set = ENCODED_STRING_COLUMN_INDEX_MAP.entries
                for (e in set) {
                    setEncodedStringValueToHeaders(c, e.value, headers, e.key)
                }

                set = TEXT_STRING_COLUMN_INDEX_MAP.entries
                for (e in set) {
                    setTextStringToHeaders(c, e.value, headers, e.key)
                }

                set = OCTET_COLUMN_INDEX_MAP.entries
                for (e in set) {
                    setOctetToHeaders(c, e.value, headers, e.key)
                }

                set = LONG_COLUMN_INDEX_MAP.entries
                for (e in set) {
                    setLongToHeaders(c, e.value, headers, e.key)
                }
            } finally {
                c?.close()
            }

            // Check whether 'msgId' has been assigned a valid value.
            if (msgId == -1L) {
                throw MmsException("Error! ID of the message: -1.")
            }

            // Load address information of the MM.
            loadAddress(msgId, headers)

            val msgType = headers.getOctet(PduHeaders.MESSAGE_TYPE)
            val body = PduBody()

            // For PDU which type is M_retrieve.conf or Send.req, we should
            // load multiparts and put them into the body of the PDU.
            if ((msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) ||
                (msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)
            ) {
                val parts = loadParts(msgId)
                if (parts != null) {
                    val partsNum = parts.size
                    for (i in 0 until partsNum) {
                        body.addPart(parts[i])
                    }
                }
            }

            pdu = when (msgType) {
                PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND -> NotificationInd(headers)
                PduHeaders.MESSAGE_TYPE_DELIVERY_IND -> DeliveryInd(headers)
                PduHeaders.MESSAGE_TYPE_READ_ORIG_IND -> ReadOrigInd(headers)
                PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF -> RetrieveConf(headers, body)
                PduHeaders.MESSAGE_TYPE_SEND_REQ -> SendReq(headers, body)
                PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND -> AcknowledgeInd(headers)
                PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND -> NotifyRespInd(headers)
                PduHeaders.MESSAGE_TYPE_READ_REC_IND -> ReadRecInd(headers)
                PduHeaders.MESSAGE_TYPE_SEND_CONF,
                PduHeaders.MESSAGE_TYPE_FORWARD_REQ,
                PduHeaders.MESSAGE_TYPE_FORWARD_CONF,
                PduHeaders.MESSAGE_TYPE_MBOX_STORE_REQ,
                PduHeaders.MESSAGE_TYPE_MBOX_STORE_CONF,
                PduHeaders.MESSAGE_TYPE_MBOX_VIEW_REQ,
                PduHeaders.MESSAGE_TYPE_MBOX_VIEW_CONF,
                PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_REQ,
                PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_CONF,
                PduHeaders.MESSAGE_TYPE_MBOX_DELETE_REQ,
                PduHeaders.MESSAGE_TYPE_MBOX_DELETE_CONF,
                PduHeaders.MESSAGE_TYPE_MBOX_DESCR,
                PduHeaders.MESSAGE_TYPE_DELETE_REQ,
                PduHeaders.MESSAGE_TYPE_DELETE_CONF,
                PduHeaders.MESSAGE_TYPE_CANCEL_REQ,
                PduHeaders.MESSAGE_TYPE_CANCEL_CONF,
                ->
                    throw MmsException(
                        "Unsupported PDU type: " + Integer.toHexString(msgType),
                    )

                else ->
                    throw MmsException(
                        "Unrecognized PDU type: " + Integer.toHexString(msgType),
                    )
            }
        } finally {
            synchronized(PDU_CACHE_INSTANCE) {
                if (pdu != null) {
                    // Update the cache entry with the real info
                    cacheEntry = PduCacheEntry(pdu, msgBox, threadId)
                    PDU_CACHE_INSTANCE.put(uri, cacheEntry)
                }
                PDU_CACHE_INSTANCE.setUpdating(uri, false)
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (PDU_CACHE_INSTANCE as java.lang.Object).notifyAll() // tell anybody waiting on this entry to go ahead
            }
        }
        return pdu
    }

    private fun persistAddress(msgId: Long, type: Int, array: Array<EncodedStringValue>) {
        val values = ContentValues(3)

        for (addr in array) {
            values.clear() // Clear all values first.
            values.put(Addr.ADDRESS, toIsoString(addr.getTextString()))
            values.put(Addr.CHARSET, addr.characterSet)
            values.put(Addr.TYPE, type)

            val uri = Uri.parse("content://mms/$msgId/addr")
            SqliteWrapper.insert(mContext, mContentResolver, uri, values)
        }
    }

    @Throws(MmsException::class)
    fun persistPart(part: PduPart, msgId: Long, preOpenedFiles: HashMap<Uri, InputStream>?): Uri {
        val uri = Uri.parse("content://mms/$msgId/part")
        val values = ContentValues(8)

        val charset = part.charset
        if (charset != 0) {
            values.put(Part.CHARSET, charset)
        }

        var contentType = getPartContentType(part)
        if (contentType != null) {
            // There is no "image/jpg" in Android (and it's an invalid mimetype).
            // Change it to "image/jpeg"
            if (ContentType.IMAGE_JPG == contentType) {
                contentType = ContentType.IMAGE_JPEG
            }

            values.put(Part.CONTENT_TYPE, contentType)
            // To ensure the SMIL part is always the first part.
            if (ContentType.APP_SMIL == contentType) {
                values.put(Part.SEQ, -1)
            }
        } else {
            throw MmsException("MIME type of the part must be set.")
        }

        if (part.filename != null) {
            val fileName = String(part.filename!!)
            values.put(Part.FILENAME, fileName)
        }

        if (part.name != null) {
            val name = String(part.name!!)
            values.put(Part.NAME, name)
        }

        var value: Any?
        if (part.contentDisposition != null) {
            value = toIsoString(part.contentDisposition)
            values.put(Part.CONTENT_DISPOSITION, value as String)
        }

        if (part.contentId != null) {
            value = toIsoString(part.contentId)
            values.put(Part.CONTENT_ID, value as String)
        }

        if (part.contentLocation != null) {
            value = toIsoString(part.contentLocation)
            values.put(Part.CONTENT_LOCATION, value as String)
        }

        val res = SqliteWrapper.insert(mContext, mContentResolver, uri, values)
            ?: throw MmsException("Failed to persist part, return null.")

        persistData(part, res, contentType, preOpenedFiles)
        // After successfully store the data, we should update
        // the dataUri of the part.
        part.setDataUri(res)

        return res
    }

    /**
     * Save data of the part into storage. The source data may be given
     * by a byte[] or a Uri. If it's a byte[], directly save it
     * into storage, otherwise load source data from the dataUri and then
     * save it. If the data is an image, we may scale down it according
     * to user preference.
     *
     * @param part The PDU part which contains data to be saved.
     * @param uri The URI of the part.
     * @param contentType The MIME type of the part.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @throws MmsException Cannot find source data or error occurred
     *         while saving the data.
     */
    @Throws(MmsException::class)
    private fun persistData(
        part: PduPart,
        uri: Uri,
        contentType: String?,
        preOpenedFiles: HashMap<Uri, InputStream>?,
    ) {
        var os: OutputStream? = null
        var `is`: InputStream? = null
        var drmConvertSession: DrmConvertSession? = null
        var dataUri: Uri?
        var path: String? = null

        try {
            var data = part.data
            if (ContentType.TEXT_PLAIN == contentType ||
                ContentType.APP_SMIL == contentType ||
                ContentType.TEXT_HTML == contentType
            ) {
                val cv = ContentValues()
                if (data == null) {
                    data = "".toByteArray(charset(CharacterSets.DEFAULT_CHARSET_NAME))
                }
                val dataText = EncodedStringValue(data).string
                cv.put(Part.TEXT, dataText)
                if (mContentResolver.update(uri, cv, null, null) != 1) {
                    if (data.size > MAX_TEXT_BODY_SIZE) {
                        val cv2 = ContentValues()
                        cv2.put(Part.TEXT, cutString(dataText, MAX_TEXT_BODY_SIZE))
                        if (mContentResolver.update(uri, cv2, null, null) != 1) {
                            throw MmsException("unable to update " + uri.toString())
                        }
                    } else {
                        throw MmsException("unable to update " + uri.toString())
                    }
                }
            } else {
                val isDrm = DownloadDrmHelper.isDrmConvertNeeded(contentType)
                if (isDrm) {
                    if (uri != null) {
                        try {
                            path = convertUriToPath(mContext, uri)
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "drm uri: $uri path: $path")
                            }
                            val f = File(path)
                            val len = f.length()
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "drm path: $path len: $len")
                            }
                            if (len > 0) {
                                // we're not going to re-persist and re-encrypt an already
                                // converted drm file
                                return
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Can't get file info for: " + part.dataUri, e)
                        }
                    }
                    // We haven't converted the file yet, start the conversion
                    drmConvertSession = DrmConvertSession.open(mContext, contentType)
                    if (drmConvertSession == null) {
                        throw MmsException(
                            "Mimetype $contentType can not be converted.",
                        )
                    }
                }
                // uri can look like:
                // content://mms/part/98
                os = mContentResolver.openOutputStream(uri)
                if (data == null) {
                    dataUri = part.dataUri
                    if ((dataUri == null) || (dataUri == uri)) {
                        Log.w(TAG, "Can't find data for this part.")
                        return
                    }
                    // dataUri can look like:
                    // content://com.google.android.gallery3d.provider/picasa/item/5720646660183715586
                    if (preOpenedFiles != null && preOpenedFiles.containsKey(dataUri)) {
                        `is` = preOpenedFiles[dataUri]
                    }
                    if (`is` == null) {
                        `is` = mContentResolver.openInputStream(dataUri)
                    }

                    if (LOCAL_LOGV) {
                        Log.v(TAG, "Saving data to: $uri")
                    }

                    val buffer = ByteArray(8192)
                    var len = `is`!!.read(buffer)
                    while (len != -1) {
                        if (!isDrm) {
                            os!!.write(buffer, 0, len)
                        } else {
                            val convertedData = drmConvertSession!!.convert(buffer, len)
                            if (convertedData != null) {
                                os!!.write(convertedData, 0, convertedData.size)
                            } else {
                                throw MmsException("Error converting drm data.")
                            }
                        }
                        len = `is`.read(buffer)
                    }
                } else {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "Saving data to: $uri")
                    }
                    if (!isDrm) {
                        os!!.write(data)
                    } else {
                        dataUri = uri
                        val convertedData = drmConvertSession!!.convert(data, data.size)
                        if (convertedData != null) {
                            os!!.write(convertedData, 0, convertedData.size)
                        } else {
                            throw MmsException("Error converting drm data.")
                        }
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Failed to open Input/Output stream.", e)
            throw MmsException(e)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read/write data.", e)
            throw MmsException(e)
        } finally {
            if (os != null) {
                try {
                    os.close()
                } catch (e: IOException) {
                    Log.e(TAG, "IOException while closing: $os", e)
                } // Ignore
            }
            if (`is` != null) {
                try {
                    `is`.close()
                } catch (e: IOException) {
                    Log.e(TAG, "IOException while closing: ${`is`}", e)
                } // Ignore
            }
            if (drmConvertSession != null) {
                drmConvertSession.close(path)

                // Reset the permissions on the encrypted part file so everyone has only read
                // permission.
                val f = File(path)
                val values = ContentValues(0)
                SqliteWrapper.update(
                    mContext, mContentResolver,
                    Uri.parse("content://mms/resetFilePerm/" + f.name),
                    values, null, null,
                )
            }
        }
    }

    private fun updateAddress(msgId: Long, type: Int, array: Array<EncodedStringValue>) {
        // Delete old address information and then insert new ones.
        SqliteWrapper.delete(
            mContext, mContentResolver,
            Uri.parse("content://mms/$msgId/addr"),
            Addr.TYPE + "=" + type, null,
        )

        persistAddress(msgId, type, array)
    }

    /**
     * Update headers of a SendReq.
     *
     * @param uri The PDU which need to be updated.
     * @param sendReq New headers.
     * @throws MmsException Bad URI or updating failed.
     */
    fun updateHeaders(uri: Uri, sendReq: SendReq) {
        synchronized(PDU_CACHE_INSTANCE) {
            // If the cache item is getting updated, wait until it's done updating before
            // purging it.
            if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "updateHeaders: $uri blocked by isUpdating()")
                }
                try {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (PDU_CACHE_INSTANCE as java.lang.Object).wait()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "updateHeaders: ", e)
                }
            }
        }
        PDU_CACHE_INSTANCE.purge(uri)

        val values = ContentValues(10)
        val contentType = sendReq.contentType
        if (contentType != null) {
            values.put(Mms.CONTENT_TYPE, toIsoString(contentType))
        }

        val date = sendReq.date
        if (date != -1L) {
            values.put(Mms.DATE, date)
        }

        val deliveryReport = sendReq.deliveryReport
        if (deliveryReport != 0) {
            values.put(Mms.DELIVERY_REPORT, deliveryReport)
        }

        val expiry = sendReq.expiry
        if (expiry != -1L) {
            values.put(Mms.EXPIRY, expiry)
        }

        val msgClass = sendReq.messageClass
        if (msgClass != null) {
            values.put(Mms.MESSAGE_CLASS, toIsoString(msgClass))
        }

        val priority = sendReq.priority
        if (priority != 0) {
            values.put(Mms.PRIORITY, priority)
        }

        val readReport = sendReq.readReport
        if (readReport != 0) {
            values.put(Mms.READ_REPORT, readReport)
        }

        val transId = sendReq.transactionId
        if (transId != null) {
            values.put(Mms.TRANSACTION_ID, toIsoString(transId))
        }

        val subject = sendReq.subject
        if (subject != null) {
            values.put(Mms.SUBJECT, toIsoString(subject.getTextString()))
            values.put(Mms.SUBJECT_CHARSET, subject.characterSet)
        } else {
            values.put(Mms.SUBJECT, "")
        }

        val messageSize = sendReq.messageSize
        if (messageSize > 0) {
            values.put(Mms.MESSAGE_SIZE, messageSize)
        }

        val headers = sendReq.getPduHeaders()!!
        val recipients = HashSet<String>()
        for (addrType in ADDRESS_FIELDS) {
            var array: Array<EncodedStringValue>? = null
            if (addrType == PduHeaders.FROM) {
                val v = headers.getEncodedStringValue(addrType)
                if (v != null) {
                    array = arrayOf(v)
                }
            } else {
                array = headers.getEncodedStringValues(addrType)
            }

            if (array != null) {
                val msgId = ContentUris.parseId(uri)
                updateAddress(msgId, addrType, array)
                if (addrType == PduHeaders.TO) {
                    for (v in array) {
                        if (v != null) {
                            recipients.add(v.string)
                        }
                    }
                }
            }
        }
        if (recipients.isNotEmpty()) {
            val threadId = Threads.getOrCreateThreadId(mContext, recipients)
            values.put(Mms.THREAD_ID, threadId)
        }

        SqliteWrapper.update(mContext, mContentResolver, uri, values, null, null)
    }

    @Throws(MmsException::class)
    private fun updatePart(uri: Uri, part: PduPart, preOpenedFiles: HashMap<Uri, InputStream>?) {
        val values = ContentValues(7)

        val charset = part.charset
        if (charset != 0) {
            values.put(Part.CHARSET, charset)
        }

        val contentType: String
        if (part.contentType != null) {
            contentType = toIsoString(part.contentType)
            values.put(Part.CONTENT_TYPE, contentType)
        } else {
            throw MmsException("MIME type of the part must be set.")
        }

        if (part.filename != null) {
            val fileName = String(part.filename!!)
            values.put(Part.FILENAME, fileName)
        }

        if (part.name != null) {
            val name = String(part.name!!)
            values.put(Part.NAME, name)
        }

        var value: Any?
        if (part.contentDisposition != null) {
            value = toIsoString(part.contentDisposition)
            values.put(Part.CONTENT_DISPOSITION, value as String)
        }

        if (part.contentId != null) {
            value = toIsoString(part.contentId)
            values.put(Part.CONTENT_ID, value as String)
        }

        if (part.contentLocation != null) {
            value = toIsoString(part.contentLocation)
            values.put(Part.CONTENT_LOCATION, value as String)
        }

        SqliteWrapper.update(mContext, mContentResolver, uri, values, null, null)

        // Only update the data when:
        // 1. New binary data supplied or
        // 2. The Uri of the part is different from the current one.
        if ((part.data != null) || (uri != part.dataUri)) {
            persistData(part, uri, contentType, preOpenedFiles)
        }
    }

    /**
     * Update all parts of a PDU.
     *
     * @param uri The PDU which need to be updated.
     * @param body New message body of the PDU.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @throws MmsException Bad URI or updating failed.
     */
    @Throws(MmsException::class)
    fun updateParts(uri: Uri, body: PduBody, preOpenedFiles: HashMap<Uri, InputStream>?) {
        try {
            var cacheEntry: PduCacheEntry?
            synchronized(PDU_CACHE_INSTANCE) {
                if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "updateParts: $uri blocked by isUpdating()")
                    }
                    try {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (PDU_CACHE_INSTANCE as java.lang.Object).wait()
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "updateParts: ", e)
                    }
                    cacheEntry = PDU_CACHE_INSTANCE.get(uri)
                    if (cacheEntry != null) {
                        (cacheEntry!!.pdu as MultimediaMessagePdu).setBody(body)
                    }
                }
                // Tell the cache to indicate to other callers that this item
                // is currently being updated.
                PDU_CACHE_INSTANCE.setUpdating(uri, true)
            }

            val toBeCreated = ArrayList<PduPart>()
            val toBeUpdated = HashMap<Uri, PduPart>()

            val partsNum = body.partsNum
            val filter = StringBuilder().append('(')
            for (i in 0 until partsNum) {
                val part = body.getPart(i)
                val partUri = part.dataUri
                if ((partUri == null) || !partUri.authority!!.startsWith("mms")) {
                    toBeCreated.add(part)
                } else {
                    toBeUpdated[partUri] = part

                    // Don't use 'i > 0' to determine whether we should append
                    // 'AND' since 'i = 0' may be skipped in another branch.
                    if (filter.length > 1) {
                        filter.append(" AND ")
                    }

                    filter.append(Part._ID)
                    filter.append("!=")
                    DatabaseUtils.appendEscapedSQLString(filter, partUri.lastPathSegment)
                }
            }
            filter.append(')')

            val msgId = ContentUris.parseId(uri)

            // Remove the parts which doesn't exist anymore.
            SqliteWrapper.delete(
                mContext, mContentResolver,
                Uri.parse(Mms.CONTENT_URI.toString() + "/" + msgId + "/part"),
                if (filter.length > 2) filter.toString() else null, null,
            )

            // Create new parts which didn't exist before.
            for (part in toBeCreated) {
                persistPart(part, msgId, preOpenedFiles)
            }

            // Update the modified parts.
            for (e in toBeUpdated.entries) {
                updatePart(e.key, e.value, preOpenedFiles)
            }
        } finally {
            synchronized(PDU_CACHE_INSTANCE) {
                PDU_CACHE_INSTANCE.setUpdating(uri, false)
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (PDU_CACHE_INSTANCE as java.lang.Object).notifyAll()
            }
        }
    }

    fun getContentLocationFromPduHeader(pdu: GenericPdu): String {
        return toIsoString(pdu.getPduHeaders()!!.getTextString(PduHeaders.CONTENT_LOCATION))
    }

    /**
     * Persist a PDU object to specific location in the storage.
     *
     * @param pdu The PDU object to be stored.
     * @param uri Where to store the given PDU object.
     * @param createThreadId if true, this function may create a thread id for the recipients
     * @param groupMmsEnabled if true, all of the recipients addressed in the PDU will be used
     *  to create the associated thread. When false, only the sender will be used in finding or
     *  creating the appropriate thread or conversation.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @return A Uri which can be used to access the stored PDU.
     */
    @Throws(MmsException::class)
    fun persist(
        pdu: GenericPdu,
        uri: Uri?,
        createThreadId: Boolean,
        groupMmsEnabled: Boolean,
        preOpenedFiles: HashMap<Uri, InputStream>?,
        subscriptionId: Int,
    ): Uri {
        if (uri == null) {
            throw MmsException("Uri may not be null.")
        }
        var msgId: Long = -1
        try {
            msgId = ContentUris.parseId(uri)
        } catch (e: NumberFormatException) {
            // the uri ends with "inbox" or something else like that
        }
        val existingUri = msgId != -1L

        if (!existingUri && MESSAGE_BOX_MAP[uri] == null) {
            throw MmsException(
                "Bad destination, must be one of " +
                    "content://mms/inbox, content://mms/sent, " +
                    "content://mms/drafts, content://mms/outbox, " +
                    "content://mms/temp.",
            )
        }
        synchronized(PDU_CACHE_INSTANCE) {
            // If the cache item is getting updated, wait until it's done updating before
            // purging it.
            if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "persist: $uri blocked by isUpdating()")
                }
                try {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (PDU_CACHE_INSTANCE as java.lang.Object).wait()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "persist1: ", e)
                }
            }
        }
        PDU_CACHE_INSTANCE.purge(uri)

        val header = pdu.getPduHeaders()!!
        var body: PduBody? = null
        var values = ContentValues()
        var set: Set<Map.Entry<Int, String>>

        set = ENCODED_STRING_COLUMN_NAME_MAP.entries
        for (e in set) {
            val field = e.key
            val encodedString = header.getEncodedStringValue(field)
            if (encodedString != null) {
                val charsetColumn = CHARSET_COLUMN_NAME_MAP[field]
                values.put(e.value, toIsoString(encodedString.getTextString()))
                values.put(charsetColumn, encodedString.characterSet)
            }
        }

        set = TEXT_STRING_COLUMN_NAME_MAP.entries
        for (e in set) {
            val text = header.getTextString(e.key)
            if (text != null) {
                values.put(e.value, toIsoString(text))
            }
        }

        set = OCTET_COLUMN_NAME_MAP.entries
        for (e in set) {
            val b = header.getOctet(e.key)
            if (b != 0) {
                values.put(e.value, b)
            }
        }

        set = LONG_COLUMN_NAME_MAP.entries
        for (e in set) {
            val l = header.getLongInteger(e.key)
            if (l != -1L) {
                values.put(e.value, l)
            }
        }

        val addressMap = HashMap<Int, Array<EncodedStringValue>?>(ADDRESS_FIELDS.size)
        // Save address information.
        for (addrType in ADDRESS_FIELDS) {
            var array: Array<EncodedStringValue>? = null
            if (addrType == PduHeaders.FROM) {
                val v = header.getEncodedStringValue(addrType)
                if (v != null) {
                    array = arrayOf(v)
                }
            } else {
                array = header.getEncodedStringValues(addrType)
            }
            addressMap[addrType] = array
        }

        val recipients = HashSet<String>()
        val msgType = pdu.messageType
        // Here we only allocate thread ID for M-Notification.ind,
        // M-Retrieve.conf and M-Send.req.
        // Some of other PDU types may be allocated a thread ID outside
        // this scope.
        if ((msgType == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) ||
            (msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) ||
            (msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)
        ) {
            when (msgType) {
                PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND,
                PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF,
                -> {
                    loadRecipients(PduHeaders.FROM, recipients, addressMap, false)

                    // For received messages when group MMS is enabled, we want to associate this
                    // message with the thread composed of all the recipients -- all but our own
                    // number, that is. This includes the person who sent the
                    // message or the FROM field (above) in addition to the other people the message
                    // was addressed to or the TO field. Our own number is in that TO field and
                    // we have to ignore it in loadRecipients.
                    if (groupMmsEnabled) {
                        loadRecipients(PduHeaders.TO, recipients, addressMap, true)

                        // Also load any numbers in the CC field to address group messaging
                        // compatibility issues with devices that place numbers in this field
                        // for group messages.
                        loadRecipients(PduHeaders.CC, recipients, addressMap, true)
                    }
                }
                PduHeaders.MESSAGE_TYPE_SEND_REQ ->
                    loadRecipients(PduHeaders.TO, recipients, addressMap, false)
            }
            var threadId = DUMMY_THREAD_ID
            if (createThreadId && recipients.isNotEmpty()) {
                // Given all the recipients associated with this message, find (or create) the
                // correct thread.
                threadId = Threads.getOrCreateThreadId(mContext, recipients)
            }
            values.put(Mms.THREAD_ID, threadId)
        }

        // Save parts first to avoid inconsistent message is loaded
        // while saving the parts.
        val dummyId = System.currentTimeMillis() // Dummy ID of the msg.

        // Figure out if this PDU is a text-only message
        var textOnly = true

        // Sum up the total message size
        var messageSize = 0

        // Get body if the PDU is a RetrieveConf or SendReq.
        if (pdu is MultimediaMessagePdu) {
            body = pdu.body
            // Start saving parts if necessary.
            if (body != null) {
                val partsNum = body.partsNum
                if (partsNum > 2) {
                    // For a text-only message there will be two parts: 1-the SMIL, 2-the text.
                    // Down a few lines below we're checking to make sure we've only got SMIL or
                    // text. We also have to check then we don't have more than two parts.
                    // Otherwise, a slideshow with two text slides would be marked as textOnly.
                    textOnly = false
                }
                for (i in 0 until partsNum) {
                    val part = body.getPart(i)
                    messageSize += part.dataLength
                    persistPart(part, dummyId, preOpenedFiles)

                    // If we've got anything besides text/plain or SMIL part, then we've got
                    // an mms message with some other type of attachment.
                    val contentType = getPartContentType(part)
                    if (contentType != null && ContentType.APP_SMIL != contentType &&
                        ContentType.TEXT_PLAIN != contentType
                    ) {
                        textOnly = false
                    }
                }
            }
        }
        // Record whether this mms message is a simple plain text or not. This is a hint for the
        // UI.
        // values.put(Mms.TEXT_ONLY, textOnly ? 1 : 0);
        // The message-size might already have been inserted when parsing the
        // PDU header. If not, then we insert the message size as well.
        if (values.getAsInteger(Mms.MESSAGE_SIZE) == null) {
            values.put(Mms.MESSAGE_SIZE, messageSize)
        }

        // insert only if it is a valid subscription id.
        if (Settings.DEFAULT_SUBSCRIPTION_ID != subscriptionId &&
            SubscriptionIdChecker.getInstance(mContext).canUseSubscriptionId()
        ) {
            values.put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
        }

        var res: Uri?
        if (existingUri) {
            res = uri
            SqliteWrapper.update(mContext, mContentResolver, res, values, null, null)
        } else {
            res = SqliteWrapper.insert(mContext, mContentResolver, uri, values)
            if (res == null) {
                throw MmsException("persist() failed: return null.")
            }
            // Get the real ID of the PDU and update all parts which were
            // saved with the dummy ID.
            msgId = ContentUris.parseId(res)
        }

        values = ContentValues(1)
        values.put(Part.MSG_ID, msgId)
        SqliteWrapper.update(
            mContext, mContentResolver,
            Uri.parse("content://mms/$dummyId/part"),
            values, null, null,
        )
        // We should return the longest URI of the persisted PDU, for
        // example, if input URI is "content://mms/inbox" and the _ID of
        // persisted PDU is '8', we should return "content://mms/inbox/8"
        // instead of "content://mms/8".
        // FIXME: Should the MmsProvider be responsible for this???
        if (!existingUri) {
            res = Uri.parse("$uri/$msgId")
        }

        // Save address information.
        for (addrType in ADDRESS_FIELDS) {
            val array = addressMap[addrType]
            if (array != null) {
                persistAddress(msgId, addrType, array)
            }
        }

        return res
    }

    /**
     * For a given address type, extract the recipients from the headers.
     *
     * @param addressType can be PduHeaders.FROM, PduHeaders.TO or PduHeaders.CC
     * @param recipients a HashSet that is loaded with the recipients from the FROM, TO or CC headers
     * @param addressMap a HashMap of the addresses from the ADDRESS_FIELDS header
     * @param excludeMyNumber if true, the number of this phone will be excluded from recipients
     */
    @Suppress("DEPRECATION")
    private fun loadRecipients(
        addressType: Int,
        recipients: HashSet<String>,
        addressMap: HashMap<Int, Array<EncodedStringValue>?>,
        excludeMyNumber: Boolean,
    ) {
        val array = addressMap[addressType] ?: return
        // If the TO recipients is only a single address, then we can skip loadRecipients when
        // we're excluding our own number because we know that address is our own.
        // NOTE: this is not true for project fi users. To fix it, we'll add the final check for the
        //       TO type. project fi will use the cc field instead.
        if (excludeMyNumber && array.size == 1 && addressType == PduHeaders.TO) {
            return
        }
        val myNumber = if (excludeMyNumber) mTelephonyManager.line1Number else null
        for (v in array) {
            if (v != null) {
                val number = v.string
                if ((myNumber == null || !PhoneNumberUtils.compare(number, myNumber)) &&
                    !recipients.contains(number)
                ) {
                    // Only add numbers which aren't my own number.
                    recipients.add(number)
                }
            }
        }
    }

    /**
     * Move a PDU object from one location to another.
     *
     * @param from Specify the PDU object to be moved.
     * @param to The destination location, should be one of the following:
     *        "content://mms/inbox", "content://mms/sent",
     *        "content://mms/drafts", "content://mms/outbox",
     *        "content://mms/trash".
     * @return New Uri of the moved PDU.
     * @throws MmsException Error occurred while moving the message.
     */
    @Throws(MmsException::class)
    fun move(from: Uri, to: Uri): Uri {
        // Check whether the 'msgId' has been assigned a valid value.
        val msgId = ContentUris.parseId(from)
        if (msgId == -1L) {
            throw MmsException("Error! ID of the message: -1.")
        }

        // Get corresponding int value of destination box.
        val msgBox = MESSAGE_BOX_MAP[to]
            ?: throw MmsException(
                "Bad destination, must be one of " +
                    "content://mms/inbox, content://mms/sent, " +
                    "content://mms/drafts, content://mms/outbox, " +
                    "content://mms/temp.",
            )

        val values = ContentValues(1)
        values.put(Mms.MESSAGE_BOX, msgBox)
        SqliteWrapper.update(mContext, mContentResolver, from, values, null, null)
        return ContentUris.withAppendedId(to, msgId)
    }

    /**
     * Remove all objects in the temporary path.
     */
    fun release() {
        val uri = Uri.parse(TEMPORARY_DRM_OBJECT_URI)
        SqliteWrapper.delete(mContext, mContentResolver, uri, null, null)
    }

    /**
     * Find all messages to be sent or downloaded before certain time.
     */
    fun getPendingMessages(dueTime: Long): Cursor? {
        if (!checkReadSmsPermissions()) {
            Log.w(TAG, "No read sms permissions have been granted")
            return null
        }
        val uriBuilder = PendingMessages.CONTENT_URI.buildUpon()
        uriBuilder.appendQueryParameter("protocol", "mms")

        val selection = PendingMessages.ERROR_TYPE + " < ?" +
            " AND " + PendingMessages.DUE_TIME + " <= ?"

        val selectionArgs = arrayOf(
            MmsSms.ERR_TYPE_GENERIC_PERMANENT.toString(),
            dueTime.toString(),
        )

        return SqliteWrapper.query(
            mContext, mContentResolver,
            uriBuilder.build(), null, selection, selectionArgs,
            PendingMessages.DUE_TIME,
        )
    }

    /**
     * Check if read permissions for SMS have been granted
     */
    private fun checkReadSmsPermissions(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            mContext.checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "PduPersister"

        @Suppress("unused")
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        private const val DUMMY_THREAD_ID = Long.MAX_VALUE

        @Suppress("unused")
        private const val DEFAULT_SUBSCRIPTION = 0
        private const val MAX_TEXT_BODY_SIZE = 300 * 1024

        /**
         * The uri of temporary drm objects.
         */
        @JvmField
        val TEMPORARY_DRM_OBJECT_URI = "content://mms/" + Long.MAX_VALUE + "/part"

        /**
         * Indicate that we transiently failed to process a MM.
         */
        const val PROC_STATUS_TRANSIENT_FAILURE = 1

        /**
         * Indicate that we permanently failed to process a MM.
         */
        const val PROC_STATUS_PERMANENTLY_FAILURE = 2

        /**
         * Indicate that we have successfully processed a MM.
         */
        const val PROC_STATUS_COMPLETED = 3

        private var sPersister: PduPersister? = null
        private val PDU_CACHE_INSTANCE: PduCache

        private val ADDRESS_FIELDS = intArrayOf(
            PduHeaders.BCC,
            PduHeaders.CC,
            PduHeaders.FROM,
            PduHeaders.TO,
        )

        private val PDU_PROJECTION = arrayOf(
            Mms._ID,
            Mms.MESSAGE_BOX,
            Mms.THREAD_ID,
            Mms.RETRIEVE_TEXT,
            Mms.SUBJECT,
            Mms.CONTENT_LOCATION,
            Mms.CONTENT_TYPE,
            Mms.MESSAGE_CLASS,
            Mms.MESSAGE_ID,
            Mms.RESPONSE_TEXT,
            Mms.TRANSACTION_ID,
            Mms.CONTENT_CLASS,
            Mms.DELIVERY_REPORT,
            Mms.MESSAGE_TYPE,
            Mms.MMS_VERSION,
            Mms.PRIORITY,
            Mms.READ_REPORT,
            Mms.READ_STATUS,
            Mms.REPORT_ALLOWED,
            Mms.RETRIEVE_STATUS,
            Mms.STATUS,
            Mms.DATE,
            Mms.DELIVERY_TIME,
            Mms.EXPIRY,
            Mms.MESSAGE_SIZE,
            Mms.SUBJECT_CHARSET,
            Mms.RETRIEVE_TEXT_CHARSET,
        )

        private const val PDU_COLUMN_ID = 0
        private const val PDU_COLUMN_MESSAGE_BOX = 1
        private const val PDU_COLUMN_THREAD_ID = 2
        private const val PDU_COLUMN_RETRIEVE_TEXT = 3
        private const val PDU_COLUMN_SUBJECT = 4
        private const val PDU_COLUMN_CONTENT_LOCATION = 5
        private const val PDU_COLUMN_CONTENT_TYPE = 6
        private const val PDU_COLUMN_MESSAGE_CLASS = 7
        private const val PDU_COLUMN_MESSAGE_ID = 8
        private const val PDU_COLUMN_RESPONSE_TEXT = 9
        private const val PDU_COLUMN_TRANSACTION_ID = 10
        private const val PDU_COLUMN_CONTENT_CLASS = 11
        private const val PDU_COLUMN_DELIVERY_REPORT = 12
        private const val PDU_COLUMN_MESSAGE_TYPE = 13
        private const val PDU_COLUMN_MMS_VERSION = 14
        private const val PDU_COLUMN_PRIORITY = 15
        private const val PDU_COLUMN_READ_REPORT = 16
        private const val PDU_COLUMN_READ_STATUS = 17
        private const val PDU_COLUMN_REPORT_ALLOWED = 18
        private const val PDU_COLUMN_RETRIEVE_STATUS = 19
        private const val PDU_COLUMN_STATUS = 20
        private const val PDU_COLUMN_DATE = 21
        private const val PDU_COLUMN_DELIVERY_TIME = 22
        private const val PDU_COLUMN_EXPIRY = 23
        private const val PDU_COLUMN_MESSAGE_SIZE = 24
        private const val PDU_COLUMN_SUBJECT_CHARSET = 25
        private const val PDU_COLUMN_RETRIEVE_TEXT_CHARSET = 26

        private val PART_PROJECTION = arrayOf(
            Part._ID,
            Part.CHARSET,
            Part.CONTENT_DISPOSITION,
            Part.CONTENT_ID,
            Part.CONTENT_LOCATION,
            Part.CONTENT_TYPE,
            Part.FILENAME,
            Part.NAME,
            Part.TEXT,
        )

        private const val PART_COLUMN_ID = 0
        private const val PART_COLUMN_CHARSET = 1
        private const val PART_COLUMN_CONTENT_DISPOSITION = 2
        private const val PART_COLUMN_CONTENT_ID = 3
        private const val PART_COLUMN_CONTENT_LOCATION = 4
        private const val PART_COLUMN_CONTENT_TYPE = 5
        private const val PART_COLUMN_FILENAME = 6
        private const val PART_COLUMN_NAME = 7
        private const val PART_COLUMN_TEXT = 8

        private val MESSAGE_BOX_MAP: HashMap<Uri, Int>

        // These map are used for convenience in persist() and load().
        private val CHARSET_COLUMN_INDEX_MAP: HashMap<Int, Int>
        private val ENCODED_STRING_COLUMN_INDEX_MAP: HashMap<Int, Int>
        private val TEXT_STRING_COLUMN_INDEX_MAP: HashMap<Int, Int>
        private val OCTET_COLUMN_INDEX_MAP: HashMap<Int, Int>
        private val LONG_COLUMN_INDEX_MAP: HashMap<Int, Int>
        private val CHARSET_COLUMN_NAME_MAP: HashMap<Int, String>
        private val ENCODED_STRING_COLUMN_NAME_MAP: HashMap<Int, String>
        private val TEXT_STRING_COLUMN_NAME_MAP: HashMap<Int, String>
        private val OCTET_COLUMN_NAME_MAP: HashMap<Int, String>
        private val LONG_COLUMN_NAME_MAP: HashMap<Int, String>

        init {
            MESSAGE_BOX_MAP = HashMap()
            MESSAGE_BOX_MAP[Mms.Inbox.CONTENT_URI] = Mms.MESSAGE_BOX_INBOX
            MESSAGE_BOX_MAP[Mms.Sent.CONTENT_URI] = Mms.MESSAGE_BOX_SENT
            MESSAGE_BOX_MAP[Mms.Draft.CONTENT_URI] = Mms.MESSAGE_BOX_DRAFTS
            MESSAGE_BOX_MAP[Mms.Outbox.CONTENT_URI] = Mms.MESSAGE_BOX_OUTBOX

            CHARSET_COLUMN_INDEX_MAP = HashMap()
            CHARSET_COLUMN_INDEX_MAP[PduHeaders.SUBJECT] = PDU_COLUMN_SUBJECT_CHARSET
            CHARSET_COLUMN_INDEX_MAP[PduHeaders.RETRIEVE_TEXT] = PDU_COLUMN_RETRIEVE_TEXT_CHARSET

            CHARSET_COLUMN_NAME_MAP = HashMap()
            CHARSET_COLUMN_NAME_MAP[PduHeaders.SUBJECT] = Mms.SUBJECT_CHARSET
            CHARSET_COLUMN_NAME_MAP[PduHeaders.RETRIEVE_TEXT] = Mms.RETRIEVE_TEXT_CHARSET

            // Encoded string field code -> column index/name map.
            ENCODED_STRING_COLUMN_INDEX_MAP = HashMap()
            ENCODED_STRING_COLUMN_INDEX_MAP[PduHeaders.RETRIEVE_TEXT] = PDU_COLUMN_RETRIEVE_TEXT
            ENCODED_STRING_COLUMN_INDEX_MAP[PduHeaders.SUBJECT] = PDU_COLUMN_SUBJECT

            ENCODED_STRING_COLUMN_NAME_MAP = HashMap()
            ENCODED_STRING_COLUMN_NAME_MAP[PduHeaders.RETRIEVE_TEXT] = Mms.RETRIEVE_TEXT
            ENCODED_STRING_COLUMN_NAME_MAP[PduHeaders.SUBJECT] = Mms.SUBJECT

            // Text string field code -> column index/name map.
            TEXT_STRING_COLUMN_INDEX_MAP = HashMap()
            TEXT_STRING_COLUMN_INDEX_MAP[PduHeaders.CONTENT_LOCATION] = PDU_COLUMN_CONTENT_LOCATION
            TEXT_STRING_COLUMN_INDEX_MAP[PduHeaders.CONTENT_TYPE] = PDU_COLUMN_CONTENT_TYPE
            TEXT_STRING_COLUMN_INDEX_MAP[PduHeaders.MESSAGE_CLASS] = PDU_COLUMN_MESSAGE_CLASS
            TEXT_STRING_COLUMN_INDEX_MAP[PduHeaders.MESSAGE_ID] = PDU_COLUMN_MESSAGE_ID
            TEXT_STRING_COLUMN_INDEX_MAP[PduHeaders.RESPONSE_TEXT] = PDU_COLUMN_RESPONSE_TEXT
            TEXT_STRING_COLUMN_INDEX_MAP[PduHeaders.TRANSACTION_ID] = PDU_COLUMN_TRANSACTION_ID

            TEXT_STRING_COLUMN_NAME_MAP = HashMap()
            TEXT_STRING_COLUMN_NAME_MAP[PduHeaders.CONTENT_LOCATION] = Mms.CONTENT_LOCATION
            TEXT_STRING_COLUMN_NAME_MAP[PduHeaders.CONTENT_TYPE] = Mms.CONTENT_TYPE
            TEXT_STRING_COLUMN_NAME_MAP[PduHeaders.MESSAGE_CLASS] = Mms.MESSAGE_CLASS
            TEXT_STRING_COLUMN_NAME_MAP[PduHeaders.MESSAGE_ID] = Mms.MESSAGE_ID
            TEXT_STRING_COLUMN_NAME_MAP[PduHeaders.RESPONSE_TEXT] = Mms.RESPONSE_TEXT
            TEXT_STRING_COLUMN_NAME_MAP[PduHeaders.TRANSACTION_ID] = Mms.TRANSACTION_ID

            // Octet field code -> column index/name map.
            OCTET_COLUMN_INDEX_MAP = HashMap()
            OCTET_COLUMN_INDEX_MAP[PduHeaders.CONTENT_CLASS] = PDU_COLUMN_CONTENT_CLASS
            OCTET_COLUMN_INDEX_MAP[PduHeaders.DELIVERY_REPORT] = PDU_COLUMN_DELIVERY_REPORT
            OCTET_COLUMN_INDEX_MAP[PduHeaders.MESSAGE_TYPE] = PDU_COLUMN_MESSAGE_TYPE
            OCTET_COLUMN_INDEX_MAP[PduHeaders.MMS_VERSION] = PDU_COLUMN_MMS_VERSION
            OCTET_COLUMN_INDEX_MAP[PduHeaders.PRIORITY] = PDU_COLUMN_PRIORITY
            OCTET_COLUMN_INDEX_MAP[PduHeaders.READ_REPORT] = PDU_COLUMN_READ_REPORT
            OCTET_COLUMN_INDEX_MAP[PduHeaders.READ_STATUS] = PDU_COLUMN_READ_STATUS
            OCTET_COLUMN_INDEX_MAP[PduHeaders.REPORT_ALLOWED] = PDU_COLUMN_REPORT_ALLOWED
            OCTET_COLUMN_INDEX_MAP[PduHeaders.RETRIEVE_STATUS] = PDU_COLUMN_RETRIEVE_STATUS
            OCTET_COLUMN_INDEX_MAP[PduHeaders.STATUS] = PDU_COLUMN_STATUS

            OCTET_COLUMN_NAME_MAP = HashMap()
            OCTET_COLUMN_NAME_MAP[PduHeaders.CONTENT_CLASS] = Mms.CONTENT_CLASS
            OCTET_COLUMN_NAME_MAP[PduHeaders.DELIVERY_REPORT] = Mms.DELIVERY_REPORT
            OCTET_COLUMN_NAME_MAP[PduHeaders.MESSAGE_TYPE] = Mms.MESSAGE_TYPE
            OCTET_COLUMN_NAME_MAP[PduHeaders.MMS_VERSION] = Mms.MMS_VERSION
            OCTET_COLUMN_NAME_MAP[PduHeaders.PRIORITY] = Mms.PRIORITY
            OCTET_COLUMN_NAME_MAP[PduHeaders.READ_REPORT] = Mms.READ_REPORT
            OCTET_COLUMN_NAME_MAP[PduHeaders.READ_STATUS] = Mms.READ_STATUS
            OCTET_COLUMN_NAME_MAP[PduHeaders.REPORT_ALLOWED] = Mms.REPORT_ALLOWED
            OCTET_COLUMN_NAME_MAP[PduHeaders.RETRIEVE_STATUS] = Mms.RETRIEVE_STATUS
            OCTET_COLUMN_NAME_MAP[PduHeaders.STATUS] = Mms.STATUS

            // Long field code -> column index/name map.
            LONG_COLUMN_INDEX_MAP = HashMap()
            LONG_COLUMN_INDEX_MAP[PduHeaders.DATE] = PDU_COLUMN_DATE
            LONG_COLUMN_INDEX_MAP[PduHeaders.DELIVERY_TIME] = PDU_COLUMN_DELIVERY_TIME
            LONG_COLUMN_INDEX_MAP[PduHeaders.EXPIRY] = PDU_COLUMN_EXPIRY
            LONG_COLUMN_INDEX_MAP[PduHeaders.MESSAGE_SIZE] = PDU_COLUMN_MESSAGE_SIZE

            LONG_COLUMN_NAME_MAP = HashMap()
            LONG_COLUMN_NAME_MAP[PduHeaders.DATE] = Mms.DATE
            LONG_COLUMN_NAME_MAP[PduHeaders.DELIVERY_TIME] = Mms.DELIVERY_TIME
            LONG_COLUMN_NAME_MAP[PduHeaders.EXPIRY] = Mms.EXPIRY
            LONG_COLUMN_NAME_MAP[PduHeaders.MESSAGE_SIZE] = Mms.MESSAGE_SIZE

            PDU_CACHE_INSTANCE = PduCache.getInstance()
        }

        /** Get(or create if not exist) an instance of PduPersister */
        @JvmStatic
        fun getPduPersister(context: Context): PduPersister {
            if (sPersister == null) {
                sPersister = PduPersister(context)
            } else if (context != sPersister!!.mContext) {
                sPersister!!.release()
                sPersister = PduPersister(context)
            }

            return sPersister!!
        }

        private fun getPartContentType(part: PduPart): String? {
            return if (part.contentType == null) null else toIsoString(part.contentType)
        }

        @JvmStatic
        fun cutString(src: String, expectSize: Int): String {
            if (src.isEmpty()) {
                return ""
            }

            val builder = StringBuilder(expectSize)
            val length = src.length
            var i = 0
            var size = 0
            while (i < length) {
                val codePoint = Character.codePointAt(src, i)
                if (Character.charCount(codePoint) == 1) {
                    size += 1
                    if (size > expectSize) {
                        break
                    }
                    builder.append(codePoint.toChar())
                } else {
                    val chars = Character.toChars(codePoint)
                    size += chars.size
                    if (size > expectSize) {
                        break
                    }
                    builder.append(chars)
                }
                i = Character.offsetByCodePoints(src, i, 1)
            }
            return builder.toString()
        }

        /**
         * This method expects uri in the following format
         *     content://media/<table_name>/<row_index> (or)
         *     file://sdcard/test.mp4
         *     http://test.com/test.mp4
         *
         * Here <table_name> shall be "video" or "audio" or "images"
         * <row_index> the index of the content in given table
         */
        @JvmStatic
        fun convertUriToPath(context: Context, uri: Uri?): String? {
            var path: String? = null
            if (null != uri) {
                val scheme = uri.scheme
                if (null == scheme || scheme == "" ||
                    scheme == ContentResolver.SCHEME_FILE
                ) {
                    path = uri.path
                } else if (scheme == "http") {
                    path = uri.toString()
                } else if (scheme == ContentResolver.SCHEME_CONTENT) {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    var cursor: Cursor? = null
                    try {
                        cursor = context.contentResolver.query(
                            uri, projection, null,
                            null, null,
                        )
                        if (null == cursor || 0 == cursor.count || !cursor.moveToFirst()) {
                            throw IllegalArgumentException(
                                "Given Uri could not be found in media store",
                            )
                        }
                        val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        path = cursor.getString(pathIndex)
                    } catch (e: SQLiteException) {
                        throw IllegalArgumentException(
                            "Given Uri is not formatted in a way " +
                                "so that it can be found in media store.",
                        )
                    } finally {
                        cursor?.close()
                    }
                } else {
                    throw IllegalArgumentException("Given Uri scheme is not supported")
                }
            }
            return path
        }

        /**
         * Wrap a byte[] into a String.
         */
        @JvmStatic
        fun toIsoString(bytes: ByteArray?): String {
            // iso-8859-1 is always supported, so the vendored unreachable
            // UnsupportedEncodingException catch is dropped. `bytes!!` preserves
            // the NPE-on-null of the vendored `new String((byte[]) null, ...)`.
            return String(bytes!!, Charsets.ISO_8859_1)
        }

        /**
         * Unpack a given String into a byte[].
         */
        @JvmStatic
        fun getBytes(data: String?): ByteArray {
            // See toIsoString: iso-8859-1 is always supported; `data!!` preserves
            // the vendored NPE-on-null of `((String) null).getBytes(...)`.
            return data!!.toByteArray(Charsets.ISO_8859_1)
        }
    }
}

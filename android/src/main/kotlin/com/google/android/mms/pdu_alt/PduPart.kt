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

import android.net.Uri
import java.nio.charset.Charset

/**
 * The pdu part.
 *
 * First-party Kotlin port of the vendored `PduPart`; logic preserved 1:1.
 * Consumed by the (still-Java) `pdu_alt` codec (`PduParser`, `PduComposer`,
 * `PduPersister`, `PduBody`) and by first-party Kotlin (`Transaction`,
 * `SmilPresentationBuilder`, `MmsPart`).
 *
 * Accessor shape: the value getters are Kotlin `val` properties (so they
 * compile to the same `getData()` / `getContentType()` / … for Java callers
 * **and** keep working as `.data` / `.contentType` / … for the Kotlin
 * consumers that used property access); the setters stay `fun setX(...)`
 * methods (Java + Kotlin call them the same way, and the get/set logic is
 * asymmetric — e.g. `setContentId` wraps in `<…>`). The `P_*` constants and the
 * `DISPOSITION_*` byte arrays are exposed via the companion (`const val` /
 * `@JvmField`) so the Java codec keeps reading `PduPart.P_CHARSET`,
 * `PduPart.DISPOSITION_FROM_DATA`, etc.
 */
class PduPart {

    /**
     * Header of part.
     */
    private val mPartHeader: MutableMap<Int, Any> = HashMap()

    /**
     * Data uri.
     */
    private var mUri: Uri? = null

    /**
     * Part data.
     */
    private var mPartData: ByteArray? = null

    /**
     * Set part data. The data are stored as byte array.
     *
     * @param data the data
     */
    fun setData(data: ByteArray?) {
        if (data == null) {
            return
        }

        mPartData = ByteArray(data.size)
        System.arraycopy(data, 0, mPartData!!, 0, data.size)
    }

    /**
     * A copy of the part data or null if the data wasn't set or the data is
     * stored as Uri.
     *
     * @see getDataUri
     */
    val data: ByteArray?
        get() {
            if (mPartData == null) {
                return null
            }

            val byteArray = ByteArray(mPartData!!.size)
            System.arraycopy(mPartData!!, 0, byteArray, 0, mPartData!!.size)
            return byteArray
        }

    /**
     * The length of the data, if this object have data, else 0.
     */
    val dataLength: Int
        get() {
            return if (mPartData != null) {
                mPartData!!.size
            } else {
                0
            }
        }

    /**
     * Set data uri. The data are stored as Uri.
     *
     * @param uri the uri
     */
    fun setDataUri(uri: Uri?) {
        mUri = uri
    }

    /**
     * The Uri of the part data or null if the data wasn't set or the data is
     * stored as byte array.
     *
     * @see data
     */
    val dataUri: Uri?
        get() = mUri

    /**
     * Set Content-id value
     *
     * @param contentId the content-id value
     * @throws NullPointerException if the value is null.
     */
    fun setContentId(contentId: ByteArray?) {
        if ((contentId == null) || (contentId.isEmpty())) {
            throw IllegalArgumentException(
                "Content-Id may not be null or empty.",
            )
        }

        if ((contentId.size > 1) &&
            (contentId[0].toInt().toChar() == '<') &&
            (contentId[contentId.size - 1].toInt().toChar() == '>')
        ) {
            mPartHeader[P_CONTENT_ID] = contentId
            return
        }

        // Insert beginning '<' and trailing '>' for Content-Id.
        val buffer = ByteArray(contentId.size + 2)
        buffer[0] = (0xff and '<'.code).toByte()
        buffer[buffer.size - 1] = (0xff and '>'.code).toByte()
        System.arraycopy(contentId, 0, buffer, 1, contentId.size)
        mPartHeader[P_CONTENT_ID] = buffer
    }

    /**
     * Get Content-id value.
     *
     * @return the value
     */
    val contentId: ByteArray?
        get() = mPartHeader[P_CONTENT_ID] as ByteArray?

    /**
     * Set Char-set value.
     *
     * @param charset the value
     */
    fun setCharset(charset: Int) {
        mPartHeader[P_CHARSET] = charset
    }

    /**
     * Get Char-set value. Returns 0 if charset was not set.
     */
    val charset: Int
        get() {
            val charset = mPartHeader[P_CHARSET] as Int?
            return charset ?: 0
        }

    /**
     * Set Content-Location value.
     *
     * @param contentLocation the value
     * @throws NullPointerException if the value is null.
     */
    fun setContentLocation(contentLocation: ByteArray?) {
        if (contentLocation == null) {
            throw NullPointerException("null content-location")
        }

        mPartHeader[P_CONTENT_LOCATION] = contentLocation
    }

    /**
     * Get Content-Location value.
     *
     * @return the value
     *     return PduPart.disposition[0] instead of <Octet 128> (Form-data).
     *     return PduPart.disposition[1] instead of <Octet 129> (Attachment).
     *     return PduPart.disposition[2] instead of <Octet 130> (Inline).
     */
    val contentLocation: ByteArray?
        get() = mPartHeader[P_CONTENT_LOCATION] as ByteArray?

    /**
     * Set Content-Disposition value.
     * Use PduPart.disposition[0] instead of <Octet 128> (Form-data).
     * Use PduPart.disposition[1] instead of <Octet 129> (Attachment).
     * Use PduPart.disposition[2] instead of <Octet 130> (Inline).
     *
     * @param contentDisposition the value
     * @throws NullPointerException if the value is null.
     */
    fun setContentDisposition(contentDisposition: ByteArray?) {
        if (contentDisposition == null) {
            throw NullPointerException("null content-disposition")
        }

        mPartHeader[P_CONTENT_DISPOSITION] = contentDisposition
    }

    /**
     * Get Content-Disposition value.
     *
     * @return the value
     */
    val contentDisposition: ByteArray?
        get() = mPartHeader[P_CONTENT_DISPOSITION] as ByteArray?

    /**
     *  Set Content-Type value.
     *
     *  @param contentType the value
     *  @throws NullPointerException if the value is null.
     */
    fun setContentType(contentType: ByteArray?) {
        if (contentType == null) {
            throw NullPointerException("null content-type")
        }

        mPartHeader[P_CONTENT_TYPE] = contentType
    }

    /**
     * Get Content-Type value of part.
     *
     * @return the value
     */
    val contentType: ByteArray?
        get() = mPartHeader[P_CONTENT_TYPE] as ByteArray?

    /**
     * Set Content-Transfer-Encoding value
     *
     * @param contentTransferEncoding the content-id value
     * @throws NullPointerException if the value is null.
     */
    fun setContentTransferEncoding(contentTransferEncoding: ByteArray?) {
        if (contentTransferEncoding == null) {
            throw NullPointerException("null content-transfer-encoding")
        }

        mPartHeader[P_CONTENT_TRANSFER_ENCODING] = contentTransferEncoding
    }

    /**
     * Get Content-Transfer-Encoding value.
     *
     * @return the value
     */
    val contentTransferEncoding: ByteArray?
        get() = mPartHeader[P_CONTENT_TRANSFER_ENCODING] as ByteArray?

    /**
     * Set Content-type parameter: name.
     *
     * @param name the name value
     * @throws NullPointerException if the value is null.
     */
    fun setName(name: ByteArray?) {
        if (null == name) {
            throw NullPointerException("null content-id")
        }

        mPartHeader[P_NAME] = name
    }

    /**
     *  Get content-type parameter: name.
     *
     *  @return the name
     */
    val name: ByteArray?
        get() = mPartHeader[P_NAME] as ByteArray?

    /**
     * Set Content-disposition parameter: filename
     *
     * @param fileName the filename value
     * @throws NullPointerException if the value is null.
     */
    fun setFilename(fileName: ByteArray?) {
        if (null == fileName) {
            throw NullPointerException("null content-id")
        }

        mPartHeader[P_FILENAME] = fileName
    }

    /**
     * Get Content-disposition parameter: filename
     *
     * @return the filename
     */
    val filename: ByteArray?
        get() = mPartHeader[P_FILENAME] as ByteArray?

    fun generateLocation(): String {
        // Assumption: At least one of the content-location / name / filename
        // or content-id should be set. This is guaranteed by the PduParser
        // for incoming messages and by MM composer for outgoing messages.
        var location = mPartHeader[P_NAME] as ByteArray?
        if (null == location) {
            location = mPartHeader[P_FILENAME] as ByteArray?

            if (null == location) {
                location = mPartHeader[P_CONTENT_LOCATION] as ByteArray?
            }
        }

        return if (null == location) {
            val contentId = mPartHeader[P_CONTENT_ID] as ByteArray?
            "cid:" + String(contentId!!, Charset.defaultCharset())
        } else {
            String(location, Charset.defaultCharset())
        }
    }

    companion object {
        /**
         * Well-Known Parameters.
         */
        const val P_Q = 0x80
        const val P_CHARSET = 0x81
        const val P_LEVEL = 0x82
        const val P_TYPE = 0x83
        const val P_DEP_NAME = 0x85
        const val P_DEP_FILENAME = 0x86
        const val P_DIFFERENCES = 0x87
        const val P_PADDING = 0x88

        // This value of "TYPE" s used with Content-Type: multipart/related
        const val P_CT_MR_TYPE = 0x89
        const val P_DEP_START = 0x8A
        const val P_DEP_START_INFO = 0x8B
        const val P_DEP_COMMENT = 0x8C
        const val P_DEP_DOMAIN = 0x8D
        const val P_MAX_AGE = 0x8E
        const val P_DEP_PATH = 0x8F
        const val P_SECURE = 0x90
        const val P_SEC = 0x91
        const val P_MAC = 0x92
        const val P_CREATION_DATE = 0x93
        const val P_MODIFICATION_DATE = 0x94
        const val P_READ_DATE = 0x95
        const val P_SIZE = 0x96
        const val P_NAME = 0x97
        const val P_FILENAME = 0x98
        const val P_START = 0x99
        const val P_START_INFO = 0x9A
        const val P_COMMENT = 0x9B
        const val P_DOMAIN = 0x9C
        const val P_PATH = 0x9D

        /**
         *  Header field names.
         */
        const val P_CONTENT_TYPE = 0x91
        const val P_CONTENT_LOCATION = 0x8E
        const val P_CONTENT_ID = 0xC0
        const val P_DEP_CONTENT_DISPOSITION = 0xAE
        const val P_CONTENT_DISPOSITION = 0xC5

        // The next header is unassigned header, use reserved header(0x48) value.
        const val P_CONTENT_TRANSFER_ENCODING = 0xC8

        /**
         * Content=Transfer-Encoding string.
         */
        const val CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding"

        /**
         * Value of Content-Transfer-Encoding.
         */
        const val P_BINARY = "binary"
        const val P_7BIT = "7bit"
        const val P_8BIT = "8bit"
        const val P_BASE64 = "base64"
        const val P_QUOTED_PRINTABLE = "quoted-printable"

        /**
         * Value of disposition can be set to PduPart when the value is octet in
         * the PDU.
         * "from-data" instead of Form-data<Octet 128>.
         * "attachment" instead of Attachment<Octet 129>.
         * "inline" instead of Inline<Octet 130>.
         */
        @JvmField
        val DISPOSITION_FROM_DATA: ByteArray = "from-data".toByteArray(Charset.defaultCharset())

        @JvmField
        val DISPOSITION_ATTACHMENT: ByteArray = "attachment".toByteArray(Charset.defaultCharset())

        @JvmField
        val DISPOSITION_INLINE: ByteArray = "inline".toByteArray(Charset.defaultCharset())

        /**
         * Content-Disposition value.
         */
        const val P_DISPOSITION_FROM_DATA = 0x80
        const val P_DISPOSITION_ATTACHMENT = 0x81
        const val P_DISPOSITION_INLINE = 0x82

        private const val TAG = "PduPart"
    }
}

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

import com.google.android.mms.InvalidHeaderValueException

/**
 * First-party Kotlin port of the vendored `ReadOrigInd` (Phase 5 · pdu_alt), a
 * [GenericPdu] subclass. Behaviour-faithful 1:1.
 *
 * `from`/`setFrom` are `override`s of [GenericPdu]'s `open` `from`/`setFrom`
 * (the vendored re-declares them with identical bodies). The `(PduHeaders)`
 * parse constructor is widened package-private → `public` for the same-package
 * non-subclass `PduParser`; the new getters are `val` properties; `mPduHeaders`
 * is dereferenced with `!!`, mirroring the vendored bare derefs.
 */
class ReadOrigInd : GenericPdu {
    /**
     * Empty constructor.
     * Since the Pdu corresponding to this class is constructed
     * by the Proxy-Relay server, this class is only instantiated
     * by the Pdu Parser.
     *
     * @throws InvalidHeaderValueException if error occurs.
     */
    @Throws(InvalidHeaderValueException::class)
    constructor() : super() {
        setMessageType(PduHeaders.MESSAGE_TYPE_READ_ORIG_IND)
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    constructor(headers: PduHeaders?) : super(headers)

    /**
     * Get Date value.
     *
     * @return the value
     */
    val date: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.DATE)

    /**
     * Set Date value.
     *
     * @param value the value
     */
    fun setDate(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.DATE)
    }

    /**
     * Get From value.
     * From-value = Value-length
     *      (Address-present-token Encoded-string-value | Insert-address-token)
     *
     * @return the value
     */
    override val from: EncodedStringValue?
        get() = mPduHeaders!!.getEncodedStringValue(PduHeaders.FROM)

    /**
     * Set From value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    override fun setFrom(value: EncodedStringValue?) {
        mPduHeaders!!.setEncodedStringValue(value, PduHeaders.FROM)
    }

    /**
     * Get Message-ID value.
     *
     * @return the value
     */
    val messageId: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.MESSAGE_ID)

    /**
     * Set Message-ID value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setMessageId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_ID)
    }

    /**
     * Get X-MMS-Read-status value.
     *
     * @return the value
     */
    val readStatus: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.READ_STATUS)

    /**
     * Set X-MMS-Read-status value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setReadStatus(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.READ_STATUS)
    }

    /**
     * Get To value.
     *
     * @return the value
     */
    val to: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.TO)

    /**
     * Set To value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setTo(value: Array<EncodedStringValue>?) {
        mPduHeaders!!.setEncodedStringValues(value, PduHeaders.TO)
    }

    /*
     * Optional, not supported header fields:
     *
     *     public byte[] getApplicId() {return null;}
     *     public void setApplicId(byte[] value) {}
     *
     *     public byte[] getAuxApplicId() {return null;}
     *     public void getAuxApplicId(byte[] value) {}
     *
     *     public byte[] getReplyApplicId() {return 0x00;}
     *     public void setReplyApplicId(byte[] value) {}
     */
}

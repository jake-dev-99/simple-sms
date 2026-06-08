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
 * M-Retrive.conf Pdu.
 *
 * First-party Kotlin port of the vendored `RetrieveConf` (Phase 5 · pdu_alt), a
 * [MultimediaMessagePdu] subclass. Behaviour-faithful 1:1. `from`/`setFrom`
 * `override` [GenericPdu]'s `open` `from`/`setFrom` (the vendored re-declares
 * them with identical bodies). The `(PduHeaders)` and `(PduHeaders, PduBody)`
 * parse constructors are widened package-private → `public` for the same-package
 * non-subclass `PduParser`; getters are `val` properties (`cc` is val + `addCc`,
 * like `to`/`addTo`); `mPduHeaders` is dereferenced with `!!`, mirroring the
 * vendored bare derefs.
 */
class RetrieveConf : MultimediaMessagePdu {
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
        setMessageType(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    constructor(headers: PduHeaders?) : super(headers)

    /**
     * Constructor with given headers and body
     *
     * @param headers Headers for this PDU.
     * @param body Body of this PDu.
     */
    constructor(headers: PduHeaders?, body: PduBody?) : super(headers, body)

    /**
     * Get CC value.
     *
     * @return the value
     */
    val cc: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.CC)

    /**
     * Add a "CC" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun addCc(value: EncodedStringValue?) {
        mPduHeaders!!.appendEncodedStringValue(value, PduHeaders.CC)
    }

    /**
     * Get Content-type value.
     *
     * @return the value
     */
    val contentType: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.CONTENT_TYPE)

    /**
     * Set Content-type value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setContentType(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.CONTENT_TYPE)
    }

    /**
     * Get X-Mms-Delivery-Report value.
     *
     * @return the value
     */
    val deliveryReport: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.DELIVERY_REPORT)

    /**
     * Set X-Mms-Delivery-Report value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setDeliveryReport(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.DELIVERY_REPORT)
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
     * Get X-Mms-Message-Class value.
     * Message-class-value = Class-identifier | Token-text
     * Class-identifier = Personal | Advertisement | Informational | Auto
     *
     * @return the value
     */
    val messageClass: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.MESSAGE_CLASS)

    /**
     * Set X-Mms-Message-Class value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setMessageClass(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_CLASS)
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
     * Get X-Mms-Read-Report value.
     *
     * @return the value
     */
    val readReport: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.READ_REPORT)

    /**
     * Set X-Mms-Read-Report value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setReadReport(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.READ_REPORT)
    }

    /**
     * Get X-Mms-Retrieve-Status value.
     *
     * @return the value
     */
    val retrieveStatus: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.RETRIEVE_STATUS)

    /**
     * Set X-Mms-Retrieve-Status value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setRetrieveStatus(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.RETRIEVE_STATUS)
    }

    /**
     * Get X-Mms-Retrieve-Text value.
     *
     * @return the value
     */
    val retrieveText: EncodedStringValue?
        get() = mPduHeaders!!.getEncodedStringValue(PduHeaders.RETRIEVE_TEXT)

    /**
     * Set X-Mms-Retrieve-Text value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setRetrieveText(value: EncodedStringValue?) {
        mPduHeaders!!.setEncodedStringValue(value, PduHeaders.RETRIEVE_TEXT)
    }

    /**
     * Get X-Mms-Transaction-Id.
     *
     * @return the value
     */
    val transactionId: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.TRANSACTION_ID)

    /**
     * Set X-Mms-Transaction-Id.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setTransactionId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.TRANSACTION_ID)
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
     *     public byte getContentClass() {return 0x00;}
     *     public void setApplicId(byte value) {}
     *
     *     public byte getDrmContent() {return 0x00;}
     *     public void setDrmContent(byte value) {}
     *
     *     public byte getDistributionIndicator() {return 0x00;}
     *     public void setDistributionIndicator(byte value) {}
     *
     *     public PreviouslySentByValue getPreviouslySentBy() {return null;}
     *     public void setPreviouslySentBy(PreviouslySentByValue value) {}
     *
     *     public PreviouslySentDateValue getPreviouslySentDate() {}
     *     public void setPreviouslySentDate(PreviouslySentDateValue value) {}
     *
     *     public MmFlagsValue getMmFlags() {return null;}
     *     public void setMmFlags(MmFlagsValue value) {}
     *
     *     public MmStateValue getMmState() {return null;}
     *     public void getMmState(MmStateValue value) {}
     *
     *     public byte[] getReplaceId() {return 0x00;}
     *     public void setReplaceId(byte[] value) {}
     *
     *     public byte[] getReplyApplicId() {return 0x00;}
     *     public void setReplyApplicId(byte[] value) {}
     *
     *     public byte getReplyCharging() {return 0x00;}
     *     public void setReplyCharging(byte value) {}
     *
     *     public byte getReplyChargingDeadline() {return 0x00;}
     *     public void setReplyChargingDeadline(byte value) {}
     *
     *     public byte[] getReplyChargingId() {return 0x00;}
     *     public void setReplyChargingId(byte[] value) {}
     *
     *     public long getReplyChargingSize() {return 0;}
     *     public void setReplyChargingSize(long value) {}
     */
}
